package varro.server

import com.intellij.openapi.diagnostic.logger
import varro.host.ProjectPermissionConfig
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.obj
import varro.store.JsonJournal
import java.nio.file.Files
import java.nio.file.Path

/** Process-owned config, never written into the user's global or project configuration. */
internal class AskAgentConfig(
    private val environment: Map<String, String>,
    private val workspaceCwd: () -> String?,
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val tempDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
) : AutoCloseable {
    private val log = logger<AskAgentConfig>()
    private var path: Path? = null
    private var content: String? = null

    @Synchronized fun prepare(enabled: Boolean): Map<String, String> {
        close()
        if (!environment["OPENCODE_CONFIG"].isNullOrBlank()) {
            log.info("Preserving caller-provided OPENCODE_CONFIG; Varro's Ask agent is not injected")
            return emptyMap()
        }
        // Keep a file even when disabled, so enabling Ask does not require a server restart.
        val directory = Files.createTempDirectory(tempDirectory, "varro-openjet-config-")
        path = directory.resolve("opencode.json")
        try {
            rewrite(enabled)
            return mapOf("OPENCODE_CONFIG" to path.toString())
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    /** Returns true only when the running server needs to reload its config. */
    @Synchronized fun rewrite(enabled: Boolean): Boolean {
        val file = path ?: return false
        val config = Json.obj()
        if (enabled && !hasConfiguredAsk()) config.add("agent", Json.obj("ask" to definition()))
        val next = Json.stringify(config)
        if (next == content) return false
        JsonJournal(file).write(config)
        content = next
        return true
    }

    private fun hasConfiguredAsk(): Boolean {
        environment["OPENCODE_CONFIG_CONTENT"]?.takeIf(String::isNotBlank)?.let {
            if (containsAsk(it)) return true
        }
        val global = environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
            ?.resolve("opencode") ?: home.resolve(".config/opencode")
        val directories = linkedSetOf(global)
        val agentDirectories = linkedSetOf(global)
        environment["OPENCODE_CONFIG_DIR"]?.takeIf(String::isNotBlank)?.let {
            directories.add(Path.of(it))
            agentDirectories.add(Path.of(it))
        }
        var workspace = workspaceCwd()?.let { Path.of(it).toAbsolutePath().normalize() }
        while (workspace != null) {
            directories.add(workspace)
            directories.add(workspace.resolve(".opencode"))
            agentDirectories.add(workspace.resolve(".opencode"))
            if (Files.exists(workspace.resolve(".git"))) break
            workspace = workspace.parent
        }
        return directories.any { directory ->
            try {
                val names = if (directory == global) listOf("config.json", "opencode.json", "opencode.jsonc")
                    else listOf("opencode.json", "opencode.jsonc")
                names.any { name ->
                    val file = directory.resolve(name)
                    Files.exists(file) && containsAsk(Files.readString(file))
                } || directory in agentDirectories && listOf("agent", "agents").any { name ->
                    val agents = directory.resolve(name)
                    Files.isDirectory(agents) && Files.newDirectoryStream(agents).use { entries ->
                        entries.any { it.fileName.toString().equals("ask.md", ignoreCase = true) }
                    }
                }
            } catch (failure: Exception) {
                log.warn("Could not inspect OpenCode config for an existing Ask agent in $directory", failure)
                true
            }
        }
    }

    @Synchronized override fun close() {
        val file = path ?: return
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.parent)
        path = null
        content = null
    }

    companion object {
        private fun containsAsk(raw: String): Boolean = runCatching {
            val config = Json.parseOrNull(ProjectPermissionConfig.stripJsonComments(raw)).asObjectOrNull()
            config == null || config.obj("agent")?.keySet()?.any { it.equals("ask", ignoreCase = true) } == true
        }.getOrDefault(true)

        private fun definition() = Json.obj(
            "description" to "Answers questions and investigates the codebase without modifying anything",
            "mode" to "primary",
            "prompt" to "Answer questions about the codebase using read-only investigation. Explain findings directly and cite relevant files and lines. Do not modify files, run shell commands, delegate work, or perform external side effects. If the user asks you to edit or implement something, do not make changes. Suggest switching to the Build agent.",
            "permission" to Json.obj(
                "*" to "deny", "read" to "allow", "glob" to "allow", "grep" to "allow",
                "list" to "allow", "lsp" to "allow", "skill" to "allow", "webfetch" to "allow",
                "websearch" to "allow", "question" to "allow",
            ),
        )
    }
}
