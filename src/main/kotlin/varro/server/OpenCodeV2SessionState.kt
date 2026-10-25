package varro.server

import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.JsonJournal
import varro.store.NativeFileLock
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Same per-session annotation files as Varro's v2 adapter. */
internal class OpenCodeV2SessionState(val directory: Path = Path.of(
    System.getenv("XDG_STATE_HOME") ?: Path.of(System.getProperty("user.home"), ".local", "state").toString(), "varro", "opencode-v2",
)) {
    private fun path(id: String): Path {
        require(Regex("[A-Za-z0-9_-]{1,256}").matches(id)) { "Invalid session ID" }
        return directory.resolve("$id.json")
    }
    private fun lock(id: String) = locks.computeIfAbsent(directory.resolve(id).toString()) { Any() }
    fun read(id: String): JsonObject = synchronized(lock(id)) { JsonJournal(path(id)).read(Json.obj()) }
    fun update(id: String, patch: JsonObject, onlyIfMissing: String? = null, checkCancelled: () -> Unit = {}) = synchronized(lock(id)) {
        checkCancelled()
        val release = NativeFileLock.acquire(path(id).resolveSibling("$id.json.lock"), checkCancelled,
            "Timed out waiting to update Varro session annotations")
        try {
            val value = read(id)
            if (onlyIfMissing == null || !value.has(onlyIfMissing)) {
                val time = (value.obj("time") ?: Json.obj()).deepCopy()
                patch.obj("time")?.entrySet()?.forEach { time.add(it.key, it.value) }
                patch.entrySet().forEach { value.add(it.key, it.value) }
                value.add("time", time)
                checkCancelled()
                JsonJournal(path(id)).write(value)
            }
        } finally { release() }
    }
    fun remove(id: String) = synchronized(lock(id)) {
        val release = NativeFileLock.acquire(path(id).resolveSibling("$id.json.lock"))
        try { Files.deleteIfExists(path(id)) } finally { release() }
    }

    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
