package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import varro.protocol.Json

internal object WorkspaceDirectoryErrors {
    private val missing = Regex("^404\\b[\\s\\S]*\\blocation not found\\b", RegexOption.IGNORE_CASE)
    fun isMissing(message: String?): Boolean = missing.containsMatchIn(message.orEmpty())
    fun message(directory: String): String = "Workspace folder unavailable: $directory. It may have been deleted or renamed. Restore the folder or reopen the workspace at its new location. The conversation has not been deleted or relocated."

    fun recover(method: String, path: String, directory: String, failure: Exception): JsonElement {
        if (method != "GET" || !isMissing(failure.message)) throw failure
        val request = ApiRoutes.parse(method, path) ?: throw failure
        if (request.pathname == "/session" && request.query.containsKey("limit")) return Json.obj(
            "items" to JsonArray(), "hasMore" to false, "incomplete" to true, "unavailableDirectories" to listOf(directory),
        )
        if (request.pathname == "/session" || request.pathname == "/session/status" || request.pathname.startsWith("/session/")) {
            throw IllegalStateException(message(directory), failure)
        }
        throw failure
    }
}
