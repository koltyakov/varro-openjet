package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

internal fun com.google.gson.JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
internal fun com.google.gson.JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null

/** Released v2 records projected into the shared Varro transcript contract. */
internal object OpenCodeV2Projection {
    fun legacyAction(action: String?) = when (action) { "shell" -> "bash"; "subagent" -> "task"; else -> action }
    fun nativeAction(action: String?) = when (action) { "bash" -> "shell"; "task" -> "subagent"; "write", "patch" -> "edit"; else -> action }
    fun rules(value: JsonElement?): JsonElement = Json.array(value.asArrayOrNull()?.map { entry ->
        val rule = entry.asObjectOrNull() ?: error("Invalid OpenCode permission rule")
        val action = rule.str("permission") ?: rule.str("action") ?: error("Missing permission action")
        val resource = rule.str("pattern") ?: rule.str("resource") ?: error("Missing permission resource")
        val effect = if (rule.has("permission")) rule.str("action") else rule.str("effect")
        require(effect in setOf("allow", "deny", "ask")) { "Invalid permission effect" }
        Json.obj("action" to nativeAction(action), "resource" to resource, "effect" to effect)
    } ?: error("Invalid OpenCode permission rules"))

    fun legacyRules(value: JsonElement?) = Json.array(value.asArrayOrNull().orEmpty().map { entry ->
        val rule = entry.asObjectOrNull()
        Json.obj("permission" to legacyAction(rule.str("action")), "pattern" to rule?.get("resource"), "action" to rule?.get("effect"))
    })

