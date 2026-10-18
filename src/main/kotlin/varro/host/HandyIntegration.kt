package varro.host

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import varro.protocol.Json
import varro.protocol.str
import varro.settings.VarroSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** One command guard is shared by all projects and composer views in this IDE. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.APP)
internal class HandyIntegration(
    private val configuredPath: () -> String = { VarroSettings.getInstance().dictationHandyPath },
    private val localHost: () -> Boolean = { !IdeHostEnvironment.current.isRemoteHost() },
    private val runtime: Runtime = Runtime(),
    private val executor: Executor = Executor { ApplicationManager.getApplication().executeOnPooledThread(it) },
) : com.intellij.openapi.Disposable {
    enum class Action(val wireName: String) { STATUS("status"), START("start"), STOP("stop"), CANCEL("cancel") }
    data class Request(val requestId: String, val action: Action)
    data class Status(val available: Boolean, val running: Boolean, val error: String? = null, val state: String = "idle", val revision: Long = 0) {
        fun payload(requestId: String): JsonObject = Json.obj(
            "requestId" to requestId, "available" to available, "running" to running, "state" to state, "revision" to revision,
        ).apply { error?.let { addProperty("error", it) } }
    }

    private val lock = Any()
    private var pending: CompletableFuture<Status>? = null
    private var pendingAction: Action? = null
    @Volatile private var requestedState = "idle"
    @Volatile private var logPath: Path? = null
    @Volatile private var disposed = false
    private val recording = HandyRecordingState { logPath }

    fun handleRequest(payload: JsonObject?, post: (JsonObject) -> Unit) {
        val parsed = parseRequest(payload) ?: return
        request(parsed.action) { result -> post(Json.message("handy/result", result.payload(parsed.requestId))) }
            .thenAccept { result -> post(Json.message("handy/result", result.payload(parsed.requestId))) }
    }

    fun request(action: Action, onState: ((Status) -> Unit)? = null): CompletableFuture<Status> {
        if (!localHost() || disposed) return CompletableFuture.completedFuture(Status(false, false))
        synchronized(lock) {
            pending?.let { operation ->
                if (action != Action.STATUS && pendingAction == Action.STATUS)
                    return operation.thenCompose { request(action, onState) }
                return operation.thenApply { status ->
                    status.copy(error = if (action == Action.STATUS) null else "Another Handy command is still pending.")
                }
            }
            val operation = CompletableFuture<Status>()
            pending = operation
            pendingAction = action
            try {
                executor.execute {
                    val status = performRequest(action)
                    if (!disposed && action != Action.STATUS) {
                        if (status.state in setOf("recording", "processing") && onState != null) recording.watch { state ->
                            if (state != "unknown") requestedState = if (state == "recording") "recording" else "idle"
                            onState(status(status.available, status.running))
                        } else if (status.state == "idle") recording.stopWatching()
                    }
                    synchronized(lock) { if (pending === operation) pending = null }
                    operation.complete(status)
                }
            } catch (failure: Exception) {
                pending = null
                operation.complete(Status(false, false, "Handy ${action.wireName} failed: ${failure.message}"))
            }
            return operation
        }
    }

    override fun dispose() { disposed = true; recording.close() }

    private fun status(available: Boolean, running: Boolean, error: String? = null) = Status(available, running, error,
        if (recording.state == "unknown") requestedState else recording.state, recording.revision)

    private fun performRequest(action: Action): Status {
        var available = false
        var running = false
        return try {
            val executable = findExecutable() ?: run { requestedState = "idle"; recording.reset("idle"); return status(false, false) }
            available = true
            running = runtime.isRunning()
            if (!running) {
                requestedState = "idle"; recording.reset("idle")
                return status(available, false, if (action == Action.STATUS) null else "Start Handy and configure its model and permissions first.")
            }
            if (action == Action.STATUS) return status(available, running)
            resolveLogPath(executable)
            recording.read()
            if (disposed) return status(available, running)
            val state = status(available, running).state
            if ((action == Action.START && state == "recording") || (action in setOf(Action.STOP, Action.CANCEL) && state == "idle")) {
                requestedState = if (state == "recording") "recording" else "idle"
                return status(available, running)
            }
            val observation = recording.observation
            val result = runtime.run(listOf(executable.toString(), if (action == Action.CANCEL) "--cancel" else "--toggle-transcription"))
            check(result.exitCode == 0) { "Handy command exited with code ${result.exitCode}." }
            requestedState = if (action == Action.START) "recording" else "idle"
            recording.read()
            if (recording.observation == observation || recording.state == "unknown") recording.assume(requestedState)
            status(available, running)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            status(available, running, "Handy ${action.wireName} failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun resolveLogPath(executable: Path) {
        val directory = executable.parent
        val portable = Files.isRegularFile(directory.resolve("portable")) && Files.isDirectory(directory.resolve("Data"))
        val path = if (portable) directory.resolve("Data/logs/handy.log") else when {
            runtime.mac -> Path.of(runtime.home, "Library/Logs/com.pais.handy/handy.log")
            runtime.windows -> runtime.environment["LOCALAPPDATA"]?.let { Path.of(it, "com.pais.handy/logs/handy.log") }
            else -> Path.of(runtime.environment["XDG_DATA_HOME"] ?: Path.of(runtime.home, ".local/share").toString(), "com.pais.handy/logs/handy.log")
        }
        if (path != logPath) recording.reset("unknown")
        logPath = path
    }

    private fun findExecutable(): Path? {
        val deadline = runtime.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TIMEOUT_MS)
        fun checkDeadline() = check(runtime.nanoTime() < deadline) { "Handy discovery timed out." }
        val configured = configuredPath().trim()
        require(configured.isEmpty() || Path.of(configured).isAbsolute) { "Handy path must be an absolute executable path." }
        val name = if (runtime.windows) "handy.exe" else "handy"
        val standard = when {
            runtime.mac -> listOf(Path.of("/Applications/Handy.app/Contents/MacOS/Handy"),
                Path.of(runtime.home, "Applications/Handy.app/Contents/MacOS/Handy"))
            runtime.windows -> listOf("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)")
                .mapNotNull { runtime.environment[it]?.takeIf(String::isNotBlank) }
                .map { Path.of(it, "Handy", name) }
            else -> listOf(Path.of(runtime.home, ".local/bin/handy"), Path.of("/usr/bin/handy"), Path.of("/usr/local/bin/handy"))
        }
        val candidates = if (configured.isNotEmpty()) listOf(Path.of(configured)) else standard +
            runtime.environment["PATH"].orEmpty().split(if (runtime.windows) ';' else ':')
                .filter(String::isNotEmpty).map(Path::of).filter(Path::isAbsolute).map { it.resolve(name) }
        for (candidate in candidates.distinct()) {
            checkDeadline()
            val executable = runCatching { runtime.isExecutable(candidate) }.getOrDefault(false)
            // Filesystem reads cannot activate dictation after the discovery deadline.
            checkDeadline()
            if (executable) return candidate
        }
        checkDeadline()
        return null
    }

    internal open class Runtime(
        os: String = System.getProperty("os.name"),
        val home: String = System.getProperty("user.home"),
        val environment: Map<String, String> = System.getenv(),
    ) {
        val windows = os.lowercase().contains("windows")
        val mac = os.lowercase().contains("mac")
        open fun nanoTime(): Long = System.nanoTime()
        open fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && (windows || Files.isExecutable(path))
        open fun userId(): String = com.sun.security.auth.module.UnixSystem().uid.toString()

        fun isRunning(): Boolean {
            val result = if (windows) run(listOf("tasklist.exe", "/FI", "IMAGENAME eq handy.exe", "/FO", "CSV", "/NH"))
            else run(listOf("/usr/bin/pgrep", "-u", userId(), "-x", "Handy|handy"))
            if (!windows && result.exitCode == 1) return false
            check(result.exitCode == 0) { "Handy process inspection exited with code ${result.exitCode}." }
            return if (windows) Regex("^\"handy\\.exe\",", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(result.output)
            else Regex("^\\d+$", RegexOption.MULTILINE).containsMatchIn(result.output.trim())
        }

        /** No shell, retries, or app startup. Only a newly spawned CLI child is terminated on timeout. */
        open fun run(command: List<String>): CommandResult {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TIMEOUT_MS)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = CompletableFuture.supplyAsync {
                process.inputStream.use { stream ->
                    val bytes = stream.readNBytes(MAX_OUTPUT_BYTES + 1)
                    check(bytes.size <= MAX_OUTPUT_BYTES) { "Handy command output exceeded the safety limit." }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            try {
                val wait = deadline - System.nanoTime()
                check(wait > 0 && process.waitFor(wait, TimeUnit.NANOSECONDS)) { "Handy command timed out." }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Handy command timed out." }
                return CommandResult(process.exitValue(), output.get(remaining, TimeUnit.NANOSECONDS))
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                output.cancel(true)
            }
        }
    }

    data class CommandResult(val exitCode: Int, val output: String = "")

    companion object {
        private const val PROCESS_TIMEOUT_MS = 2_000L
        private const val MAX_OUTPUT_BYTES = 64 * 1024

        fun parseRequest(payload: JsonObject?): Request? {
            val id = payload.str("requestId")?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
            val action = Action.entries.firstOrNull { it.wireName == payload.str("action") } ?: return null
            return Request(id, action)
        }
    }
}
