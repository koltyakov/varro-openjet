package varro.server

import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.JsonJournal
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Same per-session annotation files as Varro's v2 adapter. */
internal class OpenCodeV2SessionState(private val root: Path = Path.of(
    System.getenv("XDG_STATE_HOME") ?: Path.of(System.getProperty("user.home"), ".local", "state").toString(), "varro", "opencode-v2",
)) {
    private fun path(id: String): Path {
        require(Regex("[A-Za-z0-9_-]{1,256}").matches(id)) { "Invalid session ID" }
        return root.resolve("$id.json")
    }
    private fun lock(id: String) = locks.computeIfAbsent(root.resolve(id).toString()) { Any() }
    fun read(id: String): JsonObject = synchronized(lock(id)) { JsonJournal(path(id)).read(Json.obj()) }
    fun update(id: String, patch: JsonObject) = synchronized(lock(id)) {
        val value = read(id)
        val time = (value.obj("time") ?: Json.obj()).deepCopy()
        patch.obj("time")?.entrySet()?.forEach { time.add(it.key, it.value) }
        patch.entrySet().forEach { value.add(it.key, it.value) }
        value.add("time", time)
        JsonJournal(path(id)).write(value)
    }
    fun remove(id: String) = synchronized(lock(id)) { java.nio.file.Files.deleteIfExists(path(id)) }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
