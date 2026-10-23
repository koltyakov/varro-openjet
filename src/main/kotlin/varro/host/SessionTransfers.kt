package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.WorkspacePaths

/** Origin links authorize history reads, never execution in a folder outside this project. */
class SessionTransfers(
    initial: JsonArray,
    private val persist: (JsonArray) -> Unit,
    private val serverUrl: () -> String,
    private val roots: () -> List<String>,
    private val readSession: (String) -> JsonElement?,
    private val publish: (JsonObject) -> Unit,
    private val invalidateCatalog: () -> Unit = {},
) {
    private val entries = linkedMapOf<String, JsonObject>()
    private val observed = linkedMapOf<String, String>()

    init {
        initial.toList().takeLast(512).forEach { value ->
            val entry = value.asObjectOrNull() ?: return@forEach
            val server = entry.text("server") ?: return@forEach
            val session = summary(entry.get("session")) ?: return@forEach
            val origins = entry.arr("origins")?.strings()?.filter(::isDirectory)?.take(32).orEmpty()
            if (origins.isEmpty() || !isDirectory(session.obj("transfer").str("originDirectory"))) return@forEach
            entries[key(server, session.str("id")!!)] = Json.obj("server" to server, "origins" to origins, "session" to session).apply {
                entry.arr("hiddenOrigins")?.strings()?.filter(::isDirectory)?.take(32)?.takeIf { it.isNotEmpty() }
                    ?.let { add("hiddenOrigins", Json.toElement(it)) }
            }
        }
    }

    @Synchronized fun observe(session: JsonObject) {
        val id = session.text("id")?.takeIf(::isSessionId) ?: return
        val directory = session.str("directory")?.takeIf(::isDirectory) ?: return
        val key = key(serverUrl(), id)
        val savedUpdated = entries[key].obj("session").obj("time").num("updated")
        if (savedUpdated != null && session.obj("time").num("updated")?.let { it < savedUpdated } == true) return
        val old = observed[key] ?: entries[key].obj("session").str("directory")
        if (old != null && !WorkspacePaths.isSame(old, directory) && (isOpen(old) || entry(id) != null)) {
            remember(session, old)
        }
        observed[key] = directory
        while (observed.size > 4096) observed.remove(observed.keys.first())
    }

    /** A durable move may replay. Check its current location before publishing anything. */
    fun moved(id: String, directory: String, origin: String?): Boolean {
        if (!isSessionId(id) || !isDirectory(directory)) return false
        val known = synchronized(this) { observed[key(serverUrl(), id)] ?: entry(id).obj("session").str("directory") }
        val source = listOfNotNull(known, origin).firstOrNull { isOpen(it) && !WorkspacePaths.isSame(it, directory) }
        if (source == null && synchronized(this) { entry(id) } == null) return false
        val session = readSession(id).asObjectOrNull() ?: return false
        if (session.str("id") != id || !WorkspacePaths.isSame(session.str("directory"), directory)) return false
        synchronized(this) {
            remember(session, source ?: entry(id).obj("session").obj("transfer").str("originDirectory") ?: return false)
            observed[key(serverUrl(), id)] = directory
        }
        return true
    }

    @Synchronized fun owns(id: String, origin: String?): Boolean = isSessionId(id) &&
        (entry(id) != null || isOpen(observed[key(serverUrl(), id)]) || isOpen(origin))

    @Synchronized private fun remember(value: JsonObject, origin: String) {
        val session = summary(value) ?: return
        val server = serverUrl().takeIf { it.isNotBlank() } ?: return
        val id = session.str("id")!!
        val key = key(server, id)
        val previous = entries[key]
        val origins = (previous.arr("origins")?.strings().orEmpty() + origin).distinct().filter(::isDirectory).take(32)
        session.add("transfer", Json.obj("originDirectory" to (previous.obj("session").obj("transfer").str("originDirectory") ?: origin), "available" to isOpen(session.str("directory"))))
        val next = Json.obj("server" to server, "origins" to origins, "session" to session)
        previous.arr("hiddenOrigins")?.let { next.add("hiddenOrigins", it.deepCopy()) }
        val changed = previous?.get("session").asObjectOrNull().str("directory") != session.str("directory")
        val saved = LinkedHashMap(entries)
        saved[key] = next
        while (saved.size > 512) saved.remove(saved.keys.first())
        // Persist before exposing a link. Failed persistence must remain retryable.
        persist(Json.array(saved.values))
        entries.clear(); entries.putAll(saved)
        if (changed) publish(Json.obj("sessionId" to id, "directory" to session.str("directory"), "available" to isOpen(session.str("directory"))))
    }

    @Synchronized fun list(includeHidden: Boolean = false, directory: String? = null): List<JsonObject> {
        val scope = directory?.let(::listOf) ?: roots()
        return entries.values.filter { entry ->
            val hiddenOrigins = entry.arr("hiddenOrigins")?.strings().orEmpty()
            belongs(entry) && entry.arr("origins")?.strings().orEmpty().any { origin ->
                scope.any { WorkspacePaths.isSame(it, origin) } &&
                    (includeHidden || hiddenOrigins.none { WorkspacePaths.isSame(it, origin) })
            }
        }.mapNotNull { project(it, scope) }
    }

    @Synchronized fun get(id: String): JsonObject? = entry(id)?.let { project(it, roots()) }

    private fun project(entry: JsonObject, scope: List<String>): JsonObject? = entry.obj("session")?.deepCopy()?.apply {
        val hiddenOrigins = entry.arr("hiddenOrigins")?.strings().orEmpty()
        val available = isOpen(str("directory"))
        val hidden = !available && entry.arr("origins")?.strings().orEmpty()
            .filter { origin -> scope.any { WorkspacePaths.isSame(it, origin) } }
            .all { origin -> hiddenOrigins.any { WorkspacePaths.isSame(it, origin) } }
        add("transfer", Json.obj("originDirectory" to obj("transfer").str("originDirectory"), "available" to available).apply {
            if (hiddenOrigins.isNotEmpty()) add("hiddenOrigins", Json.toElement(hiddenOrigins))
            if (hidden) addProperty("hidden", true)
        })
    }

    /** Hiding affects only origin-list preferences, not history access or the server session. */
    @Synchronized fun hide(id: String, directory: String? = null): Boolean {
        require(isSessionId(id)) { "Invalid session ID" }
        require(directory == null || isOpen(directory)) { "Workspace folder is not open" }
        val entry = entry(id) ?: return false
        val scope = directory?.let(::listOf) ?: roots()
        val origins = entry.arr("origins")?.strings().orEmpty().filter { origin ->
            scope.any { WorkspacePaths.isSame(it, origin) } && !WorkspacePaths.isSame(origin, entry.obj("session").str("directory"))
        }
        if (origins.isEmpty()) return false
        val next = entry.deepCopy().apply {
            add("hiddenOrigins", Json.toElement((entry.arr("hiddenOrigins")?.strings().orEmpty() + origins).distinct().take(32)))
        }
        val saved = LinkedHashMap(entries).apply { put(key(serverUrl(), id), next) }
        persist(Json.array(saved.values))
        entries.clear(); entries.putAll(saved)
        invalidateCatalog()
        return true
    }

    /** Include hidden metadata so stale pages and active history reads cannot restore a visible link. */
    @Synchronized fun merge(sessions: JsonArray, request: ApiRoutes.Request): JsonArray {
        val merged = sessions.mapNotNull { it.asObjectOrNull() }.associateByTo(linkedMapOf()) { it.str("id") }
        val search = request.query["search"]?.firstOrNull()
        list(includeHidden = true, directory = request.query["directory"]?.firstOrNull()).forEach { session ->
            if (search != null && !session.str("title").orEmpty().contains(search, ignoreCase = true)) return@forEach
            if (request.query["roots"]?.firstOrNull() == "true" && session.text("parentID") != null) return@forEach
            val id = session.str("id")!!
            val current = merged[id]
            if (current == null) merged[id] = session else current.add("transfer", session.get("transfer"))
        }
        return Json.array(merged.values)
    }

    /** Return a verified history scope, rejecting writes and unrelated directory overrides. */
    fun authorizeRequest(method: String, path: String, body: JsonElement? = null): String? {
        val request = ApiRoutes.parse(method, path) ?: return null
        val parts = request.segments
        val id = when {
            parts.size >= 2 && parts[0] == "session" -> parts[1]
            parts.size >= 3 && parts[0] == "varro" && parts[1] == "session" -> parts[2]
            request.pathname in listOf(ApiRoutes.Endpoints.PERMISSION_SESSION_RULES, ApiRoutes.Endpoints.PERMISSION_SESSION_ALLOW,
                ApiRoutes.Endpoints.PERMISSION_PROJECT_ALLOW) -> body.asObjectOrNull().text("sessionId") ?: request.query["sessionId"]?.firstOrNull()
            parts.size == 3 && parts[0] == "question" && body.asObjectOrNull().str("kind") == "native-form" -> body.asObjectOrNull().text("sessionID")
            else -> null
        } ?: return null
        val session = get(id) ?: return null
        val history = method == "GET" && (Regex("^/session/[^/]+(?:/(?:message(?:/[^/]+(?:/part/[^/]+)?)?|diff|todo))?$").matches(request.pathname) ||
            Regex("^/varro/session/[^/]+/diff-summary$").matches(request.pathname))
        if (!history && session.obj("transfer").bool("available") == false) {
            error("Conversation moved to ${session.str("directory")}. Open its folder to continue.")
        }
        if (!history) return null
        val explicit = request.query["directory"]?.firstOrNull()
        require(explicit == null || WorkspacePaths.isSame(explicit, session.str("directory")) ||
            WorkspacePaths.isSame(explicit, session.obj("transfer").str("originDirectory"))) { "404 Session not found" }
        return session.str("directory")
    }

    fun checkWritable(id: String) {
        require(isSessionId(id)) { "Invalid session id" }
        // Refresh before writes so a missed move event cannot admit work from the old folder.
        val known = synchronized(this) { observed.containsKey(key(serverUrl(), id)) || entry(id) != null }
        if (known) {
            val current = readSession(id).asObjectOrNull()
            check(current.str("id") == id && isDirectory(current.str("directory"))) { "Cannot verify conversation location." }
            observe(current!!)
        }
        get(id)?.takeIf { it.obj("transfer").bool("available") == false }?.let {
            error("Conversation moved to ${it.str("directory")}. Open its folder to continue.")
        }
    }

    fun openDirectory(id: String): String? {
        val saved = get(id) ?: return null
        val directory = saved.str("directory") ?: return null
        val current = readSession(id).asObjectOrNull()
        check(current.str("id") == id && WorkspacePaths.isSame(current.str("directory"), directory)) {
            "The conversation moved again. Refresh the session list to find it."
        }
        return directory
    }

    @Synchronized fun remove(id: String) {
        val saved = LinkedHashMap(entries)
        if (saved.remove(key(serverUrl(), id)) == null) return
        persist(Json.array(saved.values))
        entries.clear(); entries.putAll(saved)
        observed.remove(key(serverUrl(), id))
    }

    private fun entry(id: String): JsonObject? = entries[key(serverUrl(), id)]?.takeIf(::belongs)
    private fun belongs(entry: JsonObject) = entry.str("server") == serverUrl() && entry.arr("origins")?.strings()?.any(::isOpen) == true
    private fun isOpen(directory: String?) = directory != null && roots().any { WorkspacePaths.isSame(it, directory) }

    companion object {
        private fun key(server: String, id: String) = "$server\u0000$id"
        private fun isSessionId(id: String) = id.length in 1..512 && id.matches(Regex("[A-Za-z0-9_-]+"))
        private fun isDirectory(value: String?) = value != null && value.isNotBlank() && value.length <= 4096 &&
            value.none { Character.isISOControl(it) } && WorkspacePaths.isAbsolute(value)
        private fun summary(value: JsonElement?): JsonObject? {
            val record = value.asObjectOrNull() ?: return null
            val time = record.obj("time")
            if (!isSessionId(record.str("id").orEmpty()) || !isDirectory(record.str("directory")) || record.str("title") == null ||
                record.str("projectID") == null || record.str("version") == null || time.num("created")?.isFinite() != true ||
                time.num("updated")?.isFinite() != true || time.num("archived") != null) return null
            return Json.obj("id" to record.str("id"), "directory" to record.str("directory"), "title" to record.str("title")!!.take(2048),
                "projectID" to record.str("projectID"), "version" to record.str("version"),
                "time" to Json.obj("created" to time!!.get("created"), "updated" to time.get("updated"))).apply {
                record.str("parentID")?.let { addProperty("parentID", it) }
                if (record.str("workspaceScope") == "workspace") addProperty("workspaceScope", "workspace")
                record.obj("transfer")?.str("originDirectory")?.takeIf(::isDirectory)?.let {
                    add("transfer", Json.obj("originDirectory" to it, "available" to false))
                }
            }
        }
    }
}
