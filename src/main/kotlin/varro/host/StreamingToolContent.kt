package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Routing-only state; original tool bodies stay in OpenCode rather than the browser store. */
internal class StreamingToolContent {
    private data class Tool(val name: String, val messageID: String?, var inputCharacters: Int)
    private val tools = LinkedHashMap<String, Tool>()

    @Synchronized fun clear() = tools.clear()

    @Synchronized fun project(event: JsonObject): JsonObject {
        val type = event.str("type") ?: return event
        if (!type.startsWith("session.next.tool.") || event.bool("sequenceOnly") == true) return event
        val source = event.obj("properties") ?: return event
        val sessionID = source.str("sessionID") ?: return event
        val callID = source.str("callID") ?: return event
        val directory = event.str("workspaceDirectory")
        val key = "$directory\u0000$sessionID\u0000$callID"
        val previous = tools.remove(key)
        val tool = Tool(source.text("name") ?: source.text("tool") ?: previous?.name.orEmpty(),
            source.text("assistantMessageID") ?: previous?.messageID,
            if (type.endsWith("input.started")) 0 else previous?.inputCharacters ?: 0)
        tools[key] = tool
        while (tools.size > 1024) tools.remove(tools.keys.first())
        val properties = Json.obj("sessionID" to sessionID, "callID" to callID,
            "assistantMessageID" to tool.messageID, "timestamp" to source.get("timestamp"))
        for (field in listOf("name", "tool")) if (source.has(field)) properties.addProperty(field, tool.name)
        val identity = Json.obj("id" to callID, "sessionID" to sessionID, "messageID" to tool.messageID)
        val reference = MessageContent.path(identity, directory)?.let(Json::toElement) ?: Json.toElement(true)
        fun summarize(state: JsonObject): JsonObject {
            val part = MessageContent.project(identity.deepCopy().apply {
                addProperty("type", "tool"); addProperty("tool", tool.name); add("state", state)
            }, directory)
            if (part.has("deferred")) properties.add("deferred", reference)
            return part.obj("state") ?: state
        }
        fun field(state: JsonObject, name: String) { state.get(name)?.let { properties.add(name, it) } }
        val time = Json.obj("start" to 0, "end" to 0)
        when (type) {
            "session.next.tool.input.started" -> return event
            "session.next.tool.input.delta" -> {
                val delta = source.str("delta") ?: source.str("text") ?: source.str("input").orEmpty()
                val fragment = delta.take(maxOf(0, 512 - tool.inputCharacters))
                tool.inputCharacters += fragment.length
                properties.addProperty("delta", fragment)
                if (fragment.length != delta.length) properties.add("deferred", reference)
            }
            "session.next.tool.input.ended" -> {
                val raw = source.str("text") ?: source.str("input").orEmpty()
                val state = summarize(Json.obj("status" to "pending", "raw" to raw, "input" to parseInput(source.get("text") ?: source.get("input"))))
                properties.addProperty("text", if (properties.has("deferred")) state.obj("input").toString() else raw)
            }
            "session.next.tool.called" -> {
                val state = summarize(Json.obj("status" to "running", "input" to parseInput(source.get("input")),
                    "title" to (source.str("title") ?: tool.name), "metadata" to source.obj("provider"), "time" to time))
                field(state, "input"); field(state, "title"); properties.add("provider", state.get("metadata"))
            }
            "session.next.tool.progress" -> {
                val metadata = source.obj("structured")?.deepCopy() ?: Json.obj()
                metadata.add("structured", source.get("structured"))
                metadata.add("content", textContent(source.get("content")))
                metadata.add("progress", source.get("progress"))
                val state = summarize(Json.obj("status" to "running", "input" to Json.obj(), "metadata" to metadata, "time" to time))
                for (name in listOf("structured", "content", "progress")) state.obj("metadata")?.get(name)?.let { properties.add(name, it) }
            }
            "session.next.tool.success" -> {
                val content = textContent(source.get("content"))
                val output = content.mapNotNull { it.asObjectOrNull().str("text") }.joinToString("\n").ifEmpty {
                    source.str("output") ?: source.obj("structured")?.toString().orEmpty()
                }
                val metadata = source.obj("structured")?.deepCopy() ?: Json.obj()
                metadata.add("provider", source.get("provider")); metadata.add("result", source.get("result"))
                val state = summarize(Json.obj("status" to "completed", "input" to Json.obj(), "title" to tool.name,
                    "output" to output, "metadata" to metadata, "time" to time))
                properties.add("content", Json.array(listOf(Json.obj("type" to "text", "text" to state.str("output")))))
                val summary = state.obj("metadata")?.deepCopy() ?: Json.obj()
                properties.add("provider", summary.remove("provider")); properties.add("result", summary.remove("result"))
                properties.add("structured", summary)
            }
            "session.next.tool.failed" -> {
                val state = summarize(Json.obj("status" to "error", "input" to Json.obj(), "time" to time,
                    "error" to (source.str("error") ?: source.obj("error").str("message") ?: "Tool execution failed"),
                    "metadata" to Json.obj("provider" to source.get("provider"), "result" to source.get("result"))))
                field(state, "error")
                for (name in listOf("provider", "result")) state.obj("metadata")?.get(name)?.let { properties.add(name, it) }
            }
            else -> return event
        }
        return JsonObject().apply {
            event.entrySet().forEach { add(it.key, it.value) }
            add("properties", properties)
        }
    }

    private fun parseInput(value: JsonElement?): JsonObject = value.asObjectOrNull()
        ?: value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let(Json::parseOrNull).asObjectOrNull() ?: Json.obj()

    private fun textContent(value: JsonElement?) = Json.array(value.asArrayOrNull()?.mapNotNull {
        val item = it.asObjectOrNull() ?: return@mapNotNull null
        val text = if (item.str("type") == "file") item.str("uri")?.takeUnless { uri -> uri.startsWith("data:") }
            else if (item.str("type") == "text") item.str("text") else null
        text?.let { Json.obj("type" to "text", "text" to it) }
    }.orEmpty())
}
