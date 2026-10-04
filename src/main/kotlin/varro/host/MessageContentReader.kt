package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.RequestOptions

internal class MessageContentReader(
    private val thumbnails: ImageThumbnails,
    private val requestMessage: (String, RequestOptions) -> JsonElement?,
) {
    fun read(request: ApiRoutes.Request, directory: String?, serverIdentity: String, isCancelled: () -> Boolean): JsonElement {
        val options = RequestOptions(directory = directory, isCancelled = isCancelled, maxResponseBytes = 256L * 1024 * 1024)
        fun original(): JsonObject {
            options.checkCancelled()
            val messagePath = "/" + request.rawSegments.take(4).joinToString("/")
            val message = requestMessage(messagePath, options).asObjectOrNull() ?: error("404 Message content not found")
            options.checkCancelled()
            return MessageContent.find(message, request.segments[1], request.segments[3], request.segments[5])
                ?: error("404 Message content not found")
        }
        options.checkCancelled()
        return if (request.query["view"] == listOf("thumbnail")) {
            val key = "$serverIdentity\u0000$directory\u0000${request.pathname}"
            Json.obj("url" to thumbnails.get(key, {
                original().takeIf { it.str("type") == "file" }?.str("url")
            }, isCancelled))
        } else MessageContent.attachments(original(), directory)
    }
}
