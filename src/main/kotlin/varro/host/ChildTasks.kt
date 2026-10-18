package varro.host

import com.google.gson.JsonElement
import varro.protocol.*
import varro.server.*
import java.util.concurrent.TimeUnit

internal class ChildTasks(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val apiVersion: () -> Int,
    private val connectionKey: () -> Any,
    private val roots: () -> List<String>,
    private val hidden: (String) -> Boolean,
) {
    fun request(method: String, path: String, body: JsonElement?, directory: String?, cancelled: () -> Boolean): JsonElement? {
        require(body == null) { "Child task controls do not accept a body" }
        val parsed = ApiRoutes.parse(method, path) ?: error("Invalid child task route")
        val id = parsed.segments[1]
        require(!hidden(id)) { "404 Session not found" }
        if (apiVersion() != 2) {
            require(method == "GET") { "Child task controls require OpenCode V2" }
            return Json.obj("parentID" to null, "stopChild" to false, "backgroundEligible" to false)
        }
        val key = connectionKey()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val options = RequestOptions(directory = directory, timeoutMs = 15_000, maxResponseBytes = 2 * 1024 * 1024,
            cancellableBody = true, isCancelled = {
                check(key == connectionKey() && apiVersion() == 2) { "Connection changed while managing child work. Refresh before retrying." }
                check(System.nanoTime() < deadline) { "Child task request timed out" }
                cancelled()
            })
        options.checkCancelled()
        val snapshot = request("GET", "/session/${OpenCodeV2Adapter.encode(id)}/child-task-controls", null, options).data.asObjectOrNull()
        val canonicalDirectory = snapshot.str("directory")
        require(canonicalDirectory != null && roots().any { WorkspacePaths.isSame(it, canonicalDirectory) }) { "404 Session not found" }
        snapshot.str("parentID")?.let { require(!hidden(it)) { "404 Session not found" } }
            ?: require(method == "GET") { "Child task controls require a native child" }
        options.checkCancelled()
        val result = if (method == "GET") snapshot else request(method, path, null, options).data
        options.checkCancelled()
        return result
    }
}
