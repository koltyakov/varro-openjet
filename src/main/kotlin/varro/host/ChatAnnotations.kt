package varro.host

import com.google.gson.JsonObject
import varro.protocol.*

internal object ChatAnnotations {
    fun preview(part: JsonObject): JsonObject? {
        if (part.str("mime") != "text/plain" || !Regex("annotation-[a-f0-9]{8}\\.txt").matches(part.str("filename").orEmpty())) return null
        val url = part.str("url") ?: return null
        val text = TextAttachments.decode(url) ?: return null
        if (!text.startsWith("Chat annotation\n\n")) return null
        var rest = text.removePrefix("Chat annotation\n\n")
        val source = Regex("^Source session: ([^\\r\\n]+)\n\n").find(rest)
        val id = source?.groupValues?.get(1)
        if (id != null && (id.isBlank() || id.length > 256)) return null
        if (source != null) rest = rest.substring(source.value.length)
        if (!rest.startsWith("Selected text:\n")) return null
        rest = rest.removePrefix("Selected text:\n")
        val separator = rest.indexOf("\n\nComment:\n")
        if (separator < 0) return null
        val selected = rest.substring(0, separator).replace(Regex("^> ", RegexOption.MULTILINE), "")
        val note = rest.substring(separator + "\n\nComment:\n".length)
        if (selected.isBlank() || note.isBlank()) return null
        return Json.obj("selectedText" to selected.take(1200), "note" to note.take(1200)).apply {
            if (id != null) addProperty("sourceSessionId", id)
        }
    }
}
