package varro.server

import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.long
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path

/** Service selection is sticky, but never grants process ownership. */
internal class OpenCodeServiceRouting(
    private val environment: Map<String, String>,
    serverDirectory: Path,
) {
    val stateHome: Path = serverDirectory.resolve("opencode-service")
    private val privateFile = stateHome.resolve("opencode/service.json")
    @Volatile var privateSelected = false
        private set

    fun launchArguments(version: String?, port: Int): List<String> =
        listOf("serve") + (if (version?.startsWith("2.") == true) listOf("--service") else emptyList()) + listOf("--port", port.toString())

    fun launchEnvironment(version: String?, values: Map<String, String>): Map<String, String> {
        if (version?.startsWith("2.") != true) return values
        privateSelected = true
        return values.filterKeys { !it.equals("XDG_STATE_HOME", ignoreCase = true) } + ("XDG_STATE_HOME" to stateHome.toString())
    }

    fun registrations(targetUrl: String? = null, privateOnly: Boolean = privateSelected): List<OpenCodeConnection.Registration> =
        (listOf(privateFile) + if (privateOnly) emptyList() else listOf(OpenCodeConnection.serviceFile(environment)))
            .distinct().mapNotNull(OpenCodeConnection::registration).filter { targetUrl == null || it.url == targetUrl }

    /** Called only after independent lease or endpoint verification. Markers need not contain passwords. */
    fun rememberVerified(registration: OpenCodeConnection.Registration) {
        if (registration.file == privateFile) privateSelected = true
    }

    fun rememberVerified(pid: Long?, port: Int) {
        if (privateSelected || pid == null) return
        val matches = runCatching {
            if (!Files.exists(privateFile) || Files.size(privateFile) > 16384) return@runCatching false
            val value = Json.parseOrNull(Files.readString(privateFile)).asObjectOrNull()
            value.long("pid") == pid && value.str("url") == "http://127.0.0.1:$port"
        }.getOrDefault(false)
        if (matches) privateSelected = true
    }
}
