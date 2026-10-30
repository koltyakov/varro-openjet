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
class RestProxy(
    private val project: Project,
    private val server: OpenCodeServer,
    private val store: VarroStore,
    private val context: ContextProvider,
    private val services: HostServices,
    private val postResponse: (JsonObject) -> Unit,
    private val admitQueuedDispatch: (JsonObject) -> Boolean = { true },
    private val completeQueuedDispatch: (JsonObject, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val selections: SessionSelections = SessionSelections(store, request = { method, path, body, directory ->
        server.transport.request(method, path, body, RequestOptions(directory = directory)).data
    }),
    private val isPermissionAutomationLeaseCurrent: (Long) -> Boolean = { false },
    private val transfers: SessionTransfers? = null,
) {
    private val log = logger<RestProxy>()
    private val modelPricing = ModelPricingCatalog()
    private val remoteImages = RemoteImages()
    private val citationPaths = CitationPaths()
    private val sideQuestions = SideQuestions(server.transport::request, { server.transport.apiVersion },
        { server.currentStatus() is varro.server.ServerStatus.Running }, { server.requestGeneration() to server.url() },
        { (listOfNotNull(project.basePath) + context.context.arr("workspaceFolders")?.mapNotNull { it.asObjectOrNull().text("path") }.orEmpty()).distinct() })
    private val settings get() = varro.settings.VarroSettings.getInstance()
    private val childTasks = ChildTasks(server.transport::request, { server.transport.apiVersion },
        { server.requestGeneration() to server.url() },
        { (listOfNotNull(project.basePath) + context.context.arr("workspaceFolders")?.mapNotNull { it.asObjectOrNull().text("path") }.orEmpty()).distinct() },
        { it in store.hiddenSessionIds })
    private val settingsSubscription = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect().apply {
        subscribe(varro.settings.VarroSettings.TOPIC, varro.settings.VarroSettings.Listener {
            if (settings.remoteImageMode() != "on") remoteImages.cancel()
        })
    }
    private val contentReader by lazy { MessageContentReader(service<ImageThumbnails>()) { path, options ->
        server.transport.request("GET", path, options = options).data
    } }

    /** In-flight requests, keyed by the webview's cancel key. */
    private val activeRequests = RequestTasks()
    private val disposed = AtomicBoolean(false)
    private val trash = SessionTrash(store, request = { method, path, body, directory ->
        server.transport.request(method, path, body, RequestOptions(directory = directory)).data
    }, journal = varro.store.JsonJournal(java.nio.file.Path.of(
        com.intellij.openapi.application.PathManager.getConfigPath(), "varro", project.locationHash, "trash.json",
    )))

    /**
     * Services the proxy needs from the rest of the host. Kept as an interface so
     * the routing logic stays testable without a live project.
     */
    interface HostServices {
        fun followups(sessionId: String, body: JsonElement?, directory: String?, dismiss: Boolean, isCancelled: () -> Boolean): JsonElement
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
        fun disableProvider(body: JsonElement?): JsonElement
        fun readOpenCodePermissions(): JsonObject
        fun updateOpenCodePermissions(body: JsonElement?): JsonObject
        fun judgePermission(body: JsonElement?): JsonObject
        fun judgeModel(providerId: String?, modelId: String?, variant: String?): JsonObject
        fun decisionProviders(method: String, body: JsonElement?): JsonObject
        fun permissionRules(sessionId: String, rules: JsonElement?, directory: String?): JsonArray
        fun allowPermission(body: JsonObject, project: Boolean, directory: String?): JsonArray
        fun providerLimit(providerId: String, modelId: String?): JsonObject
        fun sessionDiffSummary(sessionId: String, directory: String?, revision: String?): JsonObject
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
            if (skipNativeRecovery(method, path, payload.bool("interruptedRecovery") == true, server.transport.apiVersion)) {
                log.info("interrupted-recovery native-owned, generation=${server.requestGeneration()}")
                respond(id, data = Json.obj("skipped" to true))
                return
            }
            val transferDirectory = transfers?.authorizeRequest(method, path, payload.get("body"))
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
                handleVarroRequest(method, path, payload.get("body"), transferDirectory, cancelled)
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
     * Cancellation stops subsequent adapter requests and suppresses late responses.
     */
    fun cancelRequest(payload: JsonObject) {
        val cancelKey = payload.text("cancelKey") ?: return
        activeRequests.cancel(cancelKey)
    }

    fun dispose() {
        disposed.set(true)
        activeRequests.close()
        remoteImages.close()
        settingsSubscription.disconnect()
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
            val directory = transferDirectory ?: ApiRoutes.parse(method, path)?.query?.get("directory")?.firstOrNull() ?: server.workspaceDirectory().orEmpty()
            WorkspaceDirectoryErrors.recover(method, path, directory, failure)
        }
    }

    private fun forwardRequest(method: String, path: String, body: JsonElement?, queuedDispatch: Boolean, transferDirectory: String?, isCancelled: () -> Boolean): JsonElement? {
        val request = ApiRoutes.parse(method, path)
        require(!(method == "POST" && request?.segments?.firstOrNull() == "question" &&
            body.asObjectOrNull().str("kind") == "native-form" && server.transport.apiVersion != 2)) { "Native forms require OpenCode V2" }
        val sessionId = request?.segments?.takeIf { it.size >= 2 && it[0] == "session" }?.get(1)
        if (request?.segments?.size == 3 && request.segments[0] == "session" &&
            request.segments[2] in setOf("child-task-controls", "interrupt-child", "background-parent")) {
            return childTasks.request(method, path, body, transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: server.workspaceDirectory(), isCancelled)
        }
        if (sessionId != null && sessionId != "status" && method != "GET") {
            transfers?.checkWritable(sessionId)
            if (isCancelled()) throw java.util.concurrent.CancellationException("OpenCode request cancelled")
        }

        if (method == "POST" && request?.segments?.size == 3 && request.segments[2] == "side-question" && sessionId != null) {
            val directory = transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: transfers?.get(sessionId).str("directory") ?: server.workspaceDirectory()
            return sideQuestions.ask(path, body, directory, isCancelled)
        }

        if (method == "GET" && request != null && request.segments.size == 6 &&
            request.segments[0] == "session" && request.segments[2] == "message" && request.segments[4] == "part") {
            return readContent(request, transferDirectory, isCancelled)
        }

        if (method == "GET" && request != null && Regex("^/session/[^/]+/message$").matches(request.pathname)) {
            val directory = transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: transfers?.get(request.segments[1]).str("directory")
                ?: body.asObjectOrNull().str("workspaceDirectory") ?: server.workspaceDirectory()
            val response = server.transport.request(method, path, options = RequestOptions(
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

        val outgoing = if (method == "POST" && sessionId != null && request.segments.size == 3 && request.segments[2] in setOf("prompt_async", "message", "command"))
            CitationPaths.withGuidance(body, transferDirectory ?: request.query["directory"]?.firstOrNull() ?: server.workspaceDirectory()) else body
        val result = server.transport.request(method = method, path = path, body = outgoing, options = RequestOptions(isCancelled = isCancelled,
            directory = sessionId?.let { transfers?.get(it).str("directory") })).data
        if (method == "POST" && sessionId != null && request.segments.size == 3 && request.segments[2] in setOf("prompt_async", "message", "command")) {
            runCatching { services.clearFollowups(sessionId, transferDirectory ?: request.query["directory"]?.firstOrNull()
                ?: server.workspaceDirectory(), body.asObjectOrNull().str("messageID")) }
                .onFailure { log.warn("Could not clear previous-turn follow-up cache") }
        }
        if (method == "GET" && request?.pathname == "/session") return Json.array(
            mergeTransfers(result.asArrayOrNull() ?: JsonArray(), request).filter { it.asObjectOrNull().str("id") !in store.hiddenSessionIds },
        )
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
        val directory = transferDirectory ?: request.query["directory"]?.firstOrNull() ?: transfers?.get(sessionID).str("directory") ?: server.workspaceDirectory()
        return contentReader.read(request, directory, server.url(), isCancelled)
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
        val response = server.transport.request("GET", path, options = RequestOptions(captureNextCursor = true, isCancelled = isCancelled))
        val sessions = mergeTransfers(response.data.asArrayOrNull() ?: JsonArray(), request)

        val recycled = store.recycleBin.flatMap {
            it.asObjectOrNull()?.getAsJsonArray("sessions")?.mapNotNull { session -> session.asObjectOrNull().str("id") }.orEmpty()
        }.toSet()
        val hidden = store.hiddenSessionIds

        val visible = sessions.filter { entry ->
            val id = entry.asObjectOrNull().str("id") ?: return@filter false
            id !in recycled && id !in hidden
        }

        // Newest first, matching how the client renders the list.
        val ordered = visible.sortedByDescending { entry ->
            entry.asObjectOrNull().obj("time").num("updated")
                ?: entry.asObjectOrNull().obj("time").num("created")
                ?: 0.0
        }

        val items = JsonArray().apply { ordered.take(limit).forEach(::add) }
        return Json.obj("items" to items, "hasMore" to (ordered.size > limit || response.nextCursor != null))
    }

    private fun mergeTransfers(sessions: JsonArray, request: ApiRoutes.Request): JsonArray =
        transfers?.merge(sessions, request) ?: sessions

    // --- Host API namespace ---------------------------------------------------

    private fun handleVarroRequest(method: String, path: String, body: JsonElement?, transferDirectory: String?, isCancelled: () -> Boolean): JsonElement? {
        val request = ApiRoutes.parse(method, path) ?: throw IllegalArgumentException("Bad request")
        val pathname = request.pathname
        val query = request.query

        fun q(key: String): String? = query[key]?.firstOrNull()?.takeIf { it.isNotBlank() }

        return when {
            pathname == ApiRoutes.Endpoints.CITATION_PATHS -> citationPaths.validate(body, server.workspaceDirectory(), { session, directory ->
                val roots = (listOfNotNull(project.basePath) + context.context.arr("workspaceFolders")?.mapNotNull { it.asObjectOrNull().text("path") }.orEmpty()).distinct()
                if (session == null) roots.any { WorkspacePaths.isSame(it, directory) }
                else {
                    require(session !in store.hiddenSessionIds) { "Session not found" }
                    val options = RequestOptions(unscoped = true, timeoutMs = 15_000, maxResponseBytes = 2 * 1024 * 1024,
                        cancellableBody = true, isCancelled = isCancelled)
                    FileReferences.authorizeDirectory(session, directory, roots, store.sessionHistoryScope,
                        { server.transport.request("GET", "/session/${encode(session)}", options = options).data.asObjectOrNull() },
                        { root -> server.transport.request("GET", "/project/current", options = options.copy(unscoped = false, directory = root)).data.asObjectOrNull() })
                }
            }, isCancelled)
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

            pathname == ApiRoutes.Endpoints.WORKSPACE_PROBLEMS -> WorkspaceProblems.snapshot(project)

            pathname == ApiRoutes.Endpoints.COPIED_SELECTION_MATCH -> {
                val record = body.asObjectOrNull()
                val text = record.str("text")
                require(text != null && text.isNotBlank() && text.length <= 256 * 1024) { "Invalid copied selection text" }
                context.matchCopiedSelection(text, record.bool("plainTextOnly") == true)
            }

            pathname == ApiRoutes.Endpoints.PROVIDER_LIMIT ->
                services.providerLimit(q("providerID")!!, q("modelID"))

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

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_MODEL_ROUTING ->
                services.updateModelRouting(body)

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_DISABLE_PROVIDER ->
                services.disableProvider(body)

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_PERMISSIONS ->
                if (method == "GET") services.readOpenCodePermissions()
                else services.updateOpenCodePermissions(body)

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
            pathname == ApiRoutes.Endpoints.PERMISSION_SERVER_MEMORY -> server.transport.savedPermissions.handle(
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
            body.asObjectOrNull().str("scope")?.let { store.sessionHistoryScope = it }
        }
        return Json.obj(
            "scope" to store.sessionHistoryScope,
            "git" to isGitWorkingTree(directory),
        )
    }

    /** Walks up from [directory] looking for a `.git` entry, as Git itself does. */
    private fun isGitWorkingTree(directory: String?): Boolean {
        val start = directory?.takeIf { it.isNotBlank() }
            ?: project.basePath
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
            "followups", "dismiss" -> {
                require(sessionId !in store.hiddenSessionIds) { "404 Session not found" }
                services.followups(sessionId, body, directory, action == "dismiss", isCancelled)
            }
            "activate" -> {
                // Activating a session repoints REST scoping and the event stream at
                // the directory that session actually belongs to, so subsequent
                // message and diff reads resolve against the right workspace.
                val target = body.asObjectOrNull().text("directory")
                    ?: server.transport.observedSessionDirectories()[sessionId]
                server.activateDirectory(target)
                // The client expects the Session itself, and reads it only after
                // the scope switch, so it must be fetched against the new scope.
                fetchSession(sessionId, target, isCancelled) ?: JsonNull.INSTANCE
            }

            "diff-summary" ->
                services.sessionDiffSummary(sessionId, directory, request.query["revision"]?.firstOrNull())

            "pin" -> {
                val pinned = body.asObjectOrNull().bool("pinned") ?: true
                val current = store.pinnedSessionIds.toMutableList()
                if (pinned) {
                    if (sessionId !in current) current.add(sessionId)
                } else {
                    current.remove(sessionId)
                }
                store.pinnedSessionIds = current
                // The client types this as the new pinned-id list.
                Json.array(current)
            }

            "reorder-pin" -> {
                // The client sends the session this one was dropped onto; the move
                // is "put source where target currently is".
                val targetId = body.asObjectOrNull().text("targetSessionID")
                val current = store.pinnedSessionIds.toMutableList()
                val from = current.indexOf(sessionId)
                val to = targetId?.let(current::indexOf) ?: -1
                if (from >= 0 && to >= 0 && from != to) {
                    current.removeAt(from)
                    current.add(to, sessionId)
                    store.pinnedSessionIds = current
                }
                Json.array(store.pinnedSessionIds)
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
            server.transport.request(
                "GET",
                "/session/${encode(sessionId)}",
                options = RequestOptions(directory = directory, isCancelled = isCancelled),
            ).data.asObjectOrNull()
        }.getOrNull() ?: return JsonNull.INSTANCE

        val existing = current.str("title").orEmpty()
        if (existing.isNotBlank() && !UNTITLED.matches(existing)) return JsonNull.INSTANCE

        return runCatching {
            server.transport.request(
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
        server.transport.request(
            "GET",
            "/session/${encode(sessionId)}",
            options = RequestOptions(directory = directory, isCancelled = isCancelled),
        ).data
    }.getOrElse { failure ->
        if (WorkspaceDirectoryErrors.isMissing(failure.message)) throw IllegalStateException(WorkspaceDirectoryErrors.message(directory ?: server.workspaceDirectory().orEmpty()), failure)
        null
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        internal fun skipNativeRecovery(method: String, path: String, recovery: Boolean, apiVersion: Int): Boolean =
            recovery && apiVersion == 2 && method == "POST" &&
                Regex("^/session/[^/]+/(?:prompt_async|message|command)$").matches(path.substringBefore('?'))

        /** Titles OpenCode leaves as placeholders. */
        private val UNTITLED = Regex("""^(?:untitled|new session)$""", RegexOption.IGNORE_CASE)
    }
}
