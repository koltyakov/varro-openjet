package varro.host

import com.google.gson.JsonObject
import varro.protocol.str
import java.net.URI

internal data class LinkContextMenu(val label: String, val text: String) {
    companion object {
        fun from(payload: JsonObject?): LinkContextMenu? = when (payload.str("webviewSection")) {
            "varroExternalLink" -> payload.str("varroLinkUrl")?.takeIf(::isAllowedExternalUrl)?.let { LinkContextMenu("Copy link", it) }
            "varroFileLink" -> payload.str("varroFilePath")?.takeIf { it.isNotBlank() }?.let { LinkContextMenu("Copy path", it) }
            else -> null
        }
    }
}

internal fun isAllowedExternalUrl(url: String): Boolean = externalBrowserUrl(url) != null

/**
 * The webview accepts links with WHATWG `new URL()`, which tolerates characters
 * such as `|`, `{}`, `^` or spaces after the host that `java.net.URI` rejects.
 * Those are percent-encoded here, as a browser would before requesting them, so
 * the IDE opens the same links VS Code does. Returns `null` for anything but an
 * HTTP(S) URL with an authority.
 */
internal fun externalBrowserUrl(url: String): String? {
    if (url.length > MAX_EXTERNAL_URL_LENGTH || url.any(Char::isISOControl)) return null
    val match = EXTERNAL_URL.matchEntire(url) ?: return null
    val (scheme, authority, rest) = match.destructured
    if (scheme.lowercase() !in setOf("http", "https") || authority.isBlank()) return null
    val encoded = buildString {
        rest.forEachIndexed { index, char ->
            val escape = char in ENCODED_AFTER_AUTHORITY ||
                (char == '%' && !(index + 2 < rest.length && rest[index + 1].isHex() && rest[index + 2].isHex()))
            if (escape) char.toString().toByteArray(Charsets.UTF_8).forEach { append('%').append("%02X".format(it)) }
            else append(char)
        }
    }
    val candidate = "$scheme://$authority$encoded"
    return runCatching { URI(candidate) }.getOrNull()?.takeIf { !it.rawAuthority.isNullOrBlank() }?.let { candidate }
}

private const val MAX_EXTERNAL_URL_LENGTH = 32 * 1024
private val EXTERNAL_URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL)
private const val ENCODED_AFTER_AUTHORITY = " \"<>\\^`{|}[]"
private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
