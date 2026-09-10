package varro.server

import com.google.gson.JsonElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import varro.settings.VarroSettings
import varro.protocol.asObjectOrNull
import varro.protocol.str
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates OpenCode startup, adoption, compatibility and restart.
 *
 * Port of `src/extension/server.ts` + `server-lifecycle.ts`. Startup is lazy the
 * same way it is upstream: constructing this object does nothing, and
 * [ensureStarted] issues the real start the first time the tool window needs it.
 * That keeps IDE startup cheap and lets several project windows share one server.
 *
 * Start sequence, in order:
 *  1. Probe health. A server already listening is adopted, so a second project
 *     window never fights the first one for the port.
 *  2. Verify the CLI exists, reporting *which* of the three missing-CLI failures
 *     it is so the UI can give the right repair instruction.
 *  3. Check the version floor. An older CLI is reported as `update-required`
 *     rather than failing with an opaque API error later.
 *  4. Spawn, then poll health until it answers or the attempt times out. An
 *     `EADDRINUSE` during a managed start walks to the next port.
 *  5. Open the event stream.
 */
class OpenCodeServer(
    private val settings: VarroSettings,
    private val workspaceCwd: () -> String?,
) : Disposable {

    private val log = logger<OpenCodeServer>()

    private enum class Phase { IDLE, STARTING, RESTARTING, DISPOSING }

    val cli: OpenCodeCli = OpenCodeCli(
        configuredCommand = { settings.serverCommand },
        workingDirectory = workspaceCwd,
    )

    private val process = OpenCodeProcess(
        cli = cli,
        configuredPort = { settings.normalizedPort() },
        workspaceCwd = workspaceCwd,
    )

    private val status = AtomicReference<ServerStatus>(ServerStatus.Stopped)
    private val phase = AtomicReference(Phase.IDLE)
    private val disposeGeneration = AtomicInteger(0)
    private val startInFlight = AtomicBoolean(false)
    private val serverVersion = AtomicReference<String?>(null)
    @Volatile var hasHostWork: () -> Boolean = { false }
    private val maintenance = IdleMaintenance(
        enabled = { settings.serverAutoUpdate && process.isManaged && status.get() is ServerStatus.Running && phase.get() == Phase.IDLE },
        idle = ::isIdleForMaintenance,
        upgrade = {
            val result = process.upgrade()
            if (!result.succeeded) log.info("OpenCode background update failed: ${result.output.take(2000)}")
            else log.info("OpenCode background CLI update finished. The updated version will be used on the next server start.")
            result.succeeded
        },
    )
    private val maintenanceStarted = AtomicBoolean(false)

    private val statusListeners = ConcurrentHashMap.newKeySet<(ServerStatus) -> Unit>()
    private val eventListeners = ConcurrentHashMap.newKeySet<(JsonElement) -> Unit>()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "varro-opencode-server").apply { isDaemon = true }
    }

    val transport: OpenCodeTransport = OpenCodeTransport(
        getUrl = { url() },
        getWorkspaceCwd = workspaceCwd,
        getStatus = { status.get() },
        isDisposing = { phase.get() == Phase.DISPOSING || phase.get() == Phase.RESTARTING },
        updateEventStreamState = ::applyEventStreamState,
        emitEvent = { event -> eventListeners.forEach { runCatching { it(event) } } },
    )

    fun url(): String = "http://127.0.0.1:${process.port}"

    fun currentStatus(): ServerStatus = status.get()

    fun version(): String? = serverVersion.get()

    fun workspaceDirectory(): String? = transport.workspaceDirectory()

    fun isManaged(): Boolean = process.isManaged

    fun onStatus(listener: (ServerStatus) -> Unit): () -> Unit {
        statusListeners.add(listener)
        return { statusListeners.remove(listener) }
    }

    fun onEvent(listener: (JsonElement) -> Unit): () -> Unit {
        eventListeners.add(listener)
        return { eventListeners.remove(listener) }
    }

    // --- Startup --------------------------------------------------------------

    /**
     * Starts (or adopts) the server if it is not already running. Safe to call
     * from any thread and from several callers at once; concurrent calls collapse
     * onto the one in-flight attempt.
     */
    fun ensureStarted() {
        if (status.get() is ServerStatus.Running) return
        if (!startInFlight.compareAndSet(false, true)) return
        scheduler.execute {
            try {
                runStart()
            } finally {
                startInFlight.set(false)
            }
        }
    }

    private fun runStart() {
        val generation = disposeGeneration.get()
        phase.compareAndSet(Phase.IDLE, Phase.STARTING)
        setStatus(ServerStatus.Starting)

        try {
            // 1. Adopt a healthy server rather than fighting it for the port.
            val existing = transport.readHealthInfo()
            if (existing.healthy) {
                log.info("Adopting the OpenCode server already listening on ${url()}")
                serverVersion.set(existing.version)
                val compatibility = checkCompatibility(existing.version)
                if (compatibility != null) {
                    setStatus(compatibility)
                    return
                }
                finishStart(generation)
                return
            }

            if (!settings.serverAutoStart) {
                setStatus(
                    ServerStatus.Error(
                        message = "No OpenCode server is running on ${url()} and automatic startup is disabled.",
                        detail = ServerErrorDetail(
                            kind = ServerErrorKind.GENERIC,
                            blockedBy = ServerErrorBlockedBy.AUTO_START_DISABLED,
                            settingId = "varro.server.autoStart",
                            suggestedCommand = "opencode serve --port ${process.port}",
                        ),
                    ),
                )
                return
            }

            // 2. Verify the CLI before spawning, so a missing CLI reports as such.
            val info = cli.resolve()
            if (!info.found) {
                setStatus(missingCliStatus(info))
                return
            }

            // 3. Version floor.
            val installed = cli.readInstalledVersion()
            checkCompatibility(installed)?.let {
                setStatus(it)
                return
            }

            // 4. Spawn, retrying across ports while the port is taken.
            process.resetPortState()
            while (true) {
                if (disposeGeneration.get() != generation) return
                val started = spawnAndAwaitHealth(generation)
                if (started) break
                if (process.hasPortInUseDetected() && process.tryAdvancePort()) continue
                return
            }

            serverVersion.set(transport.readHealthInfo().version ?: installed)
            finishStart(generation)
        } catch (failure: OpenCodeCliMissingException) {
            setStatus(missingCliStatus(failure.info))
        } catch (failure: Exception) {
            log.warn("OpenCode server start failed", failure)
            setStatus(
                ServerStatus.Error(
                    message = failure.message ?: "OpenCode server failed to start.",
                    detail = ServerErrorDetail(kind = ServerErrorKind.GENERIC, cause = failure.message),
                ),
            )
        } finally {
            phase.compareAndSet(Phase.STARTING, Phase.IDLE)
        }
    }

    /** Spawns the process and polls health. Returns `false` when the attempt fails. */
    private fun spawnAndAwaitHealth(generation: Int): Boolean {
        process.setPortInUseDetected(false)

        process.launch(object : ProcessLaunchCallbacks {
            override fun onStdout(text: String) {
                if (OpenCodeProcess.isPortInUseMessage(text)) process.setPortInUseDetected(true)
            }

            override fun onStderr(text: String) {
                if (OpenCodeProcess.isPortInUseMessage(text)) process.setPortInUseDetected(true)
                log.debug("opencode: ${text.trimEnd()}")
            }

            override fun onExit(exitCode: Int) {
                // An exit after a successful start is a crash, not a failed
                // attempt, so it is reported rather than retried silently.
                if (disposeGeneration.get() != generation) return
                if (status.get() is ServerStatus.Running) {
                    log.warn("OpenCode server exited unexpectedly with code $exitCode")
                    transport.stopEventStream()
                    setStatus(
                        ServerStatus.Error(
                            message = "The OpenCode server exited unexpectedly (code $exitCode).",
                            detail = ServerErrorDetail(
                                kind = ServerErrorKind.GENERIC,
                                cause = process.output.takeLast(2_000).ifBlank { null },
                            ),
                        ),
                    )
                }
            }
        })

        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (disposeGeneration.get() != generation) {
                process.stop()
                return false
            }
            if (transport.checkHealth()) return true
            if (process.hasPortInUseDetected()) {
                process.stop()
                return false
            }
            if (!process.isRunning) {
                val output = process.output
                process.stop()
                if (OpenCodeCli.isMissingCliFailure(output)) throw OpenCodeCliMissingException(cli.resolve())
                setStatus(
                    ServerStatus.Error(
                        message = "The OpenCode server exited before it became healthy.",
                        detail = ServerErrorDetail(
                            kind = ServerErrorKind.GENERIC,
                            cause = output.takeLast(2_000).ifBlank { null },
                        ),
                    ),
                )
                return false
            }
            Thread.sleep(HEALTH_POLL_INTERVAL_MS)
        }

        process.stop()
        setStatus(
            ServerStatus.Error(
                message = "The OpenCode server did not become healthy within ${STARTUP_TIMEOUT_MS / 1000}s.",
                detail = ServerErrorDetail(
                    kind = ServerErrorKind.GENERIC,
                    cause = process.output.takeLast(2_000).ifBlank { null },
                ),
            ),
        )
        return false
    }

    private fun finishStart(generation: Int) {
        if (disposeGeneration.get() != generation) return
        // `degraded` until the stream actually connects; the transport promotes it
        // to healthy, so the UI never claims live updates it does not have.
        setStatus(ServerStatus.Running(url(), EventStreamState.DEGRADED))
        transport.startEventStream(OpenCodeRequestScope.normalizeDirectory(workspaceCwd()))
        if (maintenanceStarted.compareAndSet(false, true)) scheduler.scheduleWithFixedDelay({
            runCatching { maintenance.tick() }.onFailure { log.info("OpenCode maintenance check failed", it) }
        }, 60, 60, TimeUnit.SECONDS)
    }

    private fun isIdleForMaintenance(): Boolean {
        if (hasHostWork() || !transport.isQuiet()) return false
        val directories = (transport.observedSessionDirectories().values + listOfNotNull(workspaceCwd())).toSet()
        if (directories.isEmpty()) return false
        return directories.all { directory ->
            val statuses = transport.request("GET", "/session/status", options = RequestOptions(directory = directory))
                .data.asObjectOrNull() ?: return@all false
            statuses.entrySet().all { it.value.asObjectOrNull().str("type") == "idle" }
        }
    }

    private fun missingCliStatus(info: OpenCodeCommandInfo): ServerStatus.Error =
        if (info.configuredCommandMissing) {
            ServerStatus.Error(
                message = "The configured OpenCode command was not found: ${info.configuredCommand}",
                detail = ServerErrorDetail(
                    kind = ServerErrorKind.CLI_PATH_INVALID,
                    configuredCommand = info.configuredCommand,
                    settingId = "varro.server.command",
                ),
            )
        } else {
            ServerStatus.Error(
                message = OpenCodeCli.MISSING_CLI_MESSAGE,
                detail = ServerErrorDetail(
                    kind = ServerErrorKind.CLI_MISSING,
                    installMethod = info.installMethod,
                    suggestedCommand = "npm install -g opencode-ai",
                    searchedPaths = info.searchedPaths,
                ),
            )
        }

    private fun checkCompatibility(version: String?): ServerStatus.Error? {
        if (version == null) return null
        if (OpenCodeCli.compareVersions(version, OpenCodeCli.MINIMUM_SUPPORTED_VERSION) >= 0) return null
        val info = cli.resolve()
        return ServerStatus.Error(
            message = "OpenCode update required. Varro needs ${OpenCodeCli.MINIMUM_SUPPORTED_VERSION} or newer, " +
                "but found $version.",
            detail = ServerErrorDetail(
                kind = ServerErrorKind.UPDATE_REQUIRED,
                installMethod = info.installMethod,
                suggestedCommand = info.installMethod.upgradeCommand ?: "opencode upgrade",
                observed = version,
                required = OpenCodeCli.MINIMUM_SUPPORTED_VERSION,
            ),
        )
    }

    // --- Restart --------------------------------------------------------------

    /**
     * Restarts a server this project manages. A server started outside the IDE is
     * left alone - stopping someone else's process would take down their sessions
     * too - and the caller is told so it can say that in the UI.
     */
    fun restart(force: Boolean): RestartOutcome {
        if (!process.isManaged && status.get() is ServerStatus.Running) {
            return RestartOutcome.NOT_MANAGED
        }
        if (!phase.compareAndSet(Phase.IDLE, Phase.RESTARTING)) return RestartOutcome.BUSY

        disposeGeneration.incrementAndGet()
        try {
            transport.stopEventStream()
            transport.abortRequests()
            transport.clearPendingAttentionRequests()
            process.stop()
            cli.clearCache()
            serverVersion.set(null)
            setStatus(ServerStatus.Stopped)
        } finally {
            phase.set(Phase.IDLE)
        }

        ensureStarted()
        return RestartOutcome.RESTARTED
    }

    enum class RestartOutcome { RESTARTED, NOT_MANAGED, BUSY }

    /** Repoints REST scoping and the event stream at another workspace root. */
    fun activateDirectory(directory: String?) {
        val normalized = OpenCodeRequestScope.normalizeDirectory(directory)
        if (!transport.rescope(normalized)) return
        if (status.get() is ServerStatus.Running) {
            transport.startEventStream(normalized, promoteDirectoryImmediately = false)
        }
    }

    // --- Status ---------------------------------------------------------------

    private fun setStatus(next: ServerStatus) {
        val previous = status.getAndSet(normalizeRunning(next, status.get()))
        val current = status.get()
        if (previous == current) return
        statusListeners.forEach { runCatching { it(current) } }
    }

    /**
     * Preserves the event-stream health across status updates that do not mention
     * it. Port of `normalizeRunningStatus`: a plain `running` status must not
     * silently claim a healthy stream.
     */
    private fun normalizeRunning(next: ServerStatus, previous: ServerStatus): ServerStatus {
        if (next !is ServerStatus.Running) return next
        if (previous !is ServerStatus.Running) return next
        return next
    }

    private fun applyEventStreamState(state: EventStreamState) {
        val current = status.get()
        if (current !is ServerStatus.Running) return
        if (current.eventStream == state) return
        setStatus(current.copy(eventStream = state))
    }

    override fun dispose() {
        phase.set(Phase.DISPOSING)
        disposeGeneration.incrementAndGet()
        transport.dispose()
        // Only a server this project spawned is stopped; an adopted one keeps
        // serving the other IDE windows that are still attached to it.
        if (process.isManaged) process.stop()
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(2, TimeUnit.SECONDS) }
        statusListeners.clear()
        eventListeners.clear()
        setStatus(ServerStatus.Stopped)
    }

    companion object {
        private const val STARTUP_TIMEOUT_MS = 30_000L
        private const val HEALTH_POLL_INTERVAL_MS = 250L
    }
}
