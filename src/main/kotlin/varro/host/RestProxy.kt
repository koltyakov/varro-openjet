package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import varro.protocol.Json
import varro.protocol.arr
import varro.protocol.asArrayOrNull
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.int
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.str
import varro.protocol.strings
import varro.protocol.text
import varro.server.OpenCodeRequestScope
import varro.server.OpenCodeResponse
import varro.server.OpenCodeServer
import varro.server.RequestOptions
import varro.server.WorkspacePaths
import varro.store.VarroStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves `api/request` messages.
 *
 * Port of `src/extension/rest-proxy.ts`. Two kinds of request arrive on the same
 * channel:
 *
 *  - Paths under the `/varro` prefix are the host's own API namespace. These
 *    never reach OpenCode; the host answers them from IDE state and its stores.
 *  - Everything else is forwarded to OpenCode, after the [ApiRoutes] allowlist
 *    has approved it.
 *
 * Upstream additionally aggregates session catalogs across several VS Code
 * workspace folders. A JetBrains project has one primary root, so the catalog
 * here is scoped to that root plus the project's other content roots, which
 * removes most of upstream's cross-root authorization machinery while keeping
 * the same externally visible behaviour.
 */
class RestProxy internal constructor(
    private val backend: Backend,
    private val workspace: Workspace,
    private val store: VarroStore,
    private val services: HostServices,
    private val postResponse: (JsonObject) -> Unit,
    private val admitQueuedDispatch: (JsonObject) -> Boolean = { true },
    private val completeQueuedDispatch: (JsonObject, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val selections: SessionSelections = SessionSelections(store, request = { method, path, body, directory ->
        backend.request(method, path, body, RequestOptions(directory = directory)).data
    }),
    private val isPermissionAutomationLeaseCurrent: (Long) -> Boolean = { false },
    private val transfers: SessionTransfers? = null,
    private val grants: SessionGrants? = null,
    private val catalog: SessionCatalog? = null,
    /** Server-wide sessions per scoped catalog page; tests page through small released lists. */
    private val scopedCatalogPage: Int = SCOPED_CATALOG_PAGE,
) {
    constructor(
        project: Project,
        server: OpenCodeServer,
        store: VarroStore,
        context: ContextProvider,
        services: HostServices,
        postResponse: (JsonObject) -> Unit,
        admitQueuedDispatch: (JsonObject) -> Boolean = { true },
        completeQueuedDispatch: (JsonObject, Boolean, Boolean) -> Unit = { _, _, _ -> },
        selections: SessionSelections = SessionSelections(store, request = { method, path, body, directory ->
            server.transport.request(method, path, body, RequestOptions(directory = directory)).data
        }),
        isPermissionAutomationLeaseCurrent: (Long) -> Boolean = { false },
        transfers: SessionTransfers? = null,
        grants: SessionGrants? = null,
        catalog: SessionCatalog? = null,
    ) : this(ServerBackend(server), ProjectWorkspace(project, context), store, services, postResponse, admitQueuedDispatch,
        completeQueuedDispatch, selections, isPermissionAutomationLeaseCurrent, transfers, grants, catalog)

    /** The OpenCode server as the proxy uses it. Tests supply a recording fake. */
    interface Backend {
        val apiVersion: Int
        fun request(method: String, path: String, body: JsonElement? = null, options: RequestOptions = RequestOptions()): OpenCodeResponse
        fun workspaceDirectory(): String?
        fun url(): String
        fun requestGeneration(): Int
        fun isRunning(): Boolean
        fun isAttachOnly(): Boolean
        fun observedSessionDirectories(): Map<String, String>
        fun pendingAttentionSessionId(requestId: String): String?
        /** Sessions this host knows have a pending permission or question. */
        fun pendingAttentionSessionIds(): Collection<String>
        fun savedPermissions(method: String, sessionID: String?, body: JsonElement?, options: RequestOptions): JsonObject
        fun activateDirectory(directory: String?)
    }

    /** The IDE project the proxy serves. Tests supply fixed roots. */
    interface Workspace {
        val basePath: String?
        /** Open content roots beyond [basePath]. */
        fun folders(): List<String>
        /** The persistent recycle-bin journal. */
        fun trashJournal(): java.nio.file.Path
        fun citationSymbols(body: JsonElement?, directory: String?, authorize: (String?, String) -> Boolean, isCancelled: () -> Boolean): JsonElement
        fun matchCopiedSelection(text: String, plainTextOnly: Boolean): JsonElement?
        fun problems(): JsonElement
    }

    private class ServerBackend(private val server: OpenCodeServer) : Backend {
        override val apiVersion get() = server.transport.apiVersion
        override fun request(method: String, path: String, body: JsonElement?, options: RequestOptions) =
            server.transport.request(method, path, body, options)
        override fun workspaceDirectory() = server.workspaceDirectory()
        override fun url() = server.url()
        override fun requestGeneration() = server.requestGeneration()
        override fun isRunning() = server.currentStatus() is varro.server.ServerStatus.Running
        override fun isAttachOnly() = server.isAttachOnly()
        override fun observedSessionDirectories() = server.transport.observedSessionDirectories()
        override fun pendingAttentionSessionId(requestId: String) = server.transport.pendingAttentionSessionId(requestId)
        override fun pendingAttentionSessionIds(): Collection<String> = server.transport.pendingAttentionSessionIds()
        override fun savedPermissions(method: String, sessionID: String?, body: JsonElement?, options: RequestOptions) =
            server.transport.savedPermissions.handle(method, sessionID, body, options)
        override fun activateDirectory(directory: String?) = server.activateDirectory(directory)
    }

    private class ProjectWorkspace(private val project: Project, private val context: ContextProvider) : Workspace {
        override val basePath: String? get() = project.basePath
        override fun folders() = context.context.arr("workspaceFolders")?.mapNotNull { it.asObjectOrNull().text("path") }.orEmpty()
        override fun trashJournal(): java.nio.file.Path = java.nio.file.Path.of(
            com.intellij.openapi.application.PathManager.getConfigPath(), "varro", project.locationHash, "trash.json")
        override fun citationSymbols(body: JsonElement?, directory: String?, authorize: (String?, String) -> Boolean, isCancelled: () -> Boolean): JsonElement =
            context.citationSymbols.find(body, directory, authorize, isCancelled)
        override fun matchCopiedSelection(text: String, plainTextOnly: Boolean): JsonElement? = context.matchCopiedSelection(text, plainTextOnly)
        override fun problems(): JsonElement = WorkspaceProblems.snapshot(project)
    }

    private val log = logger<RestProxy>()
    private val modelPricing = ModelPricingCatalog()
    // One decoder and cache for every view, as the architecture guide documents.
    private val remoteImagesDelegate = lazy { RemoteImages(thumbnails = service<ImageThumbnails>(), ownsThumbnails = false) }
    private val remoteImages by remoteImagesDelegate
    private val localImagesDelegate = lazy { LocalImages(service<ImageThumbnails>(), ownsThumbnails = false) }
    private val localImages by localImagesDelegate
    private val citationPaths = CitationPaths()
    private val sideQuestions = SideQuestions(backend::request, { backend.apiVersion },
        { backend.isRunning() }, { backend.requestGeneration() to backend.url() },
        { (listOfNotNull(workspace.basePath) + workspace.folders()).distinct() },
        authorizeSessionDirectory = ::authorizedTransfer)
    private val settings get() = varro.settings.VarroSettings.getInstance()
    private fun workspaceRoots() = (listOfNotNull(workspace.basePath) +
        workspace.folders()).distinct()
    private val followupUsage = FollowupUsage(backend::request, { backend.apiVersion },
        { backend.requestGeneration() to backend.url() }, ::workspaceRoots,
        { it in store.hiddenSessionIds || trash.isHidden(it) }, ::authorizedTransfer)
    private val locations = SessionLocations(backend::request, { backend.apiVersion },
        { Triple(backend.requestGeneration(), backend.url(), store.sessionHistoryScope) }, ::workspaceRoots)
    private fun authorizedTransfer(id: String, directory: String): Boolean = transfers?.get(id)?.let {
        WorkspacePaths.isSame(it.str("directory"), directory) && it.obj("transfer").bool("available") == true
    } == true
    /** A retained conversation's destination, or a subagent inheriting it, learning missing ancestry on demand. */
    private fun authorizedGrant(id: String, directory: String): Boolean = authorizedTransfer(id, directory) ||
        grants?.let { it.isGranted(id, directory) || (it.isGrantedDirectory(directory) &&
            it.resolve(listOf(id), directory) == SessionGrants.Resolution.GRANTED) } == true

    /** The directory that scopes a session request: its retained destination or inherited grant. */
    private fun sessionScope(id: String): String? = transfers?.get(id).str("directory") ?: grants?.grant(id)?.directory
        ?: catalogDirectory(id)

    /** Where a pending prompt lives when it belongs to a retained, inherited or catalog session outside the open roots. */
    private fun attentionScope(sessionId: String): String? = grants?.grant(sessionId)?.directory ?: catalogDirectory(sessionId)

    /** Request threads may block, so a `project` catalog loads its roots' identities before deciding. */
    private fun catalogDirectory(id: String): String? = catalog?.let { it.loadProjects(); it.ownedDirectory(id) }

    private val access: EventAccess? get() = when {
        grants != null && catalog != null -> EventAccesses(grants, catalog)
        else -> grants ?: catalog
    }

    private val requestDirectories = RequestDirectories(::workspaceRoots, { backend.observedSessionDirectories() },
        ::authorizedGrant, authorizeSession = { session, directory ->
            val options = RequestOptions(unscoped = true, timeoutMs = 15_000, maxResponseBytes = 2 * 1024 * 1024)
            FileReferences.authorizeDirectory(session, directory, workspaceRoots(), store.sessionHistoryScope,
                { backend.request("GET", "/session/${encode(session)}", options = options).data.asObjectOrNull() },
                { root -> SessionCatalog.currentProject({ path, directory -> backend.request("GET", path, options = options.copy(unscoped = false, directory = directory)).data }, root) })
        }, cacheKey = { "${backend.requestGeneration()}|${backend.url()}|${store.sessionHistoryScope}" })
    private val childTasks = ChildTasks(backend::request, { backend.apiVersion },
        { backend.requestGeneration() to backend.url() },
        { (listOfNotNull(workspace.basePath) + workspace.folders()).distinct() },
        { it in store.hiddenSessionIds }, ::authorizedTransfer)
    private val settingsSubscription = com.intellij.openapi.application.ApplicationManager.getApplication()?.messageBus?.connect()?.apply {
        subscribe(varro.settings.VarroSettings.TOPIC, varro.settings.VarroSettings.Listener {
            if (settings.remoteImageMode() != "on" && remoteImagesDelegate.isInitialized()) remoteImages.cancel()
        })
    }
    private val contentReader by lazy { MessageContentReader(service<ImageThumbnails>()) { path, options ->
        backend.request("GET", path, options = options).data
    } }

    /** In-flight requests, keyed by the webview's cancel key. */
    private val activeRequests = RequestTasks()
    private val disposed = AtomicBoolean(false)
    private val trash = SessionTrash(store, request = { method, path, body, directory ->
        backend.request(method, path, body, RequestOptions(directory = directory)).data
    }, journal = varro.store.JsonJournal(workspace.trashJournal()))

    /**
     * Services the proxy needs from the rest of the host. Kept as an interface so
     * the routing logic stays testable without a live project.
     */
    interface HostServices {
        fun followups(sessionId: String, body: JsonElement?, directory: String?, dismiss: Boolean, isCancelled: () -> Boolean): JsonElement
        fun linkFollowupSession(sessionId: String, body: JsonElement?, directory: String?, isCancelled: () -> Boolean): JsonElement = error("Follow-up links unavailable")
        fun clearFollowups(sessionId: String, directory: String?, messageId: String?)
        fun promptCompletionModels(isCancelled: () -> Boolean): JsonObject
        fun completePrompt(body: JsonElement?, directory: String?, isCancelled: () -> Boolean): JsonObject
        fun testPromptCompletion(body: JsonElement?, isCancelled: () -> Boolean): JsonObject
        fun openPlanDocument(content: String, title: String?): String?
        fun pickWorkspaceFile(): JsonObject?
        fun readWorkspaceFile(path: String): JsonObject?
        fun resolveWorkspacePath(path: String): JsonObject?
        fun readOpenCodeConfig(): JsonObject
        fun updateModelRouting(body: JsonElement?): JsonObject
        fun mcpDefaults(method: String, body: JsonElement?): JsonObject
        fun mcpConfig(body: JsonElement?, open: Boolean): JsonElement
        fun disableProvider(body: JsonElement?): JsonElement
        fun readOpenCodePermissions(): JsonObject
        fun updateOpenCodePermissions(body: JsonElement?): JsonObject
        fun globalPermissions(method: String, body: JsonElement?): JsonObject
        fun judgePermission(body: JsonElement?): JsonObject
        fun judgeModel(providerId: String?, modelId: String?, variant: String?): JsonObject
        fun decisionProviders(method: String, body: JsonElement?): JsonObject
        fun permissionRules(sessionId: String, rules: JsonElement?, directory: String?): JsonArray
        fun allowPermission(body: JsonObject, project: Boolean, directory: String?): JsonArray
        fun allowGlobalPermission(body: JsonObject, directory: String?): JsonArray
        fun providerLimit(providerId: String, modelId: String?): JsonObject
        fun sessionDiffSummary(sessionId: String, directory: String?, revision: String?): JsonObject
        fun openReferencedSession(session: JsonObject) { error("Session opening is unavailable") }
    }

    fun submitRequest(payload: JsonObject, executor: java.util.concurrent.Executor) {
        val id = payload.int("id") ?: return
        val method = payload.str("method")?.uppercase() ?: return
        val path = payload.str("path") ?: return
        if (!ApiRoutes.isAllowed(method, path)) {
            respond(id, error = "Unsupported API request")
            return
        }
        try {
            activeRequests.submit(payload.text("cancelKey"), executor) { ticket -> handleRequest(payload, ticket) }
        } catch (failure: Exception) {
            respond(id, error = failure.message ?: "Request could not be scheduled")
        }
    }

    fun handleRequest(payload: JsonObject) = submitRequest(payload, java.util.concurrent.Executor { it.run() })

    private fun handleRequest(payload: JsonObject, ticket: RequestTasks.Ticket) {
        val id = payload.int("id") ?: return
        val method = payload.str("method")?.uppercase() ?: return
        val path = payload.str("path") ?: return
        val cancelled = { ticket.isCancelled() || disposed.get() }

        if (disposed.get()) {
            respond(id, error = "REST proxy disposed")
            return
        }

        // The allowlist runs before anything else touches the request.
        if (!ApiRoutes.isAllowed(method, path)) {
            respond(id, error = "Unsupported API request")
            return
        }

        var admitted = false
        var success = false
        var rejected = false
        try {
            if (cancelled()) return
            fun checkPermissionLease() = PermissionAutomationLease.assertCurrent(payload, isPermissionAutomationLeaseCurrent)
            checkPermissionLease()
            if (skipNativeRecovery(method, path, payload.bool("interruptedRecovery") == true, backend.apiVersion)) {
                log.info("interrupted-recovery native-owned, generation=${backend.requestGeneration()}")
                respond(id, data = Json.obj("skipped" to true))
                return
            }
            val referenceId = ApiRoutes.varroSessionId(path)?.takeIf {
                ApiRoutes.parse(method, path)?.segments?.lastOrNull() == "reference"
            }
            if (referenceId != null) {
                val reference = locations.reference(referenceId, { referenceId in store.hiddenSessionIds || trash.isHidden(referenceId) },
                    { directory -> transfers?.list(includeHidden = true)?.any {
                        it.str("id") == referenceId && WorkspacePaths.isSame(it.str("directory"), directory) &&
                            it.obj("transfer").bool("available") == true
                    } == true }, cancelled)
                if (method == "POST") services.openReferencedSession(reference)
                if (!cancelled()) respond(id, data = if (method == "GET") reference else Json.toElement(true))
                return
            }
            val transferDirectory = transfers?.authorizeRequest(method, path, payload.get("body"))
            if (transferDirectory == null) authorizeRequestedDirectory(method, path, payload.get("body"))
            val forwardedPath = if (transferDirectory == null) path else {
                val request = ApiRoutes.parse(method, path)!!
                val query = request.query.filterKeys { it != "directory" }.flatMap { (key, values) -> values.map { "${encode(key)}=${encode(it)}" } } +
                    "directory=${encode(transferDirectory)}"
                "${request.pathname}?${query.joinToString("&")}"
            }
            if (payload.obj("queuedMessageDispatch") != null && !admitQueuedDispatch(payload)) {
                error("Queued message dispatch lease is no longer current")
            }
            admitted = payload.obj("queuedMessageDispatch") != null && method == "POST"
            val data = if (path.startsWith(ApiRoutes.NAMESPACE)) {
                handleVarroRequest(method, forwardedPath, payload.get("body"), transferDirectory, cancelled)
            } else {
                forward(method, forwardedPath, payload.get("body"), payload.obj("queuedMessageDispatch") != null, transferDirectory) {
                    checkPermissionLease()
                    cancelled()
                }
            }
            success = true
            if (admitted) completeQueuedDispatch(payload, true, false)
            if (!cancelled()) respond(id, data = data)
        } catch (failure: Exception) {
            rejected = Regex("^(400|401|403|404|405|413|415|422|429) ").containsMatchIn(failure.message.orEmpty())
            log.warn("api/request failed: $method ${path.substringBefore('?')}: ${failure.message}")
            if (!cancelled()) {
                respond(id, error = failure.message ?: "Request failed")
            }
        } finally {
            if (admitted && !success) completeQueuedDispatch(payload, false, rejected)
        }
    }

    /**
     * Checks the page-supplied `directory` before it can rescope OpenCode. Retained
     * transfers were already authorized by [SessionTransfers.authorizeRequest].
     */
    private fun authorizeRequestedDirectory(method: String, path: String, body: JsonElement?) {
        val request = ApiRoutes.parse(method, path) ?: return
        // History scope only reads `.git` presence for its answer; it never rescopes.
        if (request.pathname == ApiRoutes.Endpoints.SESSION_HISTORY_SCOPE) return
        val varroSession = ApiRoutes.varroSessionId(path)
        val sessionId = varroSession ?: request.segments.takeIf { it.size >= 2 && it[0] == "session" }?.get(1)?.takeIf { it != "status" }
        request.query["directory"]?.firstOrNull()?.let { requestDirectories.require(sessionId, it) }
        if (varroSession != null && request.segments.lastOrNull() == "activate") {
            body.asObjectOrNull().text("directory")?.let { requestDirectories.require(varroSession, it) }
        }
        if (sessionId != null && request.pathname.matches(Regex("^/session/[^/]+/message$"))) {
            body.asObjectOrNull().str("workspaceDirectory")?.let { requestDirectories.require(sessionId, it) }
        }
    }

    /**
     * Cancellation stops subsequent adapter requests and suppresses late responses.
     */
    fun cancelRequest(payload: JsonObject) {
        val cancelKey = payload.text("cancelKey") ?: return
        activeRequests.cancel(cancelKey)
    }

    fun dispose() {
        disposed.set(true)
        activeRequests.close()
        if (remoteImagesDelegate.isInitialized()) remoteImages.close()
        if (localImagesDelegate.isInitialized()) localImages.close()
        settingsSubscription?.disconnect()
    }

    private fun respond(id: Int, data: JsonElement? = null, error: String? = null) {
        val payload = JsonObject().apply {
            addProperty("id", id)
            if (error != null) addProperty("error", error) else add("data", data ?: JsonNull.INSTANCE)
        }
        postResponse(Json.message("api/response", payload).asJsonObject)
    }

    // --- OpenCode forwarding --------------------------------------------------

    private fun forward(method: String, path: String, body: JsonElement?, queuedDispatch: Boolean = false, transferDirectory: String? = null, isCancelled: () -> Boolean = { false }): JsonElement? {
        return try { forwardRequest(method, path, body, queuedDispatch, transferDirectory, isCancelled) }
        catch (failure: Exception) {
            val directory = transferDirectory ?: ApiRoutes.parse(method, path)?.query?.get("directory")?.firstOrNull() ?: backend.workspaceDirectory().orEmpty()
            WorkspaceDirectoryErrors.recover(method, path, directory, failure)
        }
    }

    private fun forwardRequest(method: String, path: String, body: JsonElement?, queuedDispatch: Boolean, transferDirectory: String?, isCancelled: () -> Boolean): JsonElement? {
        val request = ApiRoutes.parse(method, path)
        require(!(method == "POST" && request?.segments?.firstOrNull() == "question" &&
            body.asObjectOrNull().str("kind") == "native-form" && backend.apiVersion != 2)) { "Native forms require OpenCode V2" }
        val sessionId = request?.segments?.takeIf { it.size >= 2 && it[0] == "session" }?.get(1)
        if (request?.segments?.size == 3 && request.segments[0] == "session" &&
            request.segments[2] in setOf("child-task-controls", "interrupt-child", "background-parent")) {
            return childTasks.request(method, path, body, transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: backend.workspaceDirectory(), isCancelled)
        }
        if (sessionId != null && sessionId != "status" && method != "GET") {
            transfers?.checkWritable(sessionId)
            // A subagent of a moved conversation is writable only while that conversation is.
            grants?.linkedOwner(sessionId)?.takeIf { it != sessionId }?.let { owner -> transfers?.checkWritable(owner) }
            if (isCancelled()) throw java.util.concurrent.CancellationException("OpenCode request cancelled")
        }
        if (method == "GET" && request?.query?.containsKey("directory") == false &&
            (request.pathname == "/permission" || request.pathname == "/question")) {
            return pendingSnapshot(path, isCancelled)
        }
        val reply = request?.segments?.takeIf { it.size == 3 && it[0] in setOf("permission", "question") && it[2] in setOf("reply", "reject") }
        if (method == "POST" && reply != null && !request.query.containsKey("directory")) {
            // A prompt from a retained conversation or its subagent is answered in its own location,
            // where the adapter can find it again after a reconnect.
            backend.pendingAttentionSessionId(reply[1])?.let(::attentionScope)?.let { directory ->
                return backend.request(method, path, body, RequestOptions(directory = directory, isCancelled = isCancelled)).data
            }
        }

        if (method == "POST" && request?.segments?.size == 3 && request.segments[2] == "side-question" && sessionId != null) {
            val directory = transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: sessionScope(sessionId) ?: backend.workspaceDirectory()
            return sideQuestions.ask(path, body, directory, isCancelled)
        }

        if (method == "GET" && request != null && request.segments.size == 6 &&
            request.segments[0] == "session" && request.segments[2] == "message" && request.segments[4] == "part") {
            return readContent(request, transferDirectory, isCancelled)
        }

        if (method == "GET" && request != null && Regex("^/session/[^/]+/message$").matches(request.pathname)) {
            val directory = transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: sessionScope(request.segments[1])
                ?: body.asObjectOrNull().str("workspaceDirectory") ?: backend.workspaceDirectory()
            val response = backend.request(method, path, options = RequestOptions(
                directory = directory, captureNextCursor = true, isCancelled = isCancelled,
                maxResponseBytes = 256L * 1024 * 1024,
            ))
            val messages = MessageContent.messages(response.data, directory)
            return if (request.query.containsKey("limit")) Json.obj("items" to messages).apply {
                response.nextCursor?.let { addProperty("nextCursor", it) }
            } else messages
        }

        // `GET /session?limit=` is a paginated read in the client's contract even
        // though OpenCode answers with a plain array, so the host builds the page.
        if (method == "GET" && request?.pathname == "/session" && request.query.containsKey("limit")) {
            return sessionPage(path, request, isCancelled)
        }
        if (method == "GET" && request?.pathname == "/session/status") {
            return transferredStatuses(isCancelled)
        }

        val outgoing = if (method == "POST" && sessionId != null && request.segments.size == 3 && request.segments[2] in setOf("prompt_async", "message", "command"))
            CitationPaths.withGuidance(body, transferDirectory ?: request.query["directory"]?.firstOrNull() ?: backend.workspaceDirectory()) else body
        val result = backend.request(method = method, path = path, body = outgoing, options = RequestOptions(isCancelled = isCancelled,
            directory = sessionId?.takeIf { it != "status" }?.let(::sessionScope))).data
        if (method == "POST" && sessionId != null && request.segments.size == 3 && request.segments[2] in setOf("prompt_async", "message", "command")) {
            runCatching { services.clearFollowups(sessionId, transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: backend.workspaceDirectory(), body.asObjectOrNull().str("messageID")) }
                .onFailure { log.warn("Could not clear previous-turn follow-up cache") }
        }
        if (method == "GET" && request?.pathname == "/session") return locations.annotate(Json.array(
            mergeTransfers(result.asArrayOrNull() ?: JsonArray(), request).filter { it.asObjectOrNull().str("id") !in store.hiddenSessionIds },
        ), isCancelled)
        if (method == "GET" && sessionId != null && request.segments.size == 2) {
            result.asObjectOrNull()?.let { session ->
                transfers?.get(sessionId).obj("transfer")?.let { session.add("transfer", it) }
            }
        }
        return result
    }

    private fun readContent(request: ApiRoutes.Request, transferDirectory: String?, isCancelled: () -> Boolean): JsonElement {
        val sessionID = request.segments[1]
        require(sessionID !in store.hiddenSessionIds) { "404 Session not found" }
        val directory = transferDirectory ?: request.query["directory"]?.firstOrNull() ?: sessionScope(sessionID) ?: backend.workspaceDirectory()
        return contentReader.read(request, directory, backend.url(), isCancelled)
    }

    /**
     * Projects OpenCode's session array into the `SessionListPage` the client
     * expects: `{ items, hasMore }`.
     *
     * This is the host's job upstream too. OpenCode has no notion of Varro's
     * recycle bin or hidden helper sessions, so they are filtered out here before
     * the page is cut — otherwise a page could be filled entirely with sessions
     * the user cannot see, and look empty.
     */
    private fun sessionPage(path: String, request: ApiRoutes.Request, isCancelled: () -> Boolean): JsonElement {
        val limit = request.query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 1_000) ?: 100
        val recycled = store.recycleBin.flatMap {
            it.asObjectOrNull()?.getAsJsonArray("sessions")?.mapNotNull { session -> session.asObjectOrNull().str("id") }.orEmpty()
        }.toSet()
        val hidden = store.hiddenSessionIds
        fun isVisible(entry: JsonElement): Boolean {
            val id = entry.asObjectOrNull().str("id") ?: return false
            return id !in recycled && id !in hidden
        }
        val scoped = scopedCatalogRows(request, limit, ::isVisible, isCancelled)
        val (listed, more) = scoped?.let { it.rows to it.more } ?: run {
            val response = backend.request("GET", path, options = RequestOptions(captureNextCursor = true, isCancelled = isCancelled))
            (response.data.asArrayOrNull() ?: JsonArray()) to (response.nextCursor != null)
        }
        val sessions = mergeTransfers(listed, request)

        val visible = sessions.filter(::isVisible)

        // Newest first, matching how the client renders the list.
        val ordered = visible.sortedByDescending { entry ->
            entry.asObjectOrNull().obj("time").num("updated")
                ?: entry.asObjectOrNull().obj("time").num("created")
                ?: 0.0
        }

        val items = locations.annotate(JsonArray().apply { ordered.take(limit).forEach(::add) }, isCancelled)
        return Json.obj("items" to items, "hasMore" to (ordered.size > limit || more)).apply {
            if (scoped?.incomplete == true) addProperty("incomplete", true)
        }
    }

    /** A scoped catalog read: admitted rows, whether more exist, and whether the read stopped at its bound. */
    private class ScopedRows(val rows: JsonArray, val more: Boolean, val incomplete: Boolean)

    /**
     * The `descendants` or `project` catalog on V2. OpenCode filters `/api/session` by exact
     * directory, so the scoped catalog pages through the server-wide list, newest-updated first as
     * OpenCode orders it, and keeps the open roots' sessions plus those this window's catalog owns.
     * Other windows' sessions, and those a more specific open window owns, are left out. Paging
     * stops once one more visible session than requested is admitted, or the list ends. A list
     * longer than [SCOPED_CATALOG_PAGES] pages reports `incomplete` rather than reading on.
     * OpenCode's `project` filter is not used: both scopes also admit nested folders by
     * containment, including independent nested repositories with their own project.
     * `null` keeps the exact-directory listing.
     */
    private fun scopedCatalogRows(request: ApiRoutes.Request, limit: Int, isVisible: (JsonElement) -> Boolean,
        isCancelled: () -> Boolean): ScopedRows? {
        val catalog = catalog?.takeIf { backend.apiVersion == 2 && it.isScoped() && !request.query.containsKey("directory") } ?: return null
        catalog.loadProjects()
        val first = listOfNotNull(
            "limit=$scopedCatalogPage",
            request.query["search"]?.firstOrNull()?.let { "search=${encode(it)}" },
            request.query["roots"]?.firstOrNull()?.takeIf { it == "true" }?.let { "roots=true" },
        ).joinToString("&")
        val roots = workspaceRoots()
        val admitted = JsonArray()
        val seen = HashSet<String>()
        var visible = 0
        var query = first
        for (page in 1..SCOPED_CATALOG_PAGES) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("OpenCode request cancelled")
            val response = backend.request("GET", "/experimental/session?$query", options = RequestOptions(unscoped = true,
                captureNextCursor = true, isCancelled = isCancelled))
            val rows = response.data.asArrayOrNull() ?: error("Malformed session list")
            rows.forEach { value ->
                val session = value.asObjectOrNull() ?: return@forEach
                val id = session.str("id") ?: return@forEach
                // A session updated while paging can appear on two pages.
                if (!seen.add(id)) return@forEach
                catalog.observe(session)
                grants?.observe(session)
                val directory = session.text("directory") ?: return@forEach
                if (roots.any { WorkspacePaths.isSame(it, directory) } || catalog.admitsListed(session)) {
                    admitted.add(session)
                    if (isVisible(session)) visible++
                }
            }
            if (visible > limit) return ScopedRows(admitted, more = true, incomplete = false)
            // The cursor carries the first page's filters; an empty page or no cursor ends the list.
            val next = response.nextCursor?.takeIf { rows.size() > 0 } ?: return ScopedRows(admitted, more = false, incomplete = false)
            query = "limit=$scopedCatalogPage&cursor=${encode(next)}"
        }
        return ScopedRows(admitted, more = true, incomplete = true)
    }

    private fun mergeTransfers(sessions: JsonArray, request: ApiRoutes.Request): JsonArray =
        transfers?.merge(sessions, request) ?: sessions

    /** A missing folder-scoped status must not erase a retained conversation's activity. */
    private fun transferredStatuses(isCancelled: () -> Boolean): JsonObject {
        val current = backend.workspaceDirectory()
        val result = backend.request("GET", "/session/status", options = RequestOptions(directory = current,
            isCancelled = isCancelled)).data.asObjectOrNull() ?: error("Malformed session status")
        return transfers?.statuses(current, result, store.hiddenSessionIds) { directory ->
            backend.request("GET", "/session/status", options = RequestOptions(directory = directory,
                isCancelled = isCancelled)).data
        } ?: result
    }

    /**
     * The workspace's pending permissions or questions, plus those of conversations this project
     * retains elsewhere and the subagents inheriting their grants. OpenCode scopes pending requests
     * to their location, so each verified destination is queried. Requests there from unrelated
     * sessions are omitted; an inconclusive ancestry rejects the snapshot, because the webview
     * treats an omission as resolution.
     */
    private fun pendingSnapshot(path: String, isCancelled: () -> Boolean): JsonElement? {
        val base = backend.request("GET", path, options = RequestOptions(isCancelled = isCancelled)).data
        val access = access ?: return base
        val current = backend.workspaceDirectory()
        catalog?.loadProjects()
        val destinations = ((grants?.verifiedDestinations() ?: emptyList()) +
            promptDirectories(access, isCancelled) + (catalog?.ownedDirectories() ?: emptyList()))
            .filterNot { WorkspacePaths.isSame(it, current) }
            .distinctBy(WorkspacePaths::normalizeIdentity)
        if (destinations.isEmpty()) return base
        val kind = path.removePrefix("/")
        val result = JsonArray()
        val seen = HashSet<String>()
        fun add(value: JsonElement) {
            val id = PendingRequests.id(value)
            if (id == null || seen.add(id)) result.add(value)
        }
        (base.asArrayOrNull() ?: error("Malformed pending $kind response")).forEach(::add)
        for (directory in destinations) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("OpenCode request cancelled")
            val listed = backend.request("GET", path, options = RequestOptions(directory = directory, isCancelled = isCancelled))
                .data.asArrayOrNull() ?: error("Malformed pending $kind response")
            PendingRequests.granted(listed, directory, access) { it in store.hiddenSessionIds || trash.isHidden(it) }.forEach(::add)
        }
        return result
    }

    /**
     * The folders of sessions that can hold a pending prompt now, so a prompt asked before a reload
     * is recovered however long ago its folder was last seen. OpenCode lists pending requests only
     * per location and has no server-wide list, but a prompt blocks its session, so it is among the
     * server-wide active sessions on V2; prompts this host already saw are included too. Only
     * sessions this window owns or is granted count. Their locations come from known records or one
     * canonical read each; an unreadable candidate rejects the snapshot rather than dropping its prompt.
     */
    private fun promptDirectories(access: EventAccess, isCancelled: () -> Boolean): List<String> {
        val active = if (backend.apiVersion == 2) {
            backend.request("GET", "/api/session/active", options = RequestOptions(unscoped = true, isCancelled = isCancelled))
                .data.asObjectOrNull()?.obj("data")?.keySet().orEmpty()
        } else emptySet()
        val candidates = (backend.pendingAttentionSessionIds() + active).distinct()
            .filter { it !in store.hiddenSessionIds && !trash.isHidden(it) }
        val observed = backend.observedSessionDirectories()
        var reads = 0
        return candidates.mapNotNull { id ->
            if (isCancelled()) throw java.util.concurrent.CancellationException("OpenCode request cancelled")
            val directory = observed[id] ?: catalog?.knownDirectory(id) ?: run {
                check(reads++ < MAX_PROMPT_SESSION_READS) { "Could not resolve pending request session ancestry" }
                val record = backend.request("GET", "/session/${encode(id)}", options = RequestOptions(unscoped = true,
                    timeoutMs = 5_000, maxResponseBytes = 2 * 1024 * 1024, isCancelled = isCancelled)).data.asObjectOrNull()
                check(record.str("id") == id) { "Could not resolve pending request session ancestry" }
                catalog?.observe(record)
                grants?.observe(record)
                record.text("directory") ?: error("Could not resolve pending request session ancestry")
            }
            when {
                access.isGranted(id, directory) -> directory
                else -> when (access.resolve(listOf(id), directory)) {
                    SessionGrants.Resolution.GRANTED -> directory
                    SessionGrants.Resolution.UNRELATED -> null
                    SessionGrants.Resolution.UNKNOWN -> error("Could not resolve pending request session ancestry")
                }
            }
        }
    }

    // --- Host API namespace ---------------------------------------------------

    private fun handleVarroRequest(method: String, path: String, body: JsonElement?, transferDirectory: String?, isCancelled: () -> Boolean): JsonElement? {
        val request = ApiRoutes.parse(method, path) ?: throw IllegalArgumentException("Bad request")
        val pathname = request.pathname
        val query = request.query

        fun q(key: String): String? = query[key]?.firstOrNull()?.takeIf { it.isNotBlank() }

        return when {
            pathname == ApiRoutes.Endpoints.CITATION_PATHS || pathname == ApiRoutes.Endpoints.CITATION_SYMBOLS -> {
                val authorize: (String?, String) -> Boolean = { session, directory ->
                val roots = (listOfNotNull(workspace.basePath) + workspace.folders()).distinct()
                if (session == null) roots.any { WorkspacePaths.isSame(it, directory) }
                else {
                    require(session !in store.hiddenSessionIds) { "Session not found" }
                    val options = RequestOptions(unscoped = true, timeoutMs = 15_000, maxResponseBytes = 2 * 1024 * 1024,
                        cancellableBody = true, isCancelled = isCancelled)
                    authorizedTransfer(session, directory) || FileReferences.authorizeDirectory(session, directory, roots, store.sessionHistoryScope,
                        { backend.request("GET", "/session/${encode(session)}", options = options).data.asObjectOrNull() },
                        { root -> SessionCatalog.currentProject({ path, directory -> backend.request("GET", path, options = options.copy(unscoped = false, directory = directory)).data }, root) })
                }
                }
                if (pathname == ApiRoutes.Endpoints.CITATION_SYMBOLS) workspace.citationSymbols(body, backend.workspaceDirectory(), authorize, isCancelled)
                else citationPaths.validate(body, backend.workspaceDirectory(), authorize, isCancelled)
            }
            pathname == ApiRoutes.Endpoints.LOCAL_IMAGE -> {
                require(!backend.isAttachOnly()) { "Local images are unavailable for an attached server" }
                val roots = workspaceRoots()
                val options = RequestOptions(unscoped = true, timeoutMs = 5_000, maxResponseBytes = 2 * 1024 * 1024,
                    cancellableBody = true, isCancelled = isCancelled)
                localImages.load(body, backend.workspaceDirectory(), roots, { session, directory ->
                    if (session == null) roots.any { WorkspacePaths.isSame(it, directory) }
                    else session !in store.hiddenSessionIds && (authorizedTransfer(session, directory) ||
                        FileReferences.authorizeDirectory(session, directory, roots, store.sessionHistoryScope,
                            { backend.request("GET", "/session/${encode(session)}", options = options).data.asObjectOrNull() },
                            { root -> SessionCatalog.currentProject({ path, directory -> backend.request("GET", path, options = options.copy(unscoped = false, directory = directory)).data }, root) }))
                }, isCancelled)
            }
            pathname == ApiRoutes.Endpoints.REMOTE_IMAGE -> {
                val record = body.asObjectOrNull()
                val automatic = record.bool("automatic") == true
                RemoteImages.checkMode(settings.remoteImageMode(), automatic)
                remoteImages.load(record.str("url") ?: error("Missing remote image URL")) {
                    isCancelled() || settings.remoteImageMode() == "off" || (automatic && settings.remoteImageMode() != "on")
                }.also { RemoteImages.checkMode(settings.remoteImageMode(), automatic) }
            }
            pathname == ApiRoutes.Endpoints.PROMPT_COMPLETION_MODELS -> services.promptCompletionModels(isCancelled)
            pathname == ApiRoutes.Endpoints.PROMPT_COMPLETION -> services.completePrompt(body, q("directory"), isCancelled)
            pathname == ApiRoutes.Endpoints.PROMPT_COMPLETION_TEST -> services.testPromptCompletion(body, isCancelled)
            pathname == ApiRoutes.Endpoints.MODEL_PRICING ->
                modelPricing.get(q("providerID")!!, q("modelID")!!) ?: JsonNull.INSTANCE

            pathname == ApiRoutes.Endpoints.WORKSPACE_PROBLEMS -> workspace.problems()

            pathname == ApiRoutes.Endpoints.COPIED_SELECTION_MATCH -> {
                val record = body.asObjectOrNull()
                val text = record.str("text")
                require(text != null && text.isNotBlank() && text.length <= 256 * 1024) { "Invalid copied selection text" }
                workspace.matchCopiedSelection(text, record.bool("plainTextOnly") == true)
            }

            pathname == ApiRoutes.Endpoints.PROVIDER_LIMIT ->
                services.providerLimit(q("providerID")!!, q("modelID"))

            pathname == ApiRoutes.Endpoints.STARTUP_DIAGNOSTICS -> {
                val directory = q("directory") ?: backend.workspaceDirectory()
                val roots = listOfNotNull(workspace.basePath, backend.workspaceDirectory()) +
                    workspace.folders()
                require(directory == null || roots.any { WorkspacePaths.isSame(it, directory) }) { "Workspace directory is not an open project folder" }
                StartupDiagnostics({ backend.apiVersion }, backend::request).read(directory, isCancelled)
            }

            // The client types this as `string | null`: the file's text, not a
            // descriptor around it.
            pathname == ApiRoutes.Endpoints.WORKSPACE_FILE ->
                services.readWorkspaceFile(q("path")!!)?.get("content") ?: JsonNull.INSTANCE

            pathname == ApiRoutes.Endpoints.WORKSPACE_FILE_PICK ->
                services.pickWorkspaceFile() ?: JsonNull.INSTANCE

            pathname == ApiRoutes.Endpoints.WORKSPACE_PATH_RESOLVE ->
                services.resolveWorkspacePath(q("path")!!) ?: JsonNull.INSTANCE

            pathname == ApiRoutes.Endpoints.PLAN_OPEN -> {
                val record = body.asObjectOrNull()
                val content = record.str("content") ?: record.str("plan") ?: ""
                val opened = services.openPlanDocument(content, record.text("title"))
                Json.obj("path" to opened, "opened" to (opened != null))
            }

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG -> services.readOpenCodeConfig()

            pathname == ApiRoutes.Endpoints.MCP_DEFAULTS -> services.mcpDefaults(method, body)
            pathname == ApiRoutes.Endpoints.MCP_CONFIG -> services.mcpConfig(body, false)
            pathname == ApiRoutes.Endpoints.MCP_CONFIG_OPEN -> services.mcpConfig(body, true)

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_MODEL_ROUTING ->
                services.updateModelRouting(body)

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_DISABLE_PROVIDER ->
                services.disableProvider(body)

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_PERMISSIONS ->
                if (method == "GET") services.readOpenCodePermissions()
                else services.updateOpenCodePermissions(body)

            pathname == ApiRoutes.Endpoints.OPENCODE_GLOBAL_PERMISSIONS -> services.globalPermissions(method, body)

            pathname == ApiRoutes.Endpoints.PERMISSION_JUDGE -> services.judgePermission(body)

            pathname == ApiRoutes.Endpoints.DECISION_PROVIDERS -> services.decisionProviders(method, body)

            // `ChatModelSelection | null`, unwrapped.
            pathname == ApiRoutes.Endpoints.PERMISSION_JUDGE_MODEL ->
                services.judgeModel(q("providerID"), q("modelID"), q("variant"))
                    .get("model") ?: JsonNull.INSTANCE

            pathname == ApiRoutes.Endpoints.SESSION_HISTORY_SCOPE ->
                handleHistoryScope(method, body, q("directory"))

            pathname == ApiRoutes.Endpoints.SESSION_TRANSFER_HIDE -> {
                val sessionId = body.asObjectOrNull().str("sessionID") ?: error("Invalid session ID")
                check(transfers?.hide(sessionId, q("directory")) == true) { "404 Transferred session not found" }
                Json.toElement(true)
            }

            pathname == ApiRoutes.Endpoints.SESSION_TRASH -> handleTrashCollection(method)

            pathname.startsWith("${ApiRoutes.Endpoints.SESSION_TRASH}/") ->
                handleTrashEntry(method, request)

            pathname.startsWith("${ApiRoutes.Endpoints.SESSION}/") ->
                handleSessionEndpoint(method, request, body, transferDirectory ?: q("directory"), isCancelled)

            // Server process memory is distinct from persisted session/project rules.
            pathname == ApiRoutes.Endpoints.PERMISSION_SERVER_MEMORY -> backend.savedPermissions(
                method, q("sessionId") ?: body.asObjectOrNull().text("sessionId"), body,
                RequestOptions(directory = q("directory"), isCancelled = isCancelled),
            )

            pathname == ApiRoutes.Endpoints.PERMISSION_SESSION_RULES -> services.permissionRules(
                q("sessionId") ?: body.asObjectOrNull().text("sessionId") ?: error("Missing session id"),
                if (method == "POST") body.asObjectOrNull()?.get("rules") ?: error("Missing rules") else null,
                q("directory"),
            )
            pathname == ApiRoutes.Endpoints.PERMISSION_SESSION_ALLOW ||
                pathname == ApiRoutes.Endpoints.PERMISSION_PROJECT_ALLOW -> services.allowPermission(
                    body.asObjectOrNull() ?: error("Missing permission request"),
                    pathname == ApiRoutes.Endpoints.PERMISSION_PROJECT_ALLOW, q("directory"),
                )

            pathname == ApiRoutes.Endpoints.PERMISSION_GLOBAL_ALLOW -> services.allowGlobalPermission(
                body.asObjectOrNull() ?: error("Missing permission request"), q("directory"),
            )

            else -> throw IllegalArgumentException("Unsupported Varro API request: $pathname")
        }
    }

    /**
     * Session-history scope, plus whether the directory is a Git working tree.
     *
     * The client validates both fields and throws `Malformed response` when
     * either is missing, so `git` is not optional. It gates the project-wide
     * scope option, which only makes sense inside a repository.
     */
    private fun handleHistoryScope(
        method: String,
        body: JsonElement?,
        directory: String?,
    ): JsonElement {
        if (method == "POST") {
            body.asObjectOrNull().str("scope")?.let {
                store.sessionHistoryScope = it
                catalog?.invalidate()
            }
        }
        return Json.obj(
            "scope" to store.sessionHistoryScope,
            "git" to isGitWorkingTree(directory),
        )
    }

    /** Walks up from [directory] looking for a `.git` entry, as Git itself does. */
    private fun isGitWorkingTree(directory: String?): Boolean {
        val start = directory?.takeIf { it.isNotBlank() }
            ?: workspace.basePath
            ?: return false
        var current: java.nio.file.Path? = runCatching { java.nio.file.Paths.get(start) }.getOrNull()
        while (current != null) {
            // `.git` is a directory in a normal clone and a file in a worktree or
            // submodule, so existence is the test rather than being a directory.
            if (java.nio.file.Files.exists(current.resolve(".git"))) return true
            current = current.parent
        }
        return false
    }

    // --- Session endpoints ----------------------------------------------------

    private fun handleSessionEndpoint(
        method: String,
        request: ApiRoutes.Request,
        body: JsonElement?,
        directory: String?,
        isCancelled: () -> Boolean,
    ): JsonElement {
        val sessionId = ApiRoutes.varroSessionId(request.pathname)
            ?: throw IllegalArgumentException("Malformed session endpoint")
        val action = request.segments.last()
        if (action in setOf("permission-mode", "rename-if-untitled", "delete")) transfers?.checkWritable(sessionId)

        return when (action) {
            "location" -> locations.location(sessionId, directory, transfers?.get(sessionId), store.sessionHistoryScope,
                sessionId in store.hiddenSessionIds, isCancelled)
            "session" -> {
                require(sessionId !in store.hiddenSessionIds) { "404 Session not found" }
                val linked = body.asObjectOrNull().obj("action").str("sessionId")
                require(linked != null && linked != sessionId && linked !in store.hiddenSessionIds && Regex("[A-Za-z0-9_-]{1,200}").matches(linked)) { "Invalid follow-up session link" }
                services.linkFollowupSession(sessionId, body, directory, isCancelled)
            }
            "followups", "dismiss" -> {
                require(sessionId !in store.hiddenSessionIds) { "404 Session not found" }
                services.followups(sessionId, body, directory, action == "dismiss", isCancelled)
            }
            "activate" -> {
                // Activating a session repoints REST scoping and the event stream at
                // the directory that session actually belongs to, so subsequent
                // message and diff reads resolve against the right workspace.
                val target = directory ?: body.asObjectOrNull().text("directory")
                    ?: backend.observedSessionDirectories()[sessionId]
                backend.activateDirectory(target)
                // The client expects the Session itself, and reads it only after
                // the scope switch, so it must be fetched against the new scope.
                fetchSession(sessionId, target, isCancelled) ?: JsonNull.INSTANCE
            }

            "diff-summary" ->
                services.sessionDiffSummary(sessionId, directory, request.query["revision"]?.firstOrNull())
            "followup-usage" -> followupUsage.read(sessionId, body, directory, isCancelled) ?: JsonNull.INSTANCE

            "pin" -> {
                val pinned = body.asObjectOrNull().bool("pinned") ?: true
                Json.array(store.updatePins { current ->
                    if (pinned) listOf(sessionId) + current.filter { it != sessionId } else current.filter { it != sessionId }
                })
            }

            "todo" -> {
                require(sessionId !in store.hiddenSessionIds) { "404 Session not found" }
                val todo = body.asObjectOrNull()?.get("todo")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    ?: throw IllegalArgumentException("Invalid todo request")
                Json.array(store.setTodoSession(sessionId, todo))
            }

            "reorder-pin" -> {
                // The client sends the session this one was dropped onto; the move
                // is "put source where target currently is".
                val targetId = body.asObjectOrNull().text("targetSessionID")
                Json.array(store.updatePins { ids ->
                    ids.toMutableList().apply {
                        val from = indexOf(sessionId)
                        val to = targetId?.let(::indexOf) ?: -1
                        if (from >= 0 && to >= 0 && from != to) { removeAt(from); add(to, sessionId) }
                    }
                })
            }

            "permission-mode" -> {
                selections.updateMode(sessionId, body.asObjectOrNull() ?: error("Missing permission mode"), directory)
            }

            "rename-if-untitled" -> renameIfUntitled(sessionId, body, directory, isCancelled)

            "delete" -> { trash.recycle(sessionId, directory); Json.toElement(true) }

            else -> throw IllegalArgumentException("Unsupported session action: $action")
        }
    }

    /**
     * Gives an untitled session a fallback title. OpenCode normally titles a
     * session from its first exchange; this only fills the gap when it did not,
     * and never overwrites a title the user or OpenCode already set.
     */
    private fun renameIfUntitled(sessionId: String, body: JsonElement?, directory: String?, isCancelled: () -> Boolean): JsonElement {
        val title = body.asObjectOrNull().text("title") ?: return JsonNull.INSTANCE
        val current = runCatching {
            backend.request(
                "GET",
                "/session/${encode(sessionId)}",
                options = RequestOptions(directory = directory, isCancelled = isCancelled),
            ).data.asObjectOrNull()
        }.getOrNull() ?: return JsonNull.INSTANCE

        val existing = current.str("title").orEmpty()
        if (existing.isNotBlank() && !UNTITLED.matches(existing)) return JsonNull.INSTANCE

        return runCatching {
            backend.request(
                "PATCH",
                "/session/${encode(sessionId)}",
                body = Json.obj("title" to title),
                options = RequestOptions(directory = directory, isCancelled = isCancelled),
            )
            Json.obj("id" to sessionId, "title" to title)
        }.getOrElse { JsonNull.INSTANCE }
    }

    // --- Recycle bin ----------------------------------------------------------

    /**
     * `GET` yields the entries as a bare array and `DELETE` (empty the bin)
     * yields a boolean. The client feeds the list straight into
     * `normalizeRecycleBinEntries`, which discards anything that is not an
     * array — silently, so a wrapper object shows up as an empty bin rather
     * than an error.
     */
    private fun handleTrashCollection(method: String): JsonElement {
        if (method == "DELETE") {
            trash.empty()
            return Json.toElement(true)
        }
        return trash.list()
    }

    private fun handleTrashEntry(method: String, request: ApiRoutes.Request): JsonElement {
        val rootId = request.segments[request.segments.size - 2]
        val action = request.segments.last()
        trash.remove(rootId, action == "restore")
        return Json.toElement(true)
    }

    /** Reads one session, scoped to [directory]. `null` when it cannot be read. */
    private fun fetchSession(sessionId: String, directory: String?, isCancelled: () -> Boolean): JsonElement? = runCatching {
        backend.request(
            "GET",
            "/session/${encode(sessionId)}",
            options = RequestOptions(directory = directory, isCancelled = isCancelled),
        ).data
    }.getOrElse { failure ->
        if (WorkspaceDirectoryErrors.isMissing(failure.message)) throw IllegalStateException(WorkspaceDirectoryErrors.message(directory ?: backend.workspaceDirectory().orEmpty()), failure)
        null
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        internal fun skipNativeRecovery(method: String, path: String, recovery: Boolean, apiVersion: Int): Boolean =
            recovery && apiVersion == 2 && method == "POST" &&
                Regex("^/session/[^/]+/(?:prompt_async|message|command)$").matches(path.substringBefore('?'))

        /** Server-wide sessions per scoped catalog page. */
        private const val SCOPED_CATALOG_PAGE = 1_000
        /** Canonical reads one pending snapshot may spend locating candidate sessions. */
        private const val MAX_PROMPT_SESSION_READS = 128
        /** Pages one scoped catalog load reads at most before reporting itself incomplete. */
        private const val SCOPED_CATALOG_PAGES = 20

        /** Titles OpenCode leaves as placeholders. */
        private val UNTITLED = Regex("""^(?:untitled|new session)$""", RegexOption.IGNORE_CASE)
    }
}
