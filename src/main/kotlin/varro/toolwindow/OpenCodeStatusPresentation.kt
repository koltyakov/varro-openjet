package varro.toolwindow

import varro.server.OpenCodeCli
import varro.server.OpenCodeVersionInfo
import java.net.URI

internal data class OpenCodeStatusPresentation(val text: String, val tooltip: String) {
    companion object {
        // Match the SDK/client versions in the pinned upstream Varro manifest.
        private const val VERIFIED_V1 = "1.18.35"
        private const val VERIFIED_V2 = "2.0.25"

        fun from(info: OpenCodeVersionInfo, extensionVersion: String, autoUpdates: Boolean, now: Long = System.currentTimeMillis()): OpenCodeStatusPresentation {
            val cli = info.cliVersion.takeUnless { info.attachOnly }
            val server = info.serverVersion
            val displayed = server ?: cli
            val verified = if ((cli ?: displayed)?.startsWith("2.") == true) VERIFIED_V2 else VERIFIED_V1
            val updateAvailable = cli != null && OpenCodeCli.compareVersions(cli, verified) < 0
            val stale = cli != null && server != null && OpenCodeCli.compareVersions(server, cli) < 0
            val endpoint = URI(info.url)
            val lines = mutableListOf<String>()
            if (info.attachOnly) {
                lines += "Server address: ${info.url}"
                lines += "Server IP: ${endpoint.host}"
            } else lines += "OpenCode CLI: ${cli ?: "unknown"}"
            lines += "OpenCode Server: ${server ?: "unknown"}"
            lines += "Server port: ${endpoint.port.takeIf { it > 0 } ?: if (endpoint.scheme == "https") 443 else 80}"
            info.startedAt?.takeIf { it > 0 }?.let { lines += "Server uptime: ${formatUptime(it, now)}" }
            if (updateAvailable) {
                lines += ""
                lines += "New CLI version: OpenCode $verified is not installed yet."
                lines += "Auto-updates are ${if (autoUpdates) "on" else "off"}."
            }
            if (stale) {
                lines += ""
                lines += "CLI updated to OpenCode $cli; server $server is stale."
            }
            lines += ""
            lines += "Varro OpenJet: $extensionVersion"
            if (listOfNotNull(cli, server).any { it.split('.').take(2) != verified.split('.').take(2) }) {
                lines += "Verified w/ OpenCode $verified"
            }
            val marker = if (updateAvailable || stale) "*" else ""
            return OpenCodeStatusPresentation("OpenCode${displayed?.let { " $it$marker" }.orEmpty()}", lines.joinToString("\n"))
        }

        private fun formatUptime(startedAt: Long, now: Long): String {
            var minutes = ((now - startedAt) / 60_000).coerceAtLeast(0)
            val units = listOf(10080L to "week", 1440L to "day", 60L to "hr", 1L to "min")
            val index = units.indexOfFirst { minutes >= it.first }
            if (index < 0) return "less than a min"
            return units.drop(index).take(2).mapNotNull { (size, name) ->
                val value = minutes / size
                minutes %= size
                if (value == 0L) null else "$value $name${if (value > 1 && name != "min") "s" else ""}"
            }.joinToString(" ")
        }
    }
}