    fun modelRef(value: JsonElement?): JsonObject? {
        if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
            val match = Regex("^([^/]+)/(.+?)(?:#([^#]+))?$").matchEntire(value.asString) ?: return null
            return Json.obj("providerID" to match.groupValues[1], "id" to match.groupValues[2],
                "variant" to match.groupValues[3].ifEmpty { null })
        }
        val model = value.asObjectOrNull() ?: return null
        return Json.obj("providerID" to (model.str("providerID") ?: return null),
            "id" to (model.str("modelID") ?: model.str("id") ?: model.str("model") ?: return null), "variant" to model.str("variant"))
    }

    fun session(value: JsonObject) = value.deepCopy().apply {
        addProperty("version", "2")
        addProperty("sharingSupported", false)
        addProperty("title", value.str("title").orEmpty())
        addProperty("directory", value.obj("location").str("directory"))
        add("permission", legacyRules(value.get("permissions")))
        value.obj("model")?.let { add("model", it.deepCopy().apply { add("modelID", it.get("id")) }) }
    }

    fun permission(value: JsonObject) = value.deepCopy().apply {
        addProperty("permission", legacyAction(value.str("action")))
        add("patterns", value.get("resources"))
        add("always", value.get("save") ?: value.get("resources"))
        add("metadata", value.obj("metadata") ?: Json.obj())
        value.obj("source")?.let { add("tool", Json.obj("messageID" to it.get("messageID"), "callID" to (it.get("callID") ?: it.get("id")))) }
    }

    fun agent(value: JsonObject) = value.deepCopy().apply {
        add("name", value.get("id")); add("prompt", value.get("system"))
        value.obj("model")?.let { add("model", it.deepCopy().apply { add("modelID", it.get("id")) }); add("variant", it.get("variant")) }
        add("permission", legacyRules(value.get("permissions")))
        add("options", value.obj("request").obj("body") ?: Json.obj())
    }

    fun model(value: JsonObject) = value.deepCopy().apply {
        val costs = value.arr("cost").orEmpty().mapNotNull { it.asObjectOrNull() }
        val cost = costs.firstOrNull { !it.hasNonNull("tier") } ?: costs.firstOrNull()
        add("api", Json.obj("id" to value.get("modelID"), "npm" to value.str("package").orEmpty(), "url" to ""))
        add("cost", Json.obj("input" to (cost.num("input") ?: 0), "output" to (cost.num("output") ?: 0),
            "cache_read" to (cost.obj("cache").num("read") ?: 0), "cache_write" to (cost.obj("cache").num("write") ?: 0),
            "cache" to cost.obj("cache"), "tiers" to costs.filter { it.hasNonNull("tier") }))
        add("variants", JsonObject().apply { value.arr("variants").orEmpty().forEach { v -> v.asObjectOrNull().str("id")?.let { add(it, v) } } })
        add("options", value.obj("settings") ?: Json.obj())
        value.obj("time").long("released")?.takeIf { it > 0 }?.let {
            addProperty("release_date", java.time.Instant.ofEpochMilli(it).toString().take(10))
        }
    }

    fun form(value: JsonObject) = Json.obj("id" to value.get("id"), "sessionID" to value.get("sessionID"),
        "questions" to value.arr("fields").orEmpty().map { entry ->
            val field = entry.asObjectOrNull()
            Json.obj("header" to (field.str("title") ?: value.str("title")),
                "question" to (field.str("description") ?: field.str("title") ?: value.str("title")),
                "multiple" to (field.str("type") == "multiselect"), "custom" to (field.bool("custom") != false),
                "options" to (field.arr("options")?.map { option -> Json.obj("label" to option.asObjectOrNull().str("label"),
                    "description" to option.asObjectOrNull().str("description").orEmpty()) }
                    ?: if (field.str("type") == "boolean") listOf(Json.obj("label" to "Yes", "description" to ""), Json.obj("label" to "No", "description" to "")) else emptyList()))
        })

    fun transcript(message: JsonObject) = message.str("type") in setOf("user", "assistant", "compaction", "skill", "shell") ||
        (message.str("type") == "idle" && message.str("outcome") == "failed")

    fun toolOutput(value: JsonElement?) = value.asArrayOrNull().orEmpty().mapNotNull { it.asObjectOrNull() }
        .filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }

    fun message(value: JsonObject, sessionID: String, directory: String = "", parentID: String = "", context: JsonObject = Json.obj()): JsonObject {
        val id = value.str("id").orEmpty()
        val type = value.str("type")
        val time = value.obj("time") ?: Json.obj()
        fun part(ordinal: Int, kind: String, fields: JsonObject) = Json.obj("id" to "$id:content:$ordinal", "sessionID" to sessionID,
            "messageID" to id, "type" to kind).apply { fields.entrySet().forEach { add(it.key, it.value) } }
        if (type == "shell" || type == "skill") {
            val completed = type == "skill" || value.str("status") != "running"
            val output = if (type == "skill") value.str("text") else value.obj("output").str("output")
            val failure = when {
                value.str("status") in setOf("timeout", "killed") -> "Shell ${value.str("status") }"
                value.str("status") == "exited" && (value.int("exit") ?: 0) != 0 -> "Shell exited with code ${value.int("exit") }"
                else -> null
            }
            val toolTime = Json.obj("created" to time.get("created"), "completed" to if (completed) time.get("completed") ?: time.get("created") else null)
            return message(Json.obj("id" to id, "type" to "assistant", "agent" to context.str("agent").orEmpty(),
                "model" to (context.obj("model") ?: Json.obj("providerID" to "", "id" to "")), "time" to toolTime,
                "content" to listOf(Json.obj("type" to "tool", "id" to (value.str("shellID") ?: "$id:content:0"), "name" to type,
                    "time" to toolTime, "state" to Json.obj("status" to if (failure != null) "error" else if (completed) "completed" else "running",
                        "input" to if (type == "skill") Json.obj("name" to value.str("skill")) else Json.obj("command" to value.str("command")),
                        "error" to failure?.let { Json.obj("message" to listOfNotNull(output, it).joinToString("\n")) },
                        "content" to listOf(Json.obj("type" to "text", "text" to output)), "metadata" to Json.obj("output" to output))))), sessionID, directory, parentID, context)
        }
        val assistant = type == "assistant" || type == "idle"
        val model = value.obj("model") ?: context.obj("model")
        val info = Json.obj("id" to id, "sessionID" to sessionID, "time" to time, "role" to if (assistant) "assistant" else "user",
            "agent" to (value.str("agent") ?: context.str("agent").orEmpty()))
        val parts = mutableListOf<JsonObject>()
        if (assistant) {
            info.addProperty("parentID", parentID)
            info.add("mode", info.get("agent"))
            info.addProperty("providerID", model.str("providerID").orEmpty())
            info.addProperty("modelID", model.str("id").orEmpty())
            info.add("variant", model?.get("variant"))
            info.add("path", Json.obj("cwd" to directory, "root" to directory))
            info.add("cost", value.get("cost") ?: Json.toElement(0))
            info.add("tokens", value.get("tokens") ?: Json.obj("input" to 0, "output" to 0, "reasoning" to 0, "cache" to Json.obj("read" to 0, "write" to 0)))
            info.add("finish", value.get("finish"))
            val error = value.obj("error") ?: if (type == "idle") context.obj("error") ?: Json.obj("type" to "UnknownError",
                "message" to "OpenCode failed before a response was recorded. Check the provider connection and the OpenCode server log.") else null
            if (error != null) info.add("error", Json.obj("name" to (error.str("type") ?: "UnknownError"),
                "data" to Json.obj("message" to error.str("message"), "statusCode" to error.get("status"))))
            if (type == "idle") info.add("time", time.deepCopy().apply { add("completed", time.get("created")) })
            value.arr("content").orEmpty().forEachIndexed { ordinal, entry ->
                val content = entry.asObjectOrNull() ?: return@forEachIndexed
                if (content.str("type") != "tool") {
                    parts.add(part(ordinal, content.str("type").orEmpty(), Json.obj("text" to content.get("text"),
                        "time" to if (content.str("type") == "reasoning") Json.obj("start" to (content.obj("time")?.get("created") ?: time.get("created")),
                            "end" to content.obj("time")?.get("completed")) else null)))
                } else {
                    val toolID = content.str("id")
                    val source = content.obj("state") ?: Json.obj()
                    val state = source.deepCopy()
                    if (source.str("status") == "streaming") {
                        state.addProperty("status", "pending"); state.add("raw", source.get("input")); state.add("input", Json.obj())
                    } else {
                        state.add("metadata", source.obj("metadata") ?: Json.obj())
                        state.add("time", Json.obj("start" to (content.obj("time")?.get("ran") ?: content.obj("time")?.get("created")), "end" to content.obj("time")?.get("completed")))
                        if (source.str("status") != "running") {
                            state.addProperty("output", toolOutput(source.get("content")))
                            state.addProperty("title", source.obj("metadata").str("title"))
                            if (source.str("status") == "error") state.addProperty("error", source.obj("error").str("message"))
                            state.add("attachments", Json.array(source.arr("content").orEmpty().mapNotNull { it.asObjectOrNull() }
                                .filter { it.str("type") == "file" }.mapIndexed { index, file -> Json.obj("id" to "$toolID:file:$index", "type" to "file",
                                    "sessionID" to sessionID, "messageID" to id, "url" to file.get("uri"), "mime" to file.get("mime"), "filename" to file.get("name")) }))
                        }
                    }
                    parts.add(Json.obj("id" to toolID, "callID" to toolID, "sessionID" to sessionID, "messageID" to id,
                        "type" to "tool", "tool" to legacyAction(content.str("name")), "state" to state))
                }
            }
        } else {
            info.add("model", Json.obj("providerID" to model.str("providerID").orEmpty(), "modelID" to model.str("id").orEmpty(), "variant" to model?.get("variant")))
            if (type == "user") {
                parts.add(part(0, "text", Json.obj("text" to value.str("text").orEmpty())))
                value.arr("files").orEmpty().forEach { entry ->
                    val file = entry.asObjectOrNull()
                    parts.add(part(parts.size, "file", Json.obj("mime" to file.str("mime"), "filename" to file.str("name"),
                        "url" to if (file.obj("source").str("type") == "uri") file.obj("source").str("uri") else "data:${file.str("mime")};base64,${file.str("data")}")))
                }
                value.arr("agents").orEmpty().forEach { parts.add(part(parts.size, "agent", Json.obj("name" to it.asObjectOrNull().str("name")))) }
                value.arr("skills").orEmpty().forEach {
                    val name = it.asObjectOrNull().str("name").orEmpty()
                    val reference = "$[${java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")}]"
                    parts.add(part(parts.size, "text", Json.obj("text" to "[Attached skill: $reference]\nUse the skill tool to load ${Json.stringify(name)} before responding. $reference in the prompt refers to this skill.", "synthetic" to true)))
                }
            } else if (type == "compaction") parts.add(part(0, "compaction", Json.obj("auto" to (value.str("reason") == "auto"),
                "status" to value.get("status"), "error" to value.obj("error").str("message"))))
        }
        return Json.obj("info" to info, "parts" to parts)
    }
}
