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

internal fun isAllowedExternalUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank()
}.getOrDefault(false)
