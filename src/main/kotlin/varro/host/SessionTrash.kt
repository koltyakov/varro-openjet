package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.VarroStore

class SessionTrash(
    private val store: VarroStore,
    private val request: (String, String, JsonElement?, String?) -> JsonElement?,
    private val now: () -> Long = System::currentTimeMillis,
    private val journal: varro.store.JsonJournal? = null,
) {
    init {
        synchronized(store) {
            journal?.read(Json.obj("entries" to store.recycleBin))?.arr("entries")?.let { store.recycleBin = it }
        }
    }

    private val log = com.intellij.openapi.diagnostic.logger<SessionTrash>()

    /**
     * Serializes recycle-bin operations. Network calls run under this lock only;
     * the store monitor, which the EDT and the event thread also take, is held
     * just for in-memory reads and read-modify-writes.
     */
    private val operations: Any = synchronized(LOCKS) { LOCKS.getOrPut(store) { Any() } }

    private fun entries(): JsonArray = synchronized(store) { store.recycleBin }

    private fun update(change: (JsonArray) -> JsonArray) = synchronized(store) {
        val entries = change(store.recycleBin)
        journal?.write(Json.obj("entries" to entries))
        store.recycleBin = entries
    }

    fun isHidden(id: String): Boolean = entries().any { entry ->
        entry.asObjectOrNull().str("rootID") == id ||
            entry.asObjectOrNull().arr("sessions")?.any { it.asObjectOrNull().str("id") == id } == true
    }

    fun recycle(id: String, directory: String?) = synchronized(operations) {
        if (entries().any { it.asObjectOrNull().str("rootID") == id }) return@synchronized
        val root = request("GET", "/session/${encode(id)}", null, directory).asObjectOrNull()
            ?: error("Session could not be loaded")
        val sessions = JsonArray()
        val visited = mutableSetOf<String>()
        fun collect(session: JsonObject) {
            val childId = session.text("id") ?: error("Missing session id")
            if (!visited.add(childId)) return
            check(visited.size <= 10000) { "Session tree is too large" }
            sessions.add(session)
            val children = request("GET", "/session/${encode(childId)}/children", null, directory).asArrayOrNull()
                ?: error("Could not load session children")
            children.forEach { collect(it.asJsonObject) }
        }
        collect(root)
        // Persist the tombstone first. A failed archive can still be restored or retried.
        update { entries ->
            entries.apply { add(Json.obj("rootID" to id, "root" to root, "sessions" to sessions,
                "deletedAt" to now(), "expiresAt" to now() + RETENTION_MS, "retained" to true)) }
        }
        store.removeSessionUnreadState(visited)
        sessions.forEach { session ->
            request("PATCH", "/session/${encode(session.asJsonObject.str("id")!!)}",
                Json.obj("time" to Json.obj("archived" to now())), directory)
        }
        synchronized(store) { store.pinnedSessionIds = store.pinnedSessionIds.filterNot { it in visited } }
    }

    /**
     * Lists the bin after purging expired entries. A purge that fails is retried on
     * a later listing rather than making the bin unreadable.
     */
    fun list(): JsonArray = synchronized(operations) {
        entries().forEach {
            val entry = it.asJsonObject
            if ((entry.long("expiresAt") ?: Long.MAX_VALUE) > now()) return@forEach
            val id = entry.str("rootID") ?: return@forEach
            runCatching { expire(id, entry) }.onFailure { failure ->
                log.warn("Could not purge expired recycled session $id: ${failure.message}")
            }
        }
        entries()
    }

    /**
     * Permanently deletes an expired tree only while it is still the archived tree
     * this host recycled. Another client on the shared server (VS Code, the TUI,
     * another project) may have restored and continued it since; that work must
     * survive, so the tombstone is dropped without deleting anything.
     */
    private fun expire(id: String, entry: JsonObject) {
        val directory = entry.obj("root").str("directory")
        val current = try {
            request("GET", "/session/${encode(id)}", null, directory).asObjectOrNull()
        } catch (failure: Exception) {
            if (failure.message?.startsWith("404 ") != true) throw failure
            null
        }
        if (current == null) return forget(id)
        val archived = current.obj("time").long("archived")?.takeIf { it > 0 } ?: return forget(id)
        val updated = current.obj("time").long("updated")
        val recycledAt = maxOf(archived, entry.long("deletedAt") ?: archived)
        if (updated != null && updated > recycledAt + ARCHIVE_UPDATE_TOLERANCE_MS) return forget(id)
        remove(id, false)
    }

    private fun forget(id: String) = update { entries -> Json.array(entries.filter { it.asObjectOrNull().str("rootID") != id }) }

    fun empty() = synchronized(operations) {
        entries().forEach { remove(it.asJsonObject.str("rootID")!!, false) }
    }

    fun remove(id: String, restore: Boolean) = synchronized(operations) {
        val entry = entries().firstOrNull { it.asObjectOrNull().str("rootID") == id }.asObjectOrNull()
            ?: error("404 Recycled session not found")
        val sessions = entry.arr("sessions") ?: error("Missing recycled session tree")
        if (restore) {
            // Old tombstones may refer to sessions already permanently deleted.
            sessions.forEach { session ->
                val record = session.asJsonObject
                request("GET", "/session/${encode(record.str("id")!!)}", null, record.str("directory"))
            }
            sessions.forEach { session ->
                val record = session.asJsonObject
                request("PATCH", "/session/${encode(record.str("id")!!)}",
                    Json.obj("time" to Json.obj("archived" to (record.obj("time").long("archived") ?: 0))), record.str("directory"))
            }
        } else {
            // Deleting a root recursively removes its children on OpenCode.
            try {
                val path = "/session/${encode(id)}"
                val directory = entry.obj("root").str("directory")
                val deleted = request("DELETE", path, null, directory)
                if (deleted?.isJsonPrimitive != true || !deleted.asJsonPrimitive.isBoolean || !deleted.asBoolean) {
                    request("GET", path, null, directory)
                    error("OpenCode did not confirm session deletion: $id")
                }
            } catch (failure: Exception) {
                if (failure.message?.startsWith("404 ") != true) throw failure
            }
        }
        forget(id)
    }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** One operation lock per store: every chat view has its own [SessionTrash]. */
        private val LOCKS = java.util.WeakHashMap<VarroStore, Any>()

        /** Archiving itself bumps `time.updated`; later activity means the tree was resumed. */
        private const val ARCHIVE_UPDATE_TOLERANCE_MS = 60_000L
        private fun encode(id: String) = java.net.URLEncoder.encode(id, Charsets.UTF_8)
    }
}
