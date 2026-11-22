package varro.protocol

/** Lengths use UTF-16 code units, matching the shared JavaScript wire format. */
internal object PromptTextAttachments {
    data class Attachment(val name: String, val text: String)
    data class Prompt(val text: String, val attachments: List<Attachment>)
    private const val PREFIX = "<attached-text name=\""
    private const val CLOSE = "\n</attached-text>"
    private const val SEPARATOR = "\n\n"
    private val name = Regex("^(?:pasted-text|annotation)-[a-f0-9]{8}\\.txt$")
    private val open = Regex("<attached-text name=\"((?:pasted-text|annotation)-[a-f0-9]{8}\\.txt)\" length=\"(\\d+)\">\\n")

    fun isName(value: String) = name.matches(value)
    fun append(text: String, attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return text
        return (listOf(text).filter { it.isNotEmpty() } + attachments.map {
            require(isName(it.name)) { "Invalid prompt text attachment name" }
            "$PREFIX${it.name}\" length=\"${it.text.length}\">\n${it.text}$CLOSE"
        }).joinToString(SEPARATOR)
    }

    fun split(text: String): Prompt {
        var start = text.indexOf(PREFIX)
        while (start >= 0) {
            if (start == 0 || start >= SEPARATOR.length && text.startsWith(SEPARATOR, start - SEPARATOR.length)) {
                readBlocks(text, start)?.let { return Prompt(text.take(maxOf(0, start - SEPARATOR.length)), it) }
            }
            start = text.indexOf(PREFIX, start + 1)
        }
        return Prompt(text, emptyList())
    }

    private fun readBlocks(text: String, start: Int): List<Attachment>? {
        val attachments = mutableListOf<Attachment>()
        var index = start
        while (true) {
            val match = open.matchAt(text, index) ?: return null
            val contentStart = match.range.last + 1
            val length = match.groupValues[2].toLongOrNull() ?: return null
            if (length > text.length - contentStart) return null
            val end = contentStart + length.toInt()
            if (!text.startsWith(CLOSE, end)) return null
            attachments += Attachment(match.groupValues[1], text.substring(contentStart, end))
            index = end + CLOSE.length
            if (index == text.length) return attachments
            if (!text.startsWith(SEPARATOR, index)) return null
            index += SEPARATOR.length
        }
    }
}
