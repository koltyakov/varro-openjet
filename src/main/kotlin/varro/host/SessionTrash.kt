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
    private val legacyUnarchive: () -> Boolean = { false },
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
        val tree = readTree(root, directory)
        // Persist the original records before archiving. Incomplete archives remain restorable.
        update { entries ->
            entries.apply { add(Json.obj("rootID" to id, "root" to root, "sessions" to tree.values,
                "archivedTimes" to Json.obj(),
                "deletedAt" to now(), "expiresAt" to now() + RETENTION_MS, "retained" to true)) }
        }
        store.removeSessionUnreadState(tree.keys)
        val archivedTimes = Json.obj()
        tree.forEach { (sessionId, record) ->
            val archived = request("PATCH", "/session/${encode(sessionId)}",
                Json.obj("time" to Json.obj("archived" to now())), record.str("directory") ?: directory).asObjectOrNull()
            if (archived.str("id") == sessionId) archived.obj("time")?.let { time ->
                archivedTimes.add(sessionId, Json.obj("updated" to time.long("updated"), "archived" to time.long("archived")))
            }
        }
        update { entries ->
            entries.first { it.asJsonObject.str("rootID") == id }.asJsonObject.add("archivedTimes", archivedTimes)
            entries
        }
        synchronized(store) { store.pinnedSessionIds = store.pinnedSessionIds.filterNot { it in tree } }
    }

    private fun readTree(root: JsonObject, directory: String?): Map<String, JsonObject> {
        val tree = linkedMapOf<String, JsonObject>()
        val pending = ArrayDeque<JsonObject>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val record = pending.removeFirst()
            val id = record.text("id") ?: error("Missing session id")
            check(tree.put(id, record) == null) { "Duplicate session in recycled tree" }
            val children = request("GET", "/session/${encode(id)}/children", null, record.str("directory") ?: directory)
                .asArrayOrNull() ?: error("Could not load session children")
            check(tree.size + pending.size + children.size() <= 10_000) { "Session tree is too large" }
            children.forEach { pending.add(it.asJsonObject) }
        }
        return tree
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
            request("GET", "/session/${encode(id)}", null, directory).asObjectOrNull() ?: error("Invalid recycled session response")
        } catch (failure: Exception) {
            if (failure.message?.startsWith("404 ") != true) throw failure
            return forget(id)
        }
        check(current.str("id") == id) { "Invalid recycled session response" }
        val rootTime = current.obj("time") ?: return
        val archived = rootTime.long("archived")?.takeIf { it > 0 } ?: return forget(id)
        val saved = entry.obj("archivedTimes")
        if (saved == null) {
            // Older journals lack post-archive times. Preserve the existing root-only
            // expiry policy, but never authorize recursive deletion on that evidence.
            val recycledAt = maxOf(archived, entry.long("deletedAt") ?: archived)
            if ((rootTime.long("updated") ?: return) > recycledAt + ARCHIVE_UPDATE_TOLERANCE_MS) return forget(id)
        }
        val tree = readTree(current, directory)
        val original = entry.arr("sessions")?.map { it.asJsonObject }?.associateBy { it.text("id")!! } ?: return
        if (tree.keys != original.keys + id) return forget(id)
        for ((key, record) in tree) {
            val previous = original[key]
            if (previous != null && (previous.str("parentID") != record.str("parentID") ||
                previous.str("directory") != record.str("directory") || previous.str("title") != record.str("title"))) return forget(id)
            val time = record.obj("time") ?: return
            val archivedAt = time.long("archived")?.takeIf { it > 0 } ?: return forget(id)
            if (saved == null) continue
            val before = saved.obj(key) ?: return
            val expectedArchive = before.long("archived") ?: return
            val expectedUpdate = before.long("updated") ?: return
            val updated = time.long("updated") ?: return
            if (archivedAt != expectedArchive || updated != expectedUpdate) return forget(id)
        }
        if (saved == null && tree.size > 1) return
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
                val archived = record.obj("time").long("archived")?.takeIf { it > 0 }
                // V1 uses a numeric timestamp; V2 explicitly unarchives with null.
                val restoreTime = archived ?: if (legacyUnarchive()) 0L else null
                request("PATCH", "/session/${encode(record.str("id")!!)}",
                    Json.obj("time" to Json.obj("archived" to restoreTime)), record.str("directory"))
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
