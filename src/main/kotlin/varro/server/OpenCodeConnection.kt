package varro.server

import varro.protocol.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Credentials stay in the host and are bound to the loopback endpoint. */
internal object OpenCodeConnection {
    private val managed = ConcurrentHashMap<String, String>()
    fun authorization(password: String, username: String = "opencode") = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    fun register(url: String, password: String) { managed[url] = authorization(password) }
    fun forget(url: String) { managed.remove(url) }
    fun credentials(url: String, environment: Map<String, String>): String? {
        managed[url]?.let { return it }
        registration(environment)?.takeIf { it.url == url }?.let { return authorization(it.password) }
        return environment["OPENCODE_SERVER_PASSWORD"]?.takeIf { it.isNotEmpty() }?.let { authorization(it, environment["OPENCODE_SERVER_USERNAME"] ?: "opencode") }
    }
    data class Registration(val url: String, val password: String, val pid: Long, val version: String)
    fun registration(environment: Map<String, String>): Registration? = runCatching {
        val path = Path.of(environment["XDG_STATE_HOME"] ?: Path.of(environment["HOME"] ?: System.getProperty("user.home"), ".local", "state").toString(), "opencode", "service.json")
        if (!Files.exists(path) || Files.size(path) > 16384) return null
        val value = Json.parse(Files.readString(path)).asObjectOrNull() ?: return null
        val url = value.str("url") ?: return null
        val uri = URI(url)
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port !in 1..65535 || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path !in listOf("", "/")) return null
        val password = value.str("password")?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return null
        val pid = value.long("pid")?.takeIf { it > 0 } ?: return null
        if (ProcessHandle.of(pid).orElse(null)?.isAlive != true) return null
        val version = value.str("version")?.takeIf { it.startsWith("2.") } ?: return null
        Registration("http://127.0.0.1:${uri.port}", password, pid, version)
    }.getOrNull()
}

/** Buffer entire lines so split startup credentials never reach diagnostics. */
internal class OpenCodeStartupOutput(private val onPassword: (String) -> Unit) {
    private val pending = StringBuilder()
    private var discarding = false
    @Synchronized fun write(chunk: String): String {
        val output = StringBuilder()
        for (char in chunk) {
            if (char == '\n') {
                if (!discarding) {
                    val line = pending.toString()
                    val index = line.indexOf("server password")
                    if (index >= 0) {
                        val password = line.substring(index + "server password".length).trim()
                        if (password.isNotEmpty() && password.length <= 4096) onPassword(password)
                        output.append(line.substring(0, index)).append("server password [redacted]\n")
                    } else output.append(line).append('\n')
                }
                pending.setLength(0); discarding = false
            } else if (!discarding) {
                pending.append(char)
                if (pending.length > 8192) { pending.setLength(0); discarding = true; output.append("[oversized startup line omitted]\n") }
            }
        }
        return output.toString()
    }
}
