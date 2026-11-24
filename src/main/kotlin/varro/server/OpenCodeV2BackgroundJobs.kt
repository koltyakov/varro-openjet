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
        val jobs = parse(response)
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

    /**
     * Cancels a waiting session's running native jobs, including descendant sessions' jobs, and returns the shells
     * they owned. Cancelled jobs settle without a completion notice, so Stop does not wake the session again.
     * Services keep running, as they do with shell-only Stop. Servers before 2.4.0 keep shell deletion.
     */
    fun cancel(sessionID: String, load: (String) -> JsonElement, post: (String) -> Unit, isService: (JsonObject) -> Boolean): Set<String> {
        synchronized(this) {
            val release = version ?: return emptySet()
            val comparison = OpenCodeCli.compareVersions(release, "2.4.0")
            if (unavailable || comparison < 0 || (comparison == 0 && '-' in release)) return emptySet()
        }
        val target = "/api/experimental/job?sessionID=${OpenCodeV2Adapter.encode(sessionID)}&descendants=true&status=running"
        val jobs = try { parse(load(target)).filter { it.str("status") == "running" } } catch (failure: Exception) {
            // A deleted session or unavailable route leaves the shell-only fallback.
            if (failure.message.orEmpty().startsWith("404 ")) return emptySet()
            throw failure
        }
        val cancelled = mutableSetOf<String>()
        var failure: Exception? = null
        for (job in jobs.filterNot { it.str("kind") == "shell" && isService(it) }) {
            try {
                post("/api/experimental/job/${OpenCodeV2Adapter.encode(job.str("id")!!)}/cancel")
                if (job.str("kind") == "shell") cancelled += job.str("shellID")!!
            } catch (error: Exception) {
                if (!error.message.orEmpty().startsWith("404 ") && failure == null) failure = error
            }
        }
        failure?.let { throw it }
        return cancelled
    }

    private fun parse(response: JsonElement): List<JsonObject> = OpenCodeV2Adapter.objects(response).onEach { job ->
        fun string(key: String) = job.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true
        require(string("id") && string("sessionID") && job.str("status") in setOf("running", "completed", "error", "cancelled") &&
            job.obj("time").num("started")?.isFinite() == true && (!job.has("title") || string("title")) && when (job.str("kind")) {
                "shell" -> string("shellID") && string("command")
                "subagent" -> string("childSessionID") && string("agent") && string("description")
                "image" -> string("path") && string("model")
                "video" -> string("path")
                "codemode" -> true
                else -> false
            }) { "Invalid OpenCode v2 background job" }
    }
}
