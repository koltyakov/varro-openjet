package varro.host

import com.google.gson.JsonElement
import varro.server.OpenCodeResponse
import varro.server.OpenCodeV2SideQuestions
import varro.server.RequestOptions
import varro.server.WorkspacePaths
import java.util.concurrent.TimeUnit

/** A bounded request, with no prompt admission, helper session or lifecycle fallback. */
internal class SideQuestions(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val apiVersion: () -> Int,
    private val running: () -> Boolean,
    private val connectionKey: () -> Any,
    private val workspaceRoots: () -> List<String>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun ask(path: String, body: JsonElement?, directory: String?, isCancelled: () -> Boolean): JsonElement? {
        check(apiVersion() == 2) { "Side questions require OpenCode V2" }
        check(running()) { "Connect to OpenCode before asking a side question" }
        require(directory != null && workspaceRoots().any { WorkspacePaths.isSame(it, directory) }) { "404 Session not found" }
        val key = connectionKey()
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(OpenCodeV2SideQuestions.TIMEOUT_MS)
        val options = RequestOptions(directory = directory, timeoutMs = OpenCodeV2SideQuestions.TIMEOUT_MS,
            maxResponseBytes = 512 * 1024, cancellableBody = true, isCancelled = {
                check(key == connectionKey() && running() && apiVersion() == 2) { "Connection changed while asking the side question" }
                check(nanoTime() < deadline) { "Side question timed out" }
                isCancelled()
            })
        options.checkCancelled()
        val result = request("POST", path, body, options).data
        options.checkCancelled()
        return result
    }
}
