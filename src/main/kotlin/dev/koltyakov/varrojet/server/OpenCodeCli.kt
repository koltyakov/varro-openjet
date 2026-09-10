package dev.koltyakov.varrojet.server

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * How OpenCode was installed. This is what separates "OpenCode is not
 * installed" from "the path you configured does not exist" and from
 * "it is installed but not where we looked" - three failures that need three
 * different repair instructions in the UI.
 *
 * Port of `src/shared/opencode-install.ts`.
 */
enum class OpenCodeInstallMethod(val id: String, val upgradeCommand: String?) {
    CURL("curl", "curl -fsSL https://opencode.ai/install | bash"),
    NPM("npm", "npm install -g opencode-ai@latest"),
    PNPM("pnpm", "pnpm add -g opencode-ai@latest"),
    BUN("bun", "bun add -g opencode-ai@latest"),
    YARN("yarn", "yarn global add opencode-ai@latest"),
    HOMEBREW("brew", "brew upgrade sst/tap/opencode"),
    SCOOP("scoop", "scoop update opencode"),
    CHOCOLATEY("choco", "choco upgrade opencode"),
    ARCH("arch", null),
    CONFIGURED("configured", null),
    UNKNOWN("unknown", null),
}

/** Outcome of locating the CLI on disk. */
data class OpenCodeCommandInfo(
    val command: String,
    val found: Boolean,
    val configuredCommand: String,
    val installMethod: OpenCodeInstallMethod,
    val searchedPaths: List<String>,
) {
    val configuredCommandMissing: Boolean get() = configuredCommand.isNotBlank() && !found
}

/**
 * Locates and interrogates the OpenCode CLI.
 *
 * Port of the discovery half of `src/extension/open-code-process.ts` plus
 * `src/extension/util/server-path.ts`. The JetBrains IDE is launched from a
 * desktop shell just like VS Code is, so it inherits the same truncated `PATH`
 * and needs the same explicit list of global install locations.
 */
