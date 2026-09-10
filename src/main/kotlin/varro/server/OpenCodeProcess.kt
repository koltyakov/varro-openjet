package varro.server

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Callbacks the lifecycle coordinator installs on a launched process. */
interface ProcessLaunchCallbacks {
    fun onStdout(text: String)
    fun onStderr(text: String)
    fun onExit(exitCode: Int)
}

/**
 * Owns the `opencode serve` child process.
 *
 * Port of the process half of `src/extension/open-code-process.ts`. Upstream's
 * cross-host ownership leases are deliberately simplified: a JetBrains project
 * is the only owner of a server it spawns, and a server already listening on the
 * port is adopted read-only rather than taken over. What is kept is the
 * behaviour users notice - the port-in-use retry walk, bounded output capture,
 * and a graceful-then-forced shutdown.
 */
class OpenCodeProcess(
    private val cli: OpenCodeCli,
    private val configuredPort: () -> Int,
    private val workspaceCwd: () -> String?,
) {
    private val log = logger<OpenCodeProcess>()

    private val currentPort = AtomicInteger(0)
    private val portRetries = AtomicInteger(0)
    private val portInUseDetected = AtomicBoolean(false)
    private val handler = AtomicReference<OSProcessHandler?>(null)
    private val managed = AtomicBoolean(false)
    private val capturedOutput = AtomicReference("")

    val port: Int get() = currentPort.get().takeIf { it > 0 } ?: configuredPort()

    val isManaged: Boolean get() = managed.get()

    val isRunning: Boolean get() = handler.get()?.isProcessTerminated == false

    /** Server output captured so far, bounded so a chatty CLI cannot grow without limit. */
    val output: String get() = capturedOutput.get()

    fun resetPortState() {
        currentPort.set(configuredPort())
        portRetries.set(0)
        portInUseDetected.set(false)
    }

    fun setPortInUseDetected(value: Boolean) = portInUseDetected.set(value)

    fun hasPortInUseDetected(): Boolean = portInUseDetected.get()

    /**
     * Walks to the next candidate port after an `EADDRINUSE`. Returns `false`
     * once the walk is exhausted, so the caller surfaces a real error instead of
     * retrying forever.
     */
    fun tryAdvancePort(): Boolean {
        if (portRetries.get() >= MAX_PORT_RETRIES) return false
        val next = port + 1
        if (next > 65_535) return false
        portRetries.incrementAndGet()
        currentPort.set(next)
        portInUseDetected.set(false)
        log.info("Retrying OpenCode server startup on port $next")
        return true
    }

    /**
     * Spawns `opencode serve`. The caller is responsible for having checked
     * health first - adopting an already-running server is cheaper and avoids
     * fighting another IDE window over the port.
     */
    fun launch(callbacks: ProcessLaunchCallbacks): OSProcessHandler {
        check(!isRunning) { "Cannot launch OpenCode while a managed child is still running" }

        if (currentPort.get() <= 0) currentPort.set(configuredPort())
        val info = cli.resolve()
        if (!info.found) throw OpenCodeCliMissingException(info)

        val launchPort = currentPort.get()
        val commandLine = cli.launchCommandLine(info.command, listOf("serve", "--port", launchPort.toString()))
        log.info("Starting OpenCode server: ${info.command} serve --port $launchPort")

        val general = GeneralCommandLine(commandLine).apply {
            workspaceCwd()?.let { withWorkDirectory(it) }
            withEnvironment(cli.serverEnvironment())
            // The child must not inherit the IDE's own environment filtering;
            // OpenCode shells out to git, node and the user's tools.
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            charset = Charsets.UTF_8
        }

        capturedOutput.set("")
        val processHandler = OSProcessHandler(general)
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                capturedOutput.updateAndGet { appendBounded(it, text) }
                if (outputType.toString() == "stderr") callbacks.onStderr(text) else callbacks.onStdout(text)
            }

            override fun processTerminated(event: ProcessEvent) {
                managed.set(false)
                callbacks.onExit(event.exitCode)
            }
        })

        handler.set(processHandler)
        managed.set(true)
        processHandler.startNotify()
        return processHandler
    }

    /**
     * Stops a server this project started. Graceful first so OpenCode can flush
     * session state, then forced once the grace window elapses.
     */
    fun stop(gracePeriodMs: Long = GRACEFUL_SHUTDOWN_MS) {
        val processHandler = handler.getAndSet(null) ?: return
        managed.set(false)
        if (processHandler.isProcessTerminated) return

        runCatching { processHandler.destroyProcess() }
        if (!processHandler.waitFor(gracePeriodMs)) {
            log.warn("OpenCode server did not exit within ${gracePeriodMs}ms; forcing termination")
            // OpenCode spawns helpers, so the whole tree has to go: killing only the
            // parent leaves a child holding the port and the next start collides.
            runCatching {
                processHandler.process.descendants().forEach { it.destroyForcibly() }
                processHandler.process.destroyForcibly()
            }
            runCatching { processHandler.process.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    /** Upgrades the CLI in place. Returns the combined output for diagnostics. */
    fun upgrade(): UpgradeResult {
        val info = cli.resolve()
        val command = info.installMethod.upgradeCommand
            ?: return UpgradeResult(
                succeeded = false,
                output = "No automatic upgrade is available for this install (${info.installMethod.id}).",
            )

        // `opencode upgrade` is preferred when the install method supports it,
        // but an install placed by a package manager has to be upgraded by that
        // manager or the binary and its metadata drift apart.
        val commandLine = GeneralCommandLine(splitShellCommand(command)).apply {
            withEnvironment(cli.serverEnvironment())
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            charset = Charsets.UTF_8
        }

        return runCatching {
            val output = com.intellij.execution.process.CapturingProcessHandler(commandLine)
                .runProcess(UPGRADE_TIMEOUT_MS.toInt())
            if (output.isTimeout) {
                UpgradeResult(false, "`$command` timed out after ${UPGRADE_TIMEOUT_MS / 1000}s.")
            } else {
                cli.clearCache()
                UpgradeResult(output.exitCode == 0, (output.stdout + output.stderr).takeLast(MAX_CAPTURED_OUTPUT)
                    .ifBlank { "`$command` finished." })
            }
        }.getOrElse { failure ->
            UpgradeResult(false, failure.message ?: "`$command` failed.")
        }
    }

    data class UpgradeResult(val succeeded: Boolean, val output: String)

    companion object {
        private const val MAX_PORT_RETRIES = 10
        private const val GRACEFUL_SHUTDOWN_MS = 5_000L
        private const val UPGRADE_TIMEOUT_MS = 180_000L
        private const val MAX_CAPTURED_OUTPUT = 64 * 1024

        /**
         * Keeps the newest output within a fixed budget. Port of
         * `appendBoundedCliOutput`: diagnostics only ever need the tail, and an
         * unbounded buffer is a slow leak for a long-lived server.
         */
        fun appendBounded(current: String, chunk: String): String {
            val combined = current + chunk
            return if (combined.length <= MAX_CAPTURED_OUTPUT) {
                combined
            } else {
                combined.substring(combined.length - MAX_CAPTURED_OUTPUT)
            }
        }

        private val PORT_IN_USE = Regex(
            """\bEADDRINUSE\b|address already in use|port .* (already )?in use|""" +
                """only one usage of each socket address""",
            RegexOption.IGNORE_CASE,
        )

        fun isPortInUseMessage(text: String): Boolean = PORT_IN_USE.containsMatchIn(text)

        /** Minimal shell-ish split; upgrade commands are fixed constants, not user input. */
        private fun splitShellCommand(command: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            for (char in command) {
                when {
                    quote != null && char == quote -> quote = null
                    quote != null -> current.append(char)
                    char == '"' || char == '\'' -> quote = char
                    char.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            parts.add(current.toString())
                            current.setLength(0)
                        }
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) parts.add(current.toString())
            return parts
        }
    }
}

/** Raised when the CLI cannot be located; carries what the UI needs to repair it. */
class OpenCodeCliMissingException(val info: OpenCodeCommandInfo) : RuntimeException(
    if (info.configuredCommandMissing) {
        "The configured OpenCode command was not found: ${info.configuredCommand}"
    } else {
        OpenCodeCli.MISSING_CLI_MESSAGE
    },
)
