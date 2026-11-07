package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Reads the server-wide job registry and assigns work using native session ancestry. */
internal class OpenCodeV2BackgroundJobs {
    private var version: String? = null
    private var unavailable = false
    private var generation = 0L

    @Synchronized fun setServerVersion(value: String?) {
        val next = value?.let { Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?").find(it)?.value }
        if (next != version) { unavailable = false; generation++ }
        version = next
    }
    @Synchronized fun reconnect() { unavailable = false; generation++ }
    @Synchronized fun reset() { version = null; reconnect() }

    fun read(load: (String) -> JsonElement, directory: String?): Map<String, List<JsonObject>>? {
        val current = synchronized(this) {
            val release = version ?: return null
            val comparison = OpenCodeCli.compareVersions(release, "2.2.6")
            if (unavailable || comparison < 0 || (comparison == 0 && '-' in release)) return null
            generation
        }
        val response = try { load("/api/experimental/job") } catch (failure: Exception) {
            if (!failure.message.orEmpty().startsWith("404 ")) throw failure
            synchronized(this) { if (current == generation) unavailable = true }
            return null
        }
        val jobs = OpenCodeV2Adapter.objects(response)
        for (job in jobs) {
            fun string(key: String) = job.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true
            require(string("id") && string("sessionID") && job.str("status") in setOf("running", "completed", "error", "cancelled") &&
                job.obj("time").num("started")?.isFinite() == true && (!job.has("title") || string("title")) && when (job.str("kind")) {
                    "shell" -> string("shellID") && string("command")
                    "subagent" -> string("childSessionID") && string("agent") && string("description")
                    "image" -> string("path") && string("model")
                    else -> false
                }) { "Invalid OpenCode v2 background job" }
        }
        val sessions = mutableMapOf<String, JsonObject?>()
        fun session(id: String): JsonObject? {
            if (sessions.containsKey(id)) return sessions[id]
            val info = try { load("/api/session/${OpenCodeV2Adapter.encode(id)}").asObjectOrNull() } catch (failure: Exception) {
                if (!failure.message.orEmpty().startsWith("404 ")) throw failure
                sessions[id] = null
                return null
            }
            require(info.str("id") == id && info.obj("location").text("directory") != null &&
                (!info!!.has("parentID") || info.get("parentID").let { it.isJsonPrimitive && it.asJsonPrimitive.isString })) {
                "Invalid OpenCode v2 background-job session"
            }
            sessions[id] = info
            return info
        }
        val grouped = mutableMapOf<String, MutableList<JsonObject>>()
        for (job in jobs.filter { it.str("status") != "cancelled" }) {
            var id = job.str("sessionID")
            val seen = mutableSetOf<String>()
            while (id != null && seen.add(id)) {
                val info = session(id) ?: break
                if (directory == null || WorkspacePaths.isSame(info.obj("location").str("directory")!!, directory))
                    grouped.getOrPut(id) { mutableListOf() }.add(job)
                id = info.str("parentID")
            }
        }
        return grouped
    }
}