class OpenCodeCli(
    private val configuredCommand: () -> String,
    private val workingDirectory: () -> String?,
    private val environment: Map<String, String> = System.getenv(),
) {
    private val log = logger<OpenCodeCli>()

    @Volatile
    private var cachedInfo: Pair<String, OpenCodeCommandInfo>? = null

    @Volatile
    private var cachedVersion: String? = null

    /**
     * Drops the memoized lookup so a CLI installed while the IDE was open is
     * picked up. The cache key only covers the inputs that identify a lookup,
     * which do not change when the user installs OpenCode from the panel's own
     * terminal button.
     */
    fun clearCache() {
        cachedInfo = null
        cachedVersion = null
    }

    fun resolve(): OpenCodeCommandInfo {
        val configured = configuredCommand().trim()
        val key = "$configured|${workingDirectory().orEmpty()}"
        cachedInfo?.let { (cachedKey, info) -> if (cachedKey == key) return info }

        val info = locate(configured)
        cachedInfo = key to info
        return info
    }

    private fun locate(configured: String): OpenCodeCommandInfo {
        if (configured.isNotEmpty()) {
            // A configured value containing a separator is a path, not a name to
            // search for. Resolving it relative to the project keeps
            // `./tools/opencode` working the way it does in VS Code.
            if (configured.contains('/') || configured.contains('\\')) {
                val path = Paths.get(configured).let { candidate ->
                    if (candidate.isAbsolute) candidate
                    else Paths.get(workingDirectory() ?: System.getProperty("user.dir")).resolve(candidate).normalize()
                }
                return OpenCodeCommandInfo(
                    command = path.toString(),
                    found = Files.exists(path),
                    configuredCommand = configured,
                    installMethod = OpenCodeInstallMethod.CONFIGURED,
                    searchedPaths = emptyList(),
                )
            }

            val candidates = windowsCandidates(configured)
            for (directory in searchPath()) {
                for (candidate in candidates) {
                    val file = File(directory, candidate)
                    if (file.exists()) {
                        return OpenCodeCommandInfo(
                            command = file.absolutePath,
                            found = true,
                            configuredCommand = configured,
                            installMethod = OpenCodeInstallMethod.CONFIGURED,
                            searchedPaths = searchPath(),
                        )
                    }
                }
            }
            return OpenCodeCommandInfo(
                command = configured,
                found = false,
                configuredCommand = configured,
                installMethod = OpenCodeInstallMethod.CONFIGURED,
                searchedPaths = searchPath(),
            )
        }

        val candidates = if (SystemInfo.isWindows) {
            listOf("opencode.exe", "opencode.cmd", "opencode.bat")
        } else {
            listOf("opencode")
        }

        for (directory in searchPath()) {
            for (candidate in candidates) {
                val file = File(directory, candidate)
                if (file.exists()) {
                    return OpenCodeCommandInfo(
                        command = file.absolutePath,
                        found = true,
                        configuredCommand = "",
                        installMethod = detectInstallMethod(resolveLinkTarget(file.absolutePath)),
                        searchedPaths = searchPath(),
                    )
                }
            }
        }

        return OpenCodeCommandInfo(
            command = if (SystemInfo.isWindows) "opencode.cmd" else "opencode",
            found = false,
            configuredCommand = "",
            installMethod = OpenCodeInstallMethod.UNKNOWN,
            searchedPaths = searchPath(),
        )
    }

    private fun windowsCandidates(command: String): List<String> =
        if (SystemInfo.isWindows && !command.matches(Regex(""".*\.(exe|cmd|bat)$""", RegexOption.IGNORE_CASE))) {
            listOf(command, "$command.exe", "$command.cmd", "$command.bat")
        } else {
            listOf(command)
        }

    /**
     * Directories searched for the CLI: the inherited `PATH`, then the global
     * install locations a desktop-launched IDE does not see.
     *
     * Version managers that scope globals per Node version (fnm, nvm, asdf)
     * cannot be enumerated statically; those installs are covered by the
     * explicit command setting.
     */
    fun searchPath(): List<String> {
        val home = environment["HOME"] ?: environment["USERPROFILE"]
        val pathEntries = (environment[pathVariableKey()] ?: "")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }

        val extras = if (SystemInfo.isWindows) {
            listOfNotNull(
                environment["PNPM_HOME"],
                environment["APPDATA"]?.let { join(it, "npm") },
                environment["LOCALAPPDATA"]?.let { join(it, "pnpm") },
                environment["LOCALAPPDATA"]?.let { join(it, "Yarn", "bin") },
                environment["VOLTA_HOME"]?.let { join(it, "bin") },
                home?.let { join(it, ".opencode", "bin") },
                home?.let { join(it, ".bun", "bin") },
                home?.let { join(it, ".yarn", "bin") },
                home?.let { join(it, ".volta", "bin") },
            )
        } else {
            listOfNotNull(
                home?.let { join(it, ".opencode", "bin") },
                home?.let { join(it, ".npm-global", "bin") },
                home?.let { join(it, ".local", "bin") },
                home?.let { join(it, ".bun", "bin") },
                home?.let { join(it, "Library", "pnpm") },
                environment["PNPM_HOME"],
                environment["VOLTA_HOME"]?.let { join(it, "bin") },
                home?.let { join(it, ".volta", "bin") },
                environment["N_PREFIX"]?.let { join(it, "bin") },
                "/opt/homebrew/bin",
                "/usr/local/bin",
            )
        }

        return (pathEntries + extras).filter { it.isNotBlank() }.distinct()
    }

    /**
     * `PATH` on Windows is case-insensitive in the process environment but not
     * in a Java map, so the existing spelling has to be preserved.
     */
    fun pathVariableKey(): String {
        if (!SystemInfo.isWindows) return "PATH"
        return environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path"
    }

    /**
     * Environment for the server process: the inherited environment with `PATH`
     * replaced by the fully expanded search list, so OpenCode itself can find
     * the tools it shells out to.
     */
    fun serverEnvironment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = environment.toMutableMap()
        env.keys.filter { it.equals("PATH", ignoreCase = true) }.forEach(env::remove)
        env[pathVariableKey()] = searchPath().joinToString(File.pathSeparator)
        env.putAll(extra)
        return env
    }

    /**
     * Builds the command line to spawn. Windows `.cmd`/`.bat` shims are not
     * executable images, so they have to run through `cmd /c`.
     *
     * Port of `src/extension/util/server-launch.ts`.
     */
    fun launchCommandLine(command: String, args: List<String>): List<String> {
        if (SystemInfo.isWindows && command.matches(Regex(""".*\.(cmd|bat)$""", RegexOption.IGNORE_CASE))) {
            val comSpec = environment["ComSpec"] ?: "cmd.exe"
            val joined = (listOf(command) + args).joinToString(" ") { quoteCmdArgument(it) }
            return listOf(comSpec, "/d", "/s", "/c", joined)
        }
        return listOf(command) + args
    }

    /** Installed CLI version, or `null` when the CLI is missing or mute. */
    fun readInstalledVersion(): String? {
        cachedVersion?.let { return it }
        val info = resolve()
        if (!info.found) return null

        val version = runCatching {
            val process = ProcessBuilder(launchCommandLine(info.command, listOf("--version")))
                .redirectErrorStream(true)
                .also { it.environment().putAll(serverEnvironment()) }
                .start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().readText()
            VERSION_PATTERN.find(output)?.value
        }.onFailure { log.warn("Failed to read OpenCode CLI version", it) }.getOrNull()

        cachedVersion = version
        return version
    }

    private fun resolveLinkTarget(command: String): String =
        runCatching { Paths.get(command).toRealPath().toString() }.getOrDefault(command)

    /**
     * Infers the package manager from where the binary actually lives.
     * `/opt/homebrew/bin/opencode` is the same path whether Homebrew or an npm
     * global under Homebrew's Node put it there; only the link target
     * (Cellar vs node_modules) tells them apart, and recommending
     * `brew upgrade` for an npm install fails outright.
     */
    private fun detectInstallMethod(resolvedCommand: String): OpenCodeInstallMethod {
        val normalized = resolvedCommand.replace('\\', '/')
        return when {
            normalized.contains("/Cellar/") || normalized.contains("/homebrew/") -> OpenCodeInstallMethod.HOMEBREW
            normalized.contains("/.bun/") -> OpenCodeInstallMethod.BUN
            normalized.contains("/pnpm/") || normalized.contains("/pnpm-global/") -> OpenCodeInstallMethod.PNPM
            normalized.contains("/.yarn/") || normalized.contains("/Yarn/") -> OpenCodeInstallMethod.YARN
            normalized.contains("/node_modules/") -> OpenCodeInstallMethod.NPM
            normalized.contains("/scoop/") -> OpenCodeInstallMethod.SCOOP
            normalized.contains("/chocolatey/") -> OpenCodeInstallMethod.CHOCOLATEY
            normalized.contains("/.opencode/bin") -> OpenCodeInstallMethod.CURL
            normalized.startsWith("/usr/bin/") -> OpenCodeInstallMethod.ARCH
            else -> OpenCodeInstallMethod.UNKNOWN
        }
    }

    private fun join(vararg parts: String): String =
        Path.of(parts.first(), *parts.drop(1).toTypedArray()).toString()

    companion object {
        /** Message shown when the CLI cannot be found anywhere. */
        const val MISSING_CLI_MESSAGE: String =
            "OpenCode CLI was not found. Install it with `npm install -g opencode-ai`, " +
                "or set the OpenCode command in Settings | Tools | Varro."

        private val VERSION_PATTERN = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.\-]+)?""")

        /** Lowest OpenCode release whose APIs Varro relies on. */
        const val MINIMUM_SUPPORTED_VERSION: String = "1.16.0"

        private val MISSING_CLI_MARKERS = listOf(
            "enoent",
            "command not found",
            "is not recognized as an internal or external command",
            "no such file or directory",
        )

        fun isMissingCliFailure(text: String): Boolean {
            val normalized = text.lowercase()
            return MISSING_CLI_MARKERS.any { normalized.contains(it) }
        }

        /**
         * Compares dotted versions, ignoring any pre-release suffix.
         * Returns a negative number when [left] is older than [right].
         */
        fun compareVersions(left: String, right: String): Int {
            val a = numericParts(left)
            val b = numericParts(right)
            for (index in 0 until maxOf(a.size, b.size)) {
                val diff = (a.getOrElse(index) { 0 }) - (b.getOrElse(index) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }

        private fun numericParts(version: String): List<Int> =
            version.trim().substringBefore('-').split('.').map { part ->
                part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
            }

        private fun quoteCmdArgument(value: String): String {
            if (value.isEmpty()) return "\"\""
            if (!value.any { it.isWhitespace() || it in "\"&()<>^|" }) return value
            return "\"${value.replace("\"", "\"\"")}\""
        }
    }
}
