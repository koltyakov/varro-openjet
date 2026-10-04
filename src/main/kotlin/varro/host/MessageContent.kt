package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import varro.protocol.*
import java.net.URLEncoder

/** Browser-only projections. The server's originals remain authoritative for previews and edits. */
internal object MessageContent {
    fun path(part: JsonObject, directory: String?): String? {
        val session = part.text("sessionID") ?: return null
        val message = part.text("messageID") ?: return null
        val id = part.text("id") ?: return null
        val route = "/session/${encode(session)}/message/${encode(message)}/part/${encode(id)}"
        return route + if (directory.isNullOrBlank()) "" else "?directory=${encode(directory)}"
    }

    fun messages(value: JsonElement?, directory: String?): JsonElement? = value.asArrayOrNull()?.let { entries ->
        Json.array(entries.map { entry ->
            val message = entry.asObjectOrNull() ?: return@map entry
            val scope = message.obj("info").obj("path").str("cwd")?.takeIf(String::isNotBlank) ?: directory
            copy(message).apply {
                message.arr("parts")?.let { parts -> add("parts", Json.array(parts.map {
                    it.asObjectOrNull()?.let { part -> project(part, scope) } ?: it
                })) }
            }
        })
    } ?: value

    fun attachments(part: JsonObject, directory: String?): JsonObject {
        val state = part.obj("state") ?: return part
        val attachments = state.arr("attachments") ?: return part
        return copy(part).apply { add("state", copy(state).apply {
            add("attachments", Json.array(attachments.map { it.asObjectOrNull()?.let { file -> project(file, directory) } ?: it }))
        }) }
    }

    fun project(part: JsonObject, directory: String?): JsonObject {
        val path = path(part, directory) ?: return part
        if (part.str("type") == "file" && part.str("url") != null) return copy(part).apply {
            addProperty("url", "varro-content:$path")
        }
        if (part.str("type") == "reasoning" && part.obj("time")?.hasNonNull("end") == true && part.str("text").orEmpty().length > 512) {
            return copy(part).apply {
                addProperty("text", part.str("text")!!.take(512)); remove("metadata"); addProperty("deferred", path)
            }
        }
        if (part.str("type") != "tool") return part
        val projected = attachments(part, directory)
        val state = projected.obj("state") ?: return projected
        val name = part.str("tool").orEmpty().trim().lowercase().substringAfterLast('.')
        // These fields drive visible answers, todos and file-change summaries. Keep their
        // full schemas until the Kotlin host has the shared file-change parser's entire contract.
        if (name == "question" || name.contains("todo") || name in STRUCTURED_TOOLS) return projected
        val summary = Summary()
        val next = copy(state)
        for (key in listOf("input", "metadata", "error", "raw", "title")) {
            state.get(key)?.let { next.add(key, summary.project(it)) }
        }
        val output = state.str("output")
        if (output != null) next.addProperty("output", output.take(512))
        val metadata = part.get("metadata")?.let(summary::project)
        if (!summary.omitted && (output?.length ?: 0) <= 512) return projected
        searchCount(name, state)?.let { count ->
            next.add("metadata", (next.obj("metadata") ?: Json.obj()).apply {
                count.entrySet().forEach { add(it.key, it.value) }
            })
        }
        return copy(projected).apply {
            addProperty("deferred", path)
            add("state", next)
            if (metadata != null) add("metadata", metadata)
        }
    }

    fun find(message: JsonObject, sessionID: String, messageID: String, partID: String): JsonObject? {
        val parts = message.arr("parts")?.mapNotNull { it.asObjectOrNull() }.orEmpty()
        val candidates = parts + parts.flatMap { it.obj("state").arr("attachments")?.mapNotNull { file -> file.asObjectOrNull() }.orEmpty() }
        return candidates.firstOrNull { it.str("id") == partID && it.str("messageID") == messageID && it.str("sessionID") == sessionID }
    }

    private fun searchCount(name: String, state: JsonObject): JsonObject? {
        if (name !in setOf("grep", "glob", "codesearch", "websearch", "search") || state.str("status") != "completed") return null
        val output = state.str("output").orEmpty()
        val metadata = state.obj("metadata")
        val count = metadata.int("matches") ?: metadata.int("count")
            ?: Regex("^\\s*Found\\s+(\\d+)\\s+(?:matches|files|results)\\b", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
                .find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (Regex("^\\s*No (?:files|matches|search results?) found\\b", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(output)) 0
            else if (name == "glob") output.lineSequence().count { it.isNotBlank() && !it.startsWith("(Results are truncated") } else return null
        return Json.obj("matches" to count, "truncated" to (metadata.bool("truncated") == true ||
            Regex("more matches available|results (?:are )?truncated", RegexOption.IGNORE_CASE).containsMatchIn(output)))
    }

    private class Summary {
        var omitted = false
        private var remaining = 16 * 1024
        private var nodes = 512
        fun project(value: JsonElement, key: String = "", depth: Int = 0): JsonElement {
            if (nodes-- <= 0 || depth > 12 || remaining <= 0) { omitted = true; return JsonNull.INSTANCE }
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val text = value.asString
                val limit = if (PATH_KEY.matches(key)) 4096 else 512
                val truncated = if (BINARY.containsMatchIn(text)) "" else text.take(minOf(limit, remaining))
                remaining -= truncated.length
                if (truncated.length != text.length) omitted = true
                return JsonPrimitive(truncated)
            }
            if (value.isJsonArray) return JsonArray().apply {
                for (item in value.asJsonArray) {
                    if (nodes <= 0 || remaining <= 0) { omitted = true; break }
                    add(project(item, depth = depth + 1))
                }
            }
            if (value.isJsonObject) return JsonObject().apply {
                for ((field, item) in value.asJsonObject.entrySet()) {
                    if (nodes <= 0 || remaining <= 0 || field.length > remaining) { omitted = true; break }
                    remaining -= field.length
                    add(field, project(item, field, depth + 1))
                }
            }
            return value
        }
    }

    /** Shallow copy avoids copying multi-megabyte bodies immediately before omitting them. */
    private fun copy(value: JsonObject) = JsonObject().apply { value.entrySet().forEach { add(it.key, it.value) } }
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    private val PATH_KEY = Regex("^(?:.*path|filename|directory|sessionID|sessionId|task_id)$", RegexOption.IGNORE_CASE)
    private val BINARY = Regex("^data:[^,]*;base64,", RegexOption.IGNORE_CASE)
    private val STRUCTURED_TOOLS = setOf("update_plan", "updateplan", "apply_patch", "edit", "write", "create", "delete", "rename", "patch",
        "file_edit", "file_write", "file_create", "update_file", "replace", "insert", "apply_edit", "apply_diff", "remove", "unlink", "rm",
        "file_delete", "file_remove", "move", "mv", "file_move", "file_rename")
}
