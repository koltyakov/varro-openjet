package varro.host

import com.google.gson.JsonElement
import varro.protocol.Json

/** Changes one property without rewriting unrelated JSONC comments or formatting. Input is parsed first. */
internal object JsoncEdit {
    private data class Token(val value: String, val range: IntRange)

    private fun tokens(text: String): List<Token> {
        val result = mutableListOf<Token>()
        var index = 0
        while (index < text.length) {
            when {
                text[index].isWhitespace() -> index++
                text.startsWith("//", index) -> { while (index < text.length && text[index] != '\n') index++ }
                text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2)
                    require(end >= 0) { "Unterminated JSONC comment" }
                    index = end + 2
                }
                else -> {
                    val start = index++
                    if (text[start] == '"') {
                        while (index < text.length) {
                            val char = text[index++]
                            if (char == '\\') index++ else if (char == '"') break
                        }
                    } else if (text[start] !in "{}[],:\"") {
                        while (index < text.length && !text[index].isWhitespace() && text[index] !in "{}[],:\"" &&
                            !text.startsWith("//", index) && !text.startsWith("/*", index)) index++
                    }
                    result += Token(text.substring(start, index), start until index)
                }
            }
        }
        return result
    }

    fun set(raw: String, path: List<String>, value: JsonElement): String {
        val text = raw.takeIf { it.isNotBlank() } ?: "{}\n"
        val parts = tokens(text)
        fun end(start: Int): Int {
            if (parts[start].value !in listOf("{", "[")) return start + 1
            var depth = 0
            for (i in start until parts.size) {
                if (parts[i].value in listOf("{", "[")) depth++
                if (parts[i].value in listOf("}", "]") && --depth == 0) return i + 1
            }
            error("Invalid OpenCode JSONC configuration")
        }
        fun edit(start: Int, keys: List<String>): String {
            require(parts[start].value == "{") { "Invalid OpenCode policy container" }
            var i = start + 1
            var match: Pair<Int, Int>? = null
            while (parts[i].value != "}") {
                if (parts[i].value == ",") { i++; continue }
                val key = Json.parse(parts[i].value).asString
                val valueStart = i + 2
                val valueEnd = end(valueStart)
                if (key == keys.first()) {
                    require(match == null) { "Duplicate OpenCode configuration key: $key" }
                    match = valueStart to valueEnd
                }
                i = valueEnd
            }
            match?.let { (valueStart, valueEnd) ->
                if (keys.size > 1) return edit(valueStart, keys.drop(1))
                return text.replaceRange(parts[valueStart].range.first, parts[valueEnd - 1].range.last + 1, value.toString())
            }
            val nested = keys.drop(1).asReversed().fold(value) { child, key -> Json.obj(key to child) }
            val comma = if (parts[i - 1].value in listOf("{", ",")) "" else ","
            return text.replaceRange(parts[i].range.first, parts[i].range.first, "$comma\n  ${Json.toElement(keys.first())}: $nested\n")
        }
        return edit(0, path)
    }
}
