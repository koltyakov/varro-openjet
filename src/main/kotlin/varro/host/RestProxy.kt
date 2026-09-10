package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import varro.protocol.Json
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
) {
    private val log = logger<RestProxy>()

    /** In-flight requests, keyed by the webview's cancel key. */
    private val activeRequests = ConcurrentHashMap<String, Int>()
    private val disposed = AtomicBoolean(false)

    /**
     * Services the proxy needs from the rest of the host. Kept as an interface so
     * the routing logic stays testable without a live project.
     */
    interface HostServices {
        fun openPlanDocument(content: String, title: String?): String?
        fun pickWorkspaceFile(): JsonObject?
        fun readWorkspaceFile(path: String): JsonObject?
        fun resolveWorkspacePath(path: String): JsonObject?
        fun readOpenCodeConfig(): JsonObject
        fun updateModelRouting(body: JsonElement?): JsonObject
        fun readOpenCodePermissions(): JsonObject
        fun updateOpenCodePermissions(body: JsonElement?): JsonObject
        fun judgePermission(body: JsonElement?): JsonObject
        fun judgeModel(providerId: String?, modelId: String?, variant: String?): JsonObject
        fun providerLimit(providerId: String, modelId: String?): JsonObject
        fun sessionDiffSummary(sessionId: String, directory: String?, revision: String?): JsonObject
    }

    fun handleRequest(payload: JsonObject) {
        val id = payload.int("id") ?: return
        val method = payload.str("method")?.uppercase() ?: return
        val path = payload.str("path") ?: return
        val cancelKey = payload.text("cancelKey")

        if (disposed.get()) {
            respond(id, error = "REST proxy disposed")
            return
        }

        // The allowlist runs before anything else touches the request.
        if (!ApiRoutes.isAllowed(method, path)) {
            respond(id, error = "Unsupported API request")
            return
        }

        cancelKey?.let { key ->
            if (activeRequests.putIfAbsent(key, id) != null) {
                respond(id, error = "Duplicate API request cancellation key")
                return
            }
        }

        try {
            if (payload.obj("queuedMessageDispatch") != null && !admitQueuedDispatch(payload)) {
                error("Queued message dispatch lease is no longer current")
            }
            val data = if (path.startsWith(ApiRoutes.NAMESPACE)) {
                handleVarroRequest(method, path, payload.get("body"))
            } else {
                forward(method, path, payload.get("body"))
            }
            if (cancelKey == null || activeRequests.containsKey(cancelKey)) respond(id, data = data)
        } catch (failure: Exception) {
            log.warn("api/request failed: $method ${path.substringBefore('?')}: ${failure.message}")
            if (cancelKey == null || activeRequests.containsKey(cancelKey)) {
                respond(id, error = failure.message ?: "Request failed")
            }
        } finally {
            cancelKey?.let(activeRequests::remove)
        }
    }

    /**
     * Cancellation is cooperative: the entry is dropped so the response is
     * suppressed when the request finally settles. The webview has already
     * rejected its promise by this point, so delivering a late response would
     * only resolve a request nothing is waiting for.
     */
    fun cancelRequest(payload: JsonObject) {
        val cancelKey = payload.text("cancelKey") ?: return
        activeRequests.remove(cancelKey)
    }

    fun dispose() {
        disposed.set(true)
        activeRequests.clear()
    }

    private fun respond(id: Int, data: JsonElement? = null, error: String? = null) {
        val payload = JsonObject().apply {
            addProperty("id", id)
            if (error != null) addProperty("error", error) else add("data", data ?: JsonNull.INSTANCE)
        }
        postResponse(Json.message("api/response", payload).asJsonObject)
    }

    // --- OpenCode forwarding --------------------------------------------------

    private fun forward(method: String, path: String, body: JsonElement?): JsonElement? {
        val request = ApiRoutes.parse(method, path)

        // `GET /session?limit=` is a paginated read in the client's contract even
        // though OpenCode answers with a plain array, so the host builds the page.
        if (method == "GET" && request?.pathname == "/session" && request.query.containsKey("limit")) {
            return sessionPage(path, request)
        }

        return server.transport.request(method = method, path = path, body = body).data
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
    private fun sessionPage(path: String, request: ApiRoutes.Request): JsonElement {
        val limit = request.query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 1_000) ?: 100
        val response = server.transport.request("GET", path)
        val sessions = response.data.asArrayOrNull() ?: JsonArray()

        val recycled = store.recycleBin
            .mapNotNull { it.asObjectOrNull().str("rootID") }
            .toSet()
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
        return Json.obj("items" to items, "hasMore" to (ordered.size > limit))
    }

    // --- Host API namespace ---------------------------------------------------

    private fun handleVarroRequest(method: String, path: String, body: JsonElement?): JsonElement? {
        val request = ApiRoutes.parse(method, path) ?: throw IllegalArgumentException("Bad request")
        val pathname = request.pathname
        val query = request.query

        fun q(key: String): String? = query[key]?.firstOrNull()?.takeIf { it.isNotBlank() }

        return when {
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

            pathname == ApiRoutes.Endpoints.OPENCODE_CONFIG_PERMISSIONS ->
                if (method == "GET") services.readOpenCodePermissions()
                else services.updateOpenCodePermissions(body)

            pathname == ApiRoutes.Endpoints.PERMISSION_JUDGE -> services.judgePermission(body)

            // `ChatModelSelection | null`, unwrapped.
            pathname == ApiRoutes.Endpoints.PERMISSION_JUDGE_MODEL ->
                services.judgeModel(q("providerID"), q("modelID"), q("variant"))
                    .get("model") ?: JsonNull.INSTANCE

            pathname == ApiRoutes.Endpoints.SESSION_HISTORY_SCOPE ->
                handleHistoryScope(method, body, q("directory"))

            pathname == ApiRoutes.Endpoints.SESSION_TRASH -> handleTrashCollection(method)

            pathname.startsWith("${ApiRoutes.Endpoints.SESSION_TRASH}/") ->
                handleTrashEntry(method, request)

            pathname.startsWith("${ApiRoutes.Endpoints.SESSION}/") ->
                handleSessionEndpoint(method, request, body, q("directory"))

            // Host-stored permission rules are not implemented in this port.
            // These answer with the shapes the client validates, using each
            // contract's own "unsupported" branch, so the UI reports the feature
            // as unavailable instead of rejecting a malformed reply.
            pathname == ApiRoutes.Endpoints.PERMISSION_SERVER_MEMORY -> Json.obj(
                "supported" to false,
                "rules" to JsonArray(),
                "reason" to "Server-memory permissions are not implemented in Varro OpenJet.",
            )

            pathname == ApiRoutes.Endpoints.PERMISSION_SESSION_RULES -> Json.obj("rules" to JsonArray())
            pathname == ApiRoutes.Endpoints.PERMISSION_SESSION_ALLOW -> Json.obj("rules" to JsonArray())
            pathname == ApiRoutes.Endpoints.PERMISSION_PROJECT_ALLOW -> Json.obj("rules" to JsonArray())

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
    ): JsonElement {
        val sessionId = ApiRoutes.varroSessionId(request.pathname)
            ?: throw IllegalArgumentException("Malformed session endpoint")
        val action = request.segments.last()

        return when (action) {
            "activate" -> {
                // Activating a session repoints REST scoping and the event stream at
                // the directory that session actually belongs to, so subsequent
                // message and diff reads resolve against the right workspace.
                val target = body.asObjectOrNull().text("directory")
                    ?: server.transport.observedSessionDirectories()[sessionId]
                server.activateDirectory(target)
                // The client expects the Session itself, and reads it only after
                // the scope switch, so it must be fetched against the new scope.
                fetchSession(sessionId, target) ?: JsonNull.INSTANCE
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
                val mode = body.asObjectOrNull()?.get("mode")
                val modes = store.sessionPermissionModes
                if (mode == null || mode.isJsonNull) modes.remove(sessionId)
                else modes.add(sessionId, mode)
                store.sessionPermissionModes = modes
                // The mode is Varro state, but the client types the reply as the
                // Session so it can refresh the row it just changed.
                fetchSession(sessionId, directory) ?: JsonNull.INSTANCE
            }

            "rename-if-untitled" -> renameIfUntitled(sessionId, body, directory)

            "delete" -> deleteSession(sessionId, directory)

            else -> throw IllegalArgumentException("Unsupported session action: $action")
        }
    }

    /**
     * Gives an untitled session a fallback title. OpenCode normally titles a
     * session from its first exchange; this only fills the gap when it did not,
     * and never overwrites a title the user or OpenCode already set.
     */
    private fun renameIfUntitled(sessionId: String, body: JsonElement?, directory: String?): JsonElement {
        val title = body.asObjectOrNull().text("title") ?: return JsonNull.INSTANCE
        val current = runCatching {
            server.transport.request(
                "GET",
                "/session/${encode(sessionId)}",
                options = RequestOptions(directory = directory),
            ).data.asObjectOrNull()
        }.getOrNull() ?: return JsonNull.INSTANCE

        val existing = current.str("title").orEmpty()
        if (existing.isNotBlank() && !UNTITLED.matches(existing)) return JsonNull.INSTANCE

        return runCatching {
            server.transport.request(
                "PATCH",
                "/session/${encode(sessionId)}",
                body = Json.obj("title" to title),
                options = RequestOptions(directory = directory),
            )
            Json.obj("id" to sessionId, "title" to title)
        }.getOrElse { JsonNull.INSTANCE }
    }

    /**
     * Recycles a session tree rather than deleting it outright. Upstream keeps a
     * restorable copy for a grace period, and the webview's recycle-bin view reads
     * it back through `/varro/session-trash`.
     */
    private fun deleteSession(sessionId: String, directory: String?): JsonElement {
        val session = runCatching {
            server.transport.request(
                "GET",
                "/session/${encode(sessionId)}",
                options = RequestOptions(directory = directory),
            ).data.asObjectOrNull()
        }.getOrNull()

        val now = System.currentTimeMillis()
        if (session != null) {
            val entries = store.recycleBin
            entries.add(
                Json.obj(
                    "rootID" to sessionId,
                    "deletedAt" to now,
                    "expiresAt" to now + TRASH_RETENTION_MS,
                    "root" to session,
                    "sessions" to JsonArray().apply { add(session) },
                ),
            )
            store.recycleBin = pruneExpired(entries, now)
        }

        server.transport.request(
            "DELETE",
            "/session/${encode(sessionId)}",
            options = RequestOptions(directory = directory),
        )

        // A recycled session should not keep occupying UI state.
        store.pinnedSessionIds = store.pinnedSessionIds.filterNot { it == sessionId }
        return Json.toElement(true)
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
            store.recycleBin = JsonArray()
            return Json.toElement(true)
        }
        val pruned = pruneExpired(store.recycleBin, System.currentTimeMillis())
        store.recycleBin = pruned
        return pruned
    }

    private fun handleTrashEntry(method: String, request: ApiRoutes.Request): JsonElement {
        val rootId = request.segments[request.segments.size - 2]
        val action = request.segments.last()
        val entries = store.recycleBin
        // Looked up purely to reject an unknown id rather than silently succeed.
        entries.firstOrNull { it.asObjectOrNull().str("rootID") == rootId }
            ?: throw IllegalArgumentException("404 Recycled session not found")

        val remaining = JsonArray().apply {
            entries.filter { it.asObjectOrNull().str("rootID") != rootId }.forEach(::add)
        }

        // Both actions answer with a boolean. "restore" only removes the
        // tombstone: OpenCode already deleted the session, so the entry is the
        // record that it existed, not a live copy.
        store.recycleBin = remaining
        return Json.toElement(true)
    }

    private fun pruneExpired(entries: JsonArray, now: Long): JsonArray = JsonArray().apply {
        entries.filter { entry ->
            val expiresAt = entry.asObjectOrNull()?.get("expiresAt")?.asJsonPrimitive
                ?.takeIf { it.isNumber }?.asLong
            expiresAt == null || expiresAt > now
        }.forEach(::add)
    }

    /** Reads one session, scoped to [directory]. `null` when it cannot be read. */
    private fun fetchSession(sessionId: String, directory: String?): JsonElement? = runCatching {
        server.transport.request(
            "GET",
            "/session/${encode(sessionId)}",
            options = RequestOptions(directory = directory),
        ).data
    }.getOrNull()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        /** How long a recycled session tree stays restorable. */
        private const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** Titles OpenCode leaves as placeholders. */
        private val UNTITLED = Regex("""^(?:untitled|new session)$""", RegexOption.IGNORE_CASE)
    }
}
