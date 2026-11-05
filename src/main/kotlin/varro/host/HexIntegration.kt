package varro.host

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.Strictness
import com.intellij.openapi.application.ApplicationManager
import varro.host.HandyIntegration.Action
import varro.host.HandyIntegration.Status
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Read-only discovery and fixed, idempotent control commands. Never launches the Hex listener. */
internal class HexIntegration(
    private val runtime: Runtime = Runtime(),
    private val localHost: () -> Boolean = { !IdeHostEnvironment.current.isRemoteHost() },
    private val executor: Executor = Executor { ApplicationManager.getApplication().executeOnPooledThread(it) },
    private val watchIntervalMs: Long = 1_000,
) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "varro-hex-state").apply { isDaemon = true } }
    private val revision = AtomicLong()
    private val generation = AtomicLong()
    private val json = GsonBuilder().setStrictness(Strictness.STRICT).create()
    @Volatile private var disposed = false

    fun request(action: Action, onState: ((Status) -> Unit)? = null): CompletableFuture<Status> {
        if (disposed || !localHost()) return CompletableFuture.completedFuture(Status(false, false))
        if (action != Action.STATUS) generation.incrementAndGet()
        return CompletableFuture.supplyAsync({
            var status = Status(false, false)
            try {
                val executable = findExecutable() ?: return@supplyAsync status
                if (disposed) return@supplyAsync status
                status = Status(true, false)
                status = readStatus(executable)
                if (action == Action.STATUS || disposed) return@supplyAsync status
                if (!status.running) return@supplyAsync status.copy(error = "Start Hex and configure its model and permissions first.")
                val reply = runCli(executable, listOf("dictation", action.wireName, "--json"))
                check(reply.str("status") == "done" || (reply.str("status") == "error" && reply.str("code") == "discarded")) {
                    reply.str("message") ?: "Unexpected Hex dictation reply. Update Hex to a build with control CLI support."
                }
                status = status.copy(state = if (action == Action.START) "recording" else "idle")
                status = readStatus(executable)
                if (onState != null && status.running && status.state in setOf("recording", "processing")) watch(executable, generation.get(), onState)
                status
            } catch (failure: Exception) {
                status.copy(error = "Hex ${action.wireName} failed: ${failure.message}")
            }
        }, executor)
    }

    private fun watch(executable: Path, epoch: Long, onState: (Status) -> Unit) {
        if (disposed || generation.get() != epoch) return
        scheduler.schedule({
            val status = runCatching { readStatus(executable) }.getOrNull() ?: return@schedule
            if (disposed || generation.get() != epoch) return@schedule
            onState(status)
            if (status.running && status.state in setOf("recording", "processing")) watch(executable, epoch, onState)
        }, watchIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun readStatus(executable: Path): Status {
        val sequence = revision.incrementAndGet()
        val reply = runCli(executable, listOf("status", "--json"))
        if (reply.str("status") == "error") {
            if (reply.str("code") == "not-running") return Status(true, false, revision = sequence)
            error(reply.str("message") ?: "Invalid Hex error reply")
        }
        val running = reply.obj("listener").bool("running")
        val state = reply.obj("capture").str("state")
        check(reply.str("status") == "status" && running != null && state in setOf("idle", "recording", "transcribing", "unknown")) {
            "Invalid Hex status reply. Update Hex to a build with control CLI support."
        }
        return Status(true, running, state = if (state == "transcribing") "processing" else state!!, revision = sequence)
    }

    private fun runCli(executable: Path, args: List<String>): com.google.gson.JsonObject {
        val result = runtime.run(listOf(executable.toString()) + args, if (args.first() == "status") 2_000 else 12_000)
        val reply = runCatching { json.fromJson(result.output, JsonElement::class.java).asObjectOrNull() }.getOrNull()
        val version = reply.num("version")
        check(version != null && version.isFinite() && version >= 1 && version <= 9_007_199_254_740_991.0 && version % 1.0 == 0.0) {
            "Invalid Hex CLI reply. Update Hex to a build with control CLI support."
        }
        check(result.exitCode == 0 || (reply.str("status") == "error" && reply.str("code") != null && reply.str("message") != null)) {
            "Hex command exited with code ${result.exitCode}."
        }
        return reply!!
    }

    internal fun findExecutable(): Path? {
        val deadline = runtime.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        fun checkDeadline() = check(runtime.nanoTime() < deadline) { "Hex discovery timed out." }
        val standard = when {
            runtime.mac -> listOf(Path.of("/Applications/Hex.app/Contents/MacOS/hex"), Path.of(runtime.home, "Applications/Hex.app/Contents/MacOS/hex"))
            runtime.windows -> runtime.environment["APPDATA"]?.let { root ->
                val directory = Path.of(root, "hex")
                val version = runtime.readVersion(directory.resolve("current-version"))?.trim()
                if (version != null && version.length <= 128 && Regex("^[\\w.-]+$").matches(version) && version !in setOf(".", ".."))
                    listOf(directory.resolve("versions").resolve(version).resolve("hex.exe")) else emptyList()
            }.orEmpty()
            else -> listOf(Path.of(runtime.home, ".local/bin/hex"), Path.of("/usr/bin/hex"), Path.of("/usr/local/bin/hex"))
        }
        val name = if (runtime.windows) "hex.exe" else "hex"
        val paths = runtime.environment["PATH"].orEmpty().split(if (runtime.windows) ';' else ':')
            .filter(String::isNotEmpty).map(Path::of).filter(Path::isAbsolute).map { it.resolve(name) }
        for (candidate in (standard + paths).distinct()) {
            checkDeadline()
            val exists = runCatching { runtime.isExecutable(candidate) }.getOrDefault(false)
            checkDeadline()
            if (exists) return candidate
        }
        checkDeadline()
        return null
    }

    override fun close() { disposed = true; generation.incrementAndGet(); scheduler.shutdownNow() }

    internal open class Runtime(os: String = System.getProperty("os.name"), val home: String = System.getProperty("user.home"),
                                val environment: Map<String, String> = System.getenv()) {
        val windows = os.lowercase().contains("windows")
        val mac = os.lowercase().contains("mac")
        open fun nanoTime(): Long = System.nanoTime()
        open fun isExecutable(path: Path) = Files.isRegularFile(path) && (windows || Files.isExecutable(path))
        open fun readVersion(path: Path): String? = runCatching {
            Files.newInputStream(path).use { it.readNBytes(130).toString(Charsets.UTF_8) }
        }.getOrNull()
        open fun run(command: List<String>, timeoutMs: Long): HandyIntegration.CommandResult {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val process = ProcessBuilder(command).start()
            process.outputStream.close()
            val output = CompletableFuture.supplyAsync { process.inputStream.use {
                val bytes = it.readNBytes(1024 * 1024 + 1)
                check(bytes.size <= 1024 * 1024) { "Hex command output exceeded the safety limit." }
                bytes.toString(Charsets.UTF_8)
            } }
            val errors = CompletableFuture.runAsync { process.errorStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) } }
            try {
                check(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { "Hex command timed out." }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Hex command timed out." }
                return HandyIntegration.CommandResult(process.exitValue(), output.get(remaining, TimeUnit.NANOSECONDS))
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close(); process.errorStream.close()
                output.cancel(true); errors.cancel(true)
            }
        }
    }
}
