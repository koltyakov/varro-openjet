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

    private fun save(entries: JsonArray) {
        journal?.write(Json.obj("entries" to entries))
        store.recycleBin = entries
    }
    fun recycle(id: String, directory: String?) = synchronized(store) {
        if (store.recycleBin.any { it.asObjectOrNull().str("rootID") == id }) return@synchronized
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
        val entries = store.recycleBin
        entries.add(Json.obj("rootID" to id, "root" to root, "sessions" to sessions,
            "deletedAt" to now(), "expiresAt" to now() + RETENTION_MS, "retained" to true))
        save(entries)
        store.removeSessionUnreadState(visited)
        sessions.forEach { session ->
            request("PATCH", "/session/${encode(session.asJsonObject.str("id")!!)}",
                Json.obj("time" to Json.obj("archived" to now())), directory)
        }
        store.pinnedSessionIds = store.pinnedSessionIds.filterNot { it in visited }
    }

    fun list(): JsonArray = synchronized(store) {
        store.recycleBin.toList().forEach {
            val entry = it.asJsonObject
            if ((entry.long("expiresAt") ?: Long.MAX_VALUE) <= now()) remove(entry.str("rootID")!!, false)
        }
        store.recycleBin
    }

    fun empty() = synchronized(store) {
        store.recycleBin.toList().forEach { remove(it.asJsonObject.str("rootID")!!, false) }
    }

    fun remove(id: String, restore: Boolean) = synchronized(store) {
        val entry = store.recycleBin.firstOrNull { it.asObjectOrNull().str("rootID") == id }.asObjectOrNull()
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
                request("DELETE", "/session/${encode(id)}", null, entry.obj("root").str("directory"))
            } catch (failure: Exception) {
                if (failure.message?.startsWith("404 ") != true) throw failure
            }
        }
        save(Json.array(store.recycleBin.filter { it.asObjectOrNull().str("rootID") != id }))
    }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private fun encode(id: String) = java.net.URLEncoder.encode(id, Charsets.UTF_8)
    }
}
