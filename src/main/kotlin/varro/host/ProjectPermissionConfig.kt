package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.server.OpenCodeV2Projection
import varro.store.JsonJournal
import java.nio.file.Files
import java.nio.file.Path

/** Replaces the local rule set rather than merging deleted rules back into it. */
class ProjectPermissionConfig(private val directory: Path, private val native: () -> Boolean = { false }) {
    fun path(): Path = ProjectConfigPath.resolve(directory, native())

    @Synchronized fun read(): JsonArray {
        val document = readDocument()
        return if (document.has("permissions")) OpenCodeV2Projection.legacyRules(document.get("permissions"))
        else PermissionService.fromConfig(document.get("permission"))
    }

    @Synchronized fun write(rules: JsonArray) {
        val document = readDocument()
        if (native() || document.has("permissions")) document.add("permissions", OpenCodeV2Projection.rules(rules))
        else document.add("permission", PermissionService.toConfig(rules))
        JsonJournal(path()).write(document)
    }

    private fun readDocument(): JsonObject {
        val file = path()
        if (!Files.exists(file)) return JsonObject()
        require(Files.size(file) <= 4 * 1024 * 1024) { "OpenCode project configuration is too large" }
        return Json.parseOrNull(stripJsonComments(Files.readString(file))).asObjectOrNull()
            ?: error("Could not parse $file")
    }

    companion object {
        /** Remove JSONC comments/trailing commas without changing quoted URLs or patterns. */
        internal fun stripJsonComments(text: String): String {
            val out = StringBuilder()
            var index = 0
            var quoted = false
            while (index < text.length) {
                val c = text[index]
                if (quoted) {
                    out.append(c)
                    if (c == '\\' && index + 1 < text.length) out.append(text[++index])
                    else if (c == '"') quoted = false
                } else when {
                    c == '"' -> { quoted = true; out.append(c) }
                    text.startsWith("//", index) -> { while (index < text.length && text[index] != '\n') index++; out.append('\n') }
                    text.startsWith("/*", index) -> {
                        val end = text.indexOf("*/", index + 2)
                        require(end >= 0) { "Unterminated JSONC comment" }
                        index = end + 1; out.append(' ')
                    }
                    else -> out.append(c)
                }
                index++
            }
            val clean = out.toString()
            out.setLength(0)
            quoted = false
            index = 0
            while (index < clean.length) {
                val c = clean[index]
                if (quoted) {
                    out.append(c)
                    if (c == '\\' && index + 1 < clean.length) out.append(clean[++index])
                    else if (c == '"') quoted = false
                } else {
                    if (c == '"') quoted = true
                    var next = index + 1
                    if (c == ',') while (next < clean.length && clean[next].isWhitespace()) next++
                    val trailing = c == ',' && next < clean.length && clean[next] in listOf('}', ']')
                    if (!trailing) out.append(c)
                }
                index++
            }
            return out.toString()
        }
    }
}
