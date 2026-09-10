package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.*

object SessionTranscript {
    fun render(session: JsonObject, messages: JsonArray): String = buildString {
        appendLine("# ${session.str("title")?.replace('\n', ' ') ?: "OpenCode session"}")
        appendLine()
        appendLine("Session: `${session.str("id").orEmpty()}`")
        session.str("directory")?.let { appendLine("Workspace: `$it`") }
        appendLine()
        messages.forEach { entry ->
            val message = entry.asObjectOrNull() ?: return@forEach
            val info = message.obj("info")
            appendLine("## ${if (info.str("role") == "user") "User" else "Assistant"}")
            appendLine()
            info.str("modelID")?.let { appendLine("Model: ${info.str("providerID").orEmpty()}/$it\n") }
            message.arr("parts")?.forEach { value ->
                val part = value.asObjectOrNull()
                when (part.str("type")) {
                    "text" -> appendLine(part.str("text").orEmpty())
                    "reasoning" -> {
                        appendLine("<details><summary>Reasoning</summary>\n")
                        appendLine(part.str("text").orEmpty())
                        appendLine("\n</details>")
                    }
                    "file" -> appendLine("Attachment: ${part.str("filename") ?: part.str("mime") ?: "file"}\n\n${part.str("url").orEmpty()}")
                    "tool" -> {
                        val state = part.obj("state")
                        appendLine("### Tool: ${part.str("tool").orEmpty()}\n")
                        appendLine("Status: ${state.str("status").orEmpty()}\n")
                        state?.get("input")?.let { appendLine(fence(Json.stringify(it), "json")) }
                        (state.str("output") ?: state.str("error"))?.let { appendLine(fence(it)) }
                    }
                    "patch" -> part.arr("files")?.strings()?.forEach { appendLine("- Changed: `$it`") }
                }
                appendLine()
            }
            info?.get("error")?.takeUnless { it.isJsonNull }?.let { appendLine("Error:\n\n${fence(Json.stringify(it))}\n") }
            appendLine("---\n")
        }
    }

    private fun fence(text: String, language: String = ""): String {
        val length = maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1)
        val delimiter = "`".repeat(length)
        return "$delimiter$language\n$text\n$delimiter"
    }
}
