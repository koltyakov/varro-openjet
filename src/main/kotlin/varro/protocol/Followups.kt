package varro.protocol

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.util.Locale

/** Validation shared by follow-up inference and native message attribution. */
internal object Followups {
    private val unsafe = Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f\\u202a-\\u202e\\u2066-\\u2069]")
    private val strictJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    fun empty(): JsonObject = Json.obj("summary" to "", "actions" to emptyList<Any>())

    fun title(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= 40 && it.none { char -> char in "\r\n\t" } && !unsafe.containsMatchIn(it)
    }

    fun model(value: JsonElement?): JsonObject? {
        val record = value.asObjectOrNull() ?: return null
        val provider = record.str("providerID")?.takeIf { it.isNotEmpty() && it.length <= 4_096 } ?: return null
        val id = record.str("modelID")?.takeIf { it.isNotEmpty() && it.length <= 4_096 } ?: return null
        val variant = record.str("variant")
        if (record.has("variant") && (variant == null || variant.length > 4_096)) return null
        return Json.obj("providerID" to provider, "modelID" to id).apply {
            variant?.takeIf { it.isNotEmpty() }?.let { addProperty("variant", it) }
        }
    }

    /** JSON.stringify-compatible identity for shared admission, including literal escapes and lone surrogates. */
    fun identityJson(value: JsonElement): String = when {
        value.isJsonNull -> "null"
        value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]", transform = ::identityJson)
        value.isJsonObject -> value.asJsonObject.entrySet().joinToString(",", "{", "}") { (key, entry) -> "${identityString(key)}:${identityJson(entry)}" }
        else -> identityString(value.asString)
    }

    private fun identityString(value: String): String = buildString {
        append('"')
        value.forEachIndexed { index, char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ' ||
                    (Character.isHighSurrogate(char) && (index + 1 == value.length || !Character.isLowSurrogate(value[index + 1]))) ||
                    (Character.isLowSurrogate(char) && (index == 0 || !Character.isHighSurrogate(value[index - 1])))) {
                    append("\\u%04x".format(char.code))
                } else append(char)
            }
        }
        append('"')
    }

    fun source(value: JsonElement?): JsonObject {
        val input = value.asObjectOrNull()
        val assistant = input.str("assistantMessageId")
        val user = input.str("userMessageId")
        val prompt = input.str("userPrompt")
        val answer = input.str("answer")
        require(assistant != null && Regex("[\\w-]{1,200}").matches(assistant) &&
            user != null && Regex("[\\w-]{1,200}").matches(user) &&
            prompt != null && prompt.isNotBlank() && prompt.length <= 4_000 &&
            answer != null && answer.isNotBlank() && answer.length <= 12_000) { "Invalid follow-ups source" }
        // Fixed field order matches the upstream digest, independent of request field order.
        return Json.obj("assistantMessageId" to assistant, "userMessageId" to user, "userPrompt" to prompt, "answer" to answer)
    }

    fun text(parts: JsonElement?): String = parts.asArrayOrNull()?.toList().orEmpty().mapNotNull {
        val part = it.asObjectOrNull()
        part.str("text")?.takeIf { part.str("type") == "text" && part.bool("synthetic") != true && part.bool("ignored") != true }
    }.joinToString("\n\n").trim()

    fun suggestions(text: String): JsonObject {
        require(text.length <= 4_096) { "Follow-ups output is too large" }
        val record = strictJson.fromJson(text, JsonElement::class.java).asObjectOrNull()
        val summary = record.str("summary")
        val actions = record.arr("actions")
        require(record?.keySet() == setOf("summary", "actions") && summary != null && summary.trim().length <= 400 &&
            !unsafe.containsMatchIn(summary) && actions != null && actions.size() <= 3 &&
            (actions.isEmpty || summary.isNotBlank())) { "Invalid follow-ups output" }
        val titles = mutableSetOf<String>()
        val prompts = mutableSetOf<String>()
        return Json.obj("summary" to summary.trim(), "actions" to actions.map {
            val action = it.asObjectOrNull()
            val title = title(action.str("title"))
            val prompt = action.str("prompt")?.trim()
            require(action?.keySet() == setOf("title", "prompt") && title != null && prompt != null &&
                prompt.isNotEmpty() && prompt.length <= 600 && !prompt.startsWith('/') && !unsafe.containsMatchIn(prompt) &&
                titles.add(title.lowercase(Locale.ROOT)) && prompts.add(prompt.lowercase(Locale.ROOT))) { "Invalid follow-up action" }
            Json.obj("title" to title, "prompt" to prompt)
        })
    }
}
