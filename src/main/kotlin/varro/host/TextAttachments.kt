package varro.host

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Same two data URL formats and strict UTF-8/64 KiB bound as shared/pasted-text.ts. */
internal object TextAttachments {
    fun decode(url: String): String? {
        if (url.length > 3 * 65536 + 64) return null
        return runCatching {
            val bytes = when {
                url.startsWith("data:text/plain;base64,") -> Base64.getDecoder().decode(url.removePrefix("data:text/plain;base64,"))
                url.startsWith("data:text/plain;charset=utf-8,") -> {
                    val value = url.removePrefix("data:text/plain;charset=utf-8,")
                    val output = ByteArrayOutputStream()
                    var index = 0
                    while (index < value.length) {
                        if (value[index] == '%') {
                            require(index + 2 < value.length)
                            output.write(value.substring(index + 1, index + 3).toInt(16))
                            index += 3
                        } else {
                            val end = value.indexOf('%', index).takeIf { it >= 0 } ?: value.length
                            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                                .encode(CharBuffer.wrap(value, index, end))
                            val chunk = ByteArray(encoded.remaining()); encoded.get(chunk); output.write(chunk)
                            index = end
                        }
                        require(output.size() <= 65536)
                    }
                    output.toByteArray()
                }
                else -> return null
            }
            require(bytes.size <= 65536)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }
}
