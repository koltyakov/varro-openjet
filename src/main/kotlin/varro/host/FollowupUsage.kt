package varro.host

import com.google.gson.JsonElement
import varro.protocol.*
import varro.server.*

/** Authorize the canonical session before reading any inbox or transcript records. */
internal class FollowupUsage(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val apiVersion: () -> Int,
    private val connectionKey: () -> Any,
    private val roots: () -> List<String>,
    private val hidden: (String) -> Boolean,
    private val authorizeSessionDirectory: (String, String) -> Boolean = { _, _ -> false },
) {
    fun read(id: String, body: JsonElement?, directory: String?, cancelled: () -> Boolean): JsonElement? {
        require(body == null) { "Follow-up usage does not accept a body" }
        require(!hidden(id)) { "404 Session not found" }
        val key = connectionKey()
        val version = apiVersion()
        val options = RequestOptions(directory = directory, timeoutMs = 30_000,
            deadlineNanos = System.nanoTime() + 30_000_000_000L, maxResponseBytes = OpenCodeV2FollowupUsage.MAX_BYTES,
            isCancelled = {
                check(key == connectionKey() && version == apiVersion()) { "Connection changed while reading follow-up usage" }
                cancelled()
            })
        options.checkCancelled()
        val path = "/session/${OpenCodeV2Adapter.encode(id)}"
        val session = request("GET", path, null, options).data.asObjectOrNull()
        val canonical = session.text("directory")
        require(session.str("id") == id && canonical != null &&
            (directory == null || WorkspacePaths.isSame(canonical, directory)) &&
            (roots().any { WorkspacePaths.isSame(it, canonical) } || authorizeSessionDirectory(id, canonical))) { "404 Session not found" }
        options.checkCancelled()
        val result = if (version == 2) request("GET", "$path/followup-usage", null, options.copy(directory = canonical)).data
            else Json.obj("supported" to false, "complete" to false, "intervals" to emptyList<Any>(), "pendingPromptIDs" to emptyList<String>())
        options.checkCancelled()
        return result
    }
}
