package varro.server

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal class RestartPreflight(
    private val globalStatus: Boolean,
    private val observedDirectories: Map<String, String>,
    private val pending: () -> Set<String>,
    private val request: (String, RequestOptions) -> OpenCodeResponse,
) {
    fun read(): JsonObject {
        val directories = observedDirectories.toMutableMap()
        val blocking = mutableSetOf<String>()
        fun snapshot(directory: String?) {
            val options = RequestOptions(directory = directory, unscoped = directory == null)
            val statuses = request("/session/status", options).data.asObjectOrNull()
                ?: error("Invalid OpenCode session status")
            statuses.entrySet().forEach { (id, value) ->
                val type = value.asObjectOrNull().str("type")
                require(id.isNotBlank() && type in setOf("idle", "busy", "retry")) { "Invalid OpenCode session status" }
                if (directory != null) directories.putIfAbsent(id, directory)
                if (type != "idle") blocking.add(id)
            }
            for (route in listOf("/question", "/permission")) {
                val attention = request(route, options).data
                require(attention?.isJsonArray == true) { "Invalid OpenCode pending attention" }
                attention.asJsonArray.forEach {
                    val id = it.asObjectOrNull().str("sessionID")?.takeIf(String::isNotBlank)
                        ?: error("Invalid OpenCode pending attention session")
                    if (directory != null) directories.putIfAbsent(id, directory)
                    blocking.add(id)
                }
            }
        }
        snapshot(null)
        val inventory = request("/experimental/session?limit=10000", RequestOptions(unscoped = true)).data
        require(inventory?.isJsonArray == true && inventory.asJsonArray.size() < 10000) { "Invalid or oversized OpenCode session inventory" }
        inventory.asJsonArray.forEach {
            val session = it.asObjectOrNull()
            val id = session.str("id")?.takeIf(String::isNotBlank) ?: error("Invalid session ID")
            directories[id] = session.str("directory")?.takeIf(String::isNotBlank) ?: error("Invalid session directory")
        }
        directories.values.toSet().forEach { directory ->
            if (globalStatus) {
                try { Files.readAttributes(Path.of(directory), BasicFileAttributes::class.java) }
                catch (_: NoSuchFileException) { return@forEach }
            }
            snapshot(directory)
        }
        blocking.addAll(pending())
        return Json.obj("totalSessionCount" to blocking.size, "directories" to Json.array(
            blocking.groupingBy { directories[it] ?: "Unknown directory" }.eachCount().map { (directory, count) ->
                Json.obj("directory" to directory, "sessionCount" to count)
            },
        ))
    }
}
