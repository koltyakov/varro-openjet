package varro.host

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
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

    private fun end(parts: List<Token>, start: Int): Int {
        if (parts[start].value !in listOf("{", "[")) return start + 1
        var depth = 0
        for (i in start until parts.size) {
            if (parts[i].value in listOf("{", "[")) depth++
            if (parts[i].value in listOf("}", "]") && --depth == 0) return i + 1
        }
        error("Invalid OpenCode JSONC configuration")
    }

    /** Preserve native rule entries and their comments, including during append-only approvals. */
    fun nativePermissions(raw: String, rules: JsonArray): String {
        var text = raw.takeIf { it.isNotBlank() } ?: "{}\n"
        fun array(parts: List<Token>): Int? {
            require(parts.first().value == "{") { "Invalid OpenCode policy container" }
            var i = 1
            var found: Int? = null
            while (parts[i].value != "}") {
                if (parts[i].value == ",") { i++; continue }
                if (Json.parse(parts[i].value).asString == "permissions") {
                    require(found == null) { "Duplicate OpenCode configuration key: permissions" }
                    found = i + 2
                }
                i = end(parts, i + 2)
            }
            return found
        }
        var parts = tokens(text)
        var start = array(parts)
        if (start == null || parts[start].value != "[") {
            text = set(text, listOf("permissions"), JsonArray())
            parts = tokens(text)
            start = array(parts)!!
        }
        fun entries(parts: List<Token>, start: Int): List<Pair<Int, Int>> {
            val result = mutableListOf<Pair<Int, Int>>()
            var i = start + 1
            while (parts[i].value != "]") {
                if (parts[i].value == ",") { i++; continue }
                val next = end(parts, i)
                result += i to next
                i = next
            }
            return result
        }
        val children = entries(parts, start)
        val eol = Regex("\\r\\n|\\n|\\r").find(text)?.value ?: "\n"
        fun prefix(offset: Int) = text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset)
        val arrayIndent = prefix(parts[start].range.first).takeWhile { it == ' ' || it == '\t' }
        val samplePrefix = children.firstOrNull()?.let { prefix(parts[it.first].range.first) }
        val entryIndent = samplePrefix?.takeIf { it.all { c -> c == ' ' || c == '\t' } }
            ?: (arrayIndent + if ('\t' in arrayIndent) "\t" else "  ")
        val indent = entryIndent.removePrefix(arrayIndent).ifEmpty { "  " }
        val sample = children.firstOrNull()?.let { text.substring(parts[it.first].range.first, parts[it.second - 1].range.last + 1) }
        val compact = sample != null && sample.none { it == '\r' || it == '\n' }
        val multiline = sample == null || text.substring(parts[start].range.first, parts[end(parts, start) - 1].range.last + 1).any { it == '\r' || it == '\n' }
        fun render(value: JsonElement): String = if (compact && value.isJsonObject) {
            value.asJsonObject.entrySet().joinToString(", ", "{ ", " }") { (key, item) -> "${Json.toElement(key)}: $item" }
        } else GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(value)
            .lines().mapIndexed { index, line -> if (index == 0) line else entryIndent + line.replace(Regex("^(?:  )+")) { indent.repeat(it.value.length / 2) } }.joinToString(eol)

        data class Edit(val from: Int, val to: Int, val content: String)
        val edits = mutableListOf<Edit>()
        children.forEachIndexed { index, (first, after) ->
            val from = parts[first].range.first
            val to = parts[after - 1].range.last + 1
            if (index >= rules.size()) {
                edits += Edit(from, to, "")
                val comma = if (index > 0) children[index - 1].second else children.last().second
                if (parts[comma].value == ",") edits += Edit(parts[comma].range.first, parts[comma].range.last + 1, "")
            } else {
                var entry = text.substring(from, to)
                val previous = Json.parse(ProjectPermissionConfig.stripJsonComments(entry))
                val next = rules[index]
                if (previous != next) {
                    if (previous.isJsonObject && next.isJsonObject) {
                        (previous.asJsonObject.keySet() + next.asJsonObject.keySet()).forEach { key ->
                            if (previous.asJsonObject.get(key) != next.asJsonObject.get(key))
                                entry = set(entry, listOf(key), next.asJsonObject.get(key))
                        }
                    } else entry = render(next)
                    edits += Edit(from, to, entry)
                }
            }
        }
        edits.sortedByDescending { it.from }.forEach { text = text.replaceRange(it.from, it.to, it.content) }
        if (rules.size() <= children.size) return text

        parts = tokens(text)
        start = array(parts)!!
        val current = entries(parts, start)
        val last = current.lastOrNull()
        val next = last?.second ?: (start + 1)
        val trailingComma = parts[next].value == ","
        val close = parts[end(parts, start) - 1].range.first
        val closePrefix = prefix(close)
        val ownLine = closePrefix.all { it == ' ' || it == '\t' }
        val insertion = if (multiline && ownLine) close - closePrefix.length else close
        val separator = if (multiline) ",$eol$entryIndent" else ", "
        val added = rules.drop(children.size).joinToString(separator, transform = ::render)
        var addition = if (multiline) "${if (ownLine) "" else eol}$entryIndent$added${if (trailingComma) "," else ""}$eol${if (ownLine) "" else arrayIndent}"
            else "${if (last != null) " " else ""}$added${if (trailingComma) "," else ""}"
        val lastEnd = last?.let { parts[it.second - 1].range.last + 1 }
        if (last != null && !trailingComma && insertion == lastEnd) addition = ",$addition"
        text = text.replaceRange(insertion, insertion, addition)
        if (lastEnd != null && !trailingComma && insertion != lastEnd) text = text.replaceRange(lastEnd, lastEnd, ",")
        return text
    }

    fun set(raw: String, path: List<String>, value: JsonElement?): String {
        val text = raw.takeIf { it.isNotBlank() } ?: "{}\n"
        val parts = tokens(text)
        fun edit(start: Int, keys: List<String>): String {
            require(parts[start].value == "{") { "Invalid OpenCode policy container" }
            var i = start + 1
            var match: Pair<Int, Int>? = null
            while (parts[i].value != "}") {
                if (parts[i].value == ",") { i++; continue }
                val key = Json.parse(parts[i].value).asString
                val valueStart = i + 2
                val valueEnd = end(parts, valueStart)
                if (key == keys.first()) {
                    require(match == null) { "Duplicate OpenCode configuration key: $key" }
                    match = valueStart to valueEnd
                }
                i = valueEnd
            }
            match?.let { (valueStart, valueEnd) ->
                if (keys.size > 1) return edit(valueStart, keys.drop(1))
                if (value == null) {
                    val keyStart = valueStart - 2
                    val followingComma = parts.getOrNull(valueEnd)?.value == ","
                    val precedingComma = parts.getOrNull(keyStart - 1)?.value == ","
                    val from = if (!followingComma && precedingComma) parts[keyStart - 1].range.first else parts[keyStart].range.first
                    val to = if (followingComma) parts[valueEnd].range.last + 1 else parts[valueEnd - 1].range.last + 1
                    return text.removeRange(from, to)
                }
                return text.replaceRange(parts[valueStart].range.first, parts[valueEnd - 1].range.last + 1, value.toString())
            }
            if (value == null) return text
            val nested = keys.drop(1).asReversed().fold(value) { child, key -> Json.obj(key to child) }
            val comma = if (parts[i - 1].value in listOf("{", ",")) "" else ","
            val eol = Regex("\\r\\n|\\n|\\r").find(text)?.value ?: "\n"
            return text.replaceRange(parts[i].range.first, parts[i].range.first, "$comma$eol  ${Json.toElement(keys.first())}: $nested$eol")
        }
        return edit(0, path)
    }
}
