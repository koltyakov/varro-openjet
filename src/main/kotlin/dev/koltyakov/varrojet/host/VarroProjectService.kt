package dev.koltyakov.varrojet.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import dev.koltyakov.varrojet.protocol.Json
import dev.koltyakov.varrojet.protocol.asObjectOrNull
import dev.koltyakov.varrojet.protocol.bool
import dev.koltyakov.varrojet.protocol.int
import dev.koltyakov.varrojet.protocol.obj
import dev.koltyakov.varrojet.protocol.str
import dev.koltyakov.varrojet.protocol.strings
import dev.koltyakov.varrojet.protocol.text
import dev.koltyakov.varrojet.server.OpenCodeServer
import dev.koltyakov.varrojet.server.ServerEvents
import dev.koltyakov.varrojet.server.ServerStatus
import dev.koltyakov.varrojet.settings.VarroSettings
import dev.koltyakov.varrojet.store.VarroStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Composition root for one project.
 *
 * JetBrains counterpart of `src/extension/sidebar-provider.ts`. It owns the
 * long-lived pieces - the OpenCode server, the editor context tracker, the
 * stores - and connects them to however many webview surfaces are open.
 *
 * Ownership is deliberately here rather than in the tool window: a tool window
 * is created and destroyed as the user shows and hides it, while a running
 * OpenCode session must survive that. Upstream makes the same split for the same
 * reason.
 */
@Service(Service.Level.PROJECT)
class VarroProjectService(private val project: Project) : Disposable {

    private val log = logger<VarroProjectService>()

    // Plain state is declared before the collaborators below on purpose. Kotlin
    // runs property initializers in declaration order, and those collaborators
    // are handed callbacks that reach back into this object; a callback that
    // fires early must not find these fields still null.

    /** Attached webview surfaces. A project can have the tool window and editor tabs. */
    private val panels = CopyOnWriteArrayList<WebviewHost>()

    private val started = AtomicBoolean(false)
    private val lastStatus = AtomicReference<ServerStatus>(ServerStatus.Stopped)

    /** Sessions whose run was cut short by a reload, offered back for recovery. */
    private val interruptedSessionIds = AtomicReference<List<String>>(emptyList())

    val settings: VarroSettings = VarroSettings.getInstance()
    val store: VarroStore = VarroStore.getInstance(project)
    val editor: EditorIntegration = EditorIntegration(project)
    val terminal: TerminalService = TerminalService(project)

    val server: OpenCodeServer = OpenCodeServer(
        settings = settings,
        workspaceCwd = { project.guessProjectDir()?.path ?: project.basePath },
    )

    val context: ContextProvider = ContextProvider(project)

    private val hostServices = OpenCodeHostServices(project, server, editor, settings)

    private val restProxy = RestProxy(
        project = project,
        server = server,
        store = store,
        context = context,
        services = hostServices,
        postResponse = { message -> broadcastEnvelope(message) },
    )

    init {
        Disposer.register(this, server)
        Disposer.register(this, context)

        // Every callback into this service is wired here, after all properties
        // exist. Passing these to a collaborator's constructor instead would let
        // it fire while this object was still half-built.
        context.addListener { snapshot -> broadcast("context/update", snapshot) }

        server.onStatus { status ->
            lastStatus.set(status)
            broadcast("server/status", status.toJson())
            reportStatusFailure(status)
        }

        server.onEvent { event -> forwardServerEvent(event) }

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VarroSettings.TOPIC, VarroSettings.Listener { broadcastConfig() })
    }

    // --- Surfaces -------------------------------------------------------------

    /**
     * Creates a webview surface and registers it for broadcasts.
     *
     * The caller owns the returned host's lifetime and must register it with a
     * parent disposable - it wraps a native browser. It is deliberately *not*
     * parented to this service: a surface comes and goes as the user opens and
     * closes the tool window, while the service and its OpenCode session outlive
     * that.
     */
    fun createPanel(surface: WebviewHost.Surface): WebviewHost {
        val host = WebviewHost(
            project = project,
            surface = surface,
            initialStateProvider = { buildInitialState(surface) },
            viewStateProvider = { store.viewState(surface.id) },
            onMessage = { message -> handleMessage(host = null, message = message) },
        )
        panels.add(host)
        Disposer.register(host) { panels.remove(host) }
        return host
    }

    private fun broadcast(type: String, payload: Any? = Unit) =
        panels.forEach { it.post(type, payload) }

    private fun broadcastEnvelope(message: JsonElement) = panels.forEach { it.post(message) }

    // --- Startup --------------------------------------------------------------

    /**
     * Starts OpenCode if it is not already running. Called when a surface first
     * signals `ready`, which keeps IDE startup free of any OpenCode work.
     */
    fun ensureServerStarted() {
        if (!started.compareAndSet(false, true)) {
            // Already started once; a later call still nudges a stopped server, which
            // is what the webview's retry button relies on.
            if (lastStatus.get() !is ServerStatus.Running) server.ensureStarted()
            return
        }
        server.ensureStarted()
    }

    // --- Initial state --------------------------------------------------------

    /**
     * The boot snapshot inlined into the page. Mirrors upstream's
     * `InitialWebviewState` so the webview can render its first frame without a
     * round trip.
     */
    private fun buildInitialState(surface: WebviewHost.Surface): JsonObject {
        val theme = ThemeBridge.current()
        return JsonObject().apply {
            addProperty("theme", theme.kind.id)
            add("serverStatus", lastStatus.get().toJson())
            add("editorContext", context.context)
            add("terminalSelection", terminal.currentSelection())
            add("droppedFiles", JsonArray())
            addProperty("emptyStateLogoUri", "")
            addProperty("remoteExtensionHost", false)

            add(
                "webviewContext",
                Json.obj(
                    "viewId" to surface.id,
                    "surface" to surface.id,
                    "initialRoute" to Json.obj("type" to "new-session"),
                ),
            )

            // Configuration mirrored from settings.
            addProperty("showFileDiffs", settings.chatShowFileDiffs)
            addProperty("expandThinking", settings.chatExpandThinking)
            addProperty("showChangedFiles", settings.chatShowChangedFiles)
            addProperty("showTurnTimer", settings.chatShowTurnTimer)
            addProperty("desktopSessionPaneSide", settings.sessionPaneSide())
            addProperty("defaultPermissionMode", settings.permissionMode())
            addProperty("chatFontSize", resolvedChatFontSize())
            addProperty("chatEditorFontSize", resolvedEditorFontSize())
            addProperty("chatFontFamily", settings.chatFontFamily)

            // Persisted state the webview treats as authoritative.
            add("sessionPermissionModes", store.sessionPermissionModes)
            add("sessionSelectedModels", store.sessionSelectedModels)
            add("sessionPlanState", store.sessionPlanState)
            add("modelPreferences", store.modelPreferences)
            add("pinnedSessionIds", Json.array(store.pinnedSessionIds))
            add("queuedMessages", store.queuedMessages)
            add("recycleBinEntries", store.recycleBin)
            add("interruptedSessionIds", Json.array(interruptedSessionIds.get()))

            addProperty("editorTabsOpen", false)
            add("editorSessionIds", JsonArray())
            add("openEditorSessionIds", JsonArray())
        }
    }

    private fun resolvedChatFontSize(): Int =
        settings.chatFontSize.takeIf { it in 6..100 } ?: ThemeBridge.uiFontSize()

    private fun resolvedEditorFontSize(): Int =
        settings.chatEditorFontSize.takeIf { it in 6..100 } ?: ThemeBridge.editorFontSize()

    private fun broadcastConfig() {
        broadcast(
            "config/update",
            Json.obj(
                "showFileDiffs" to settings.chatShowFileDiffs,
                "expandThinking" to settings.chatExpandThinking,
                "showChangedFiles" to settings.chatShowChangedFiles,
                "showTurnTimer" to settings.chatShowTurnTimer,
                "desktopSessionPaneSide" to settings.sessionPaneSide(),
                "defaultPermissionMode" to settings.permissionMode(),
                "chatFontSize" to resolvedChatFontSize(),
                "chatEditorFontSize" to resolvedEditorFontSize(),
                "chatFontFamily" to settings.chatFontFamily,
            ),
        )
    }

    // --- Server events --------------------------------------------------------

    /**
     * Forwards an OpenCode event to the surfaces.
     *
     * Upstream routes detailed events only to the endpoint owning their execution
     * directory and projects safe lifecycle summaries elsewhere. A JetBrains
     * project is a single workspace, so an event is forwarded when it belongs to
     * this project's directory or carries no directory at all.
     */
    private fun forwardServerEvent(event: JsonElement) {
        val parsed = ServerEvents.parse(event) ?: return
        val eventDirectory = parsed.workspaceDirectory
        val projectDirectory = project.guessProjectDir()?.path ?: project.basePath
        if (eventDirectory != null && projectDirectory != null &&
            !dev.koltyakov.varrojet.server.WorkspacePaths.isSame(eventDirectory, projectDirectory)
        ) {
            // Another IDE window owns this directory; forwarding it would make this
            // project's session list show sessions it cannot act on.
            return
        }

        val payload = JsonObject().apply {
            addProperty("type", parsed.type)
            parsed.id?.let { addProperty("id", it) }
            parsed.seq?.let { addProperty("seq", it) }
            parsed.workspaceDirectory?.let { addProperty("workspaceDirectory", it) }
            if (parsed.sequenceOnly) addProperty("sequenceOnly", true)
            parsed.sequenceStart?.let { addProperty("sequenceStart", it) }
            parsed.properties?.let { add("properties", it) }
        }
        broadcast("server/event", payload)
    }

    /** Surfaces a startup failure as an IDE notification when no panel is visible. */
    private fun reportStatusFailure(status: ServerStatus) {
        if (status !is ServerStatus.Error) return
        if (isToolWindowVisible()) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Varro: OpenCode unavailable", status.message, NotificationType.WARNING)
            .notify(project)
    }

    private fun isToolWindowVisible(): Boolean = runCatching {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.isVisible == true
    }.getOrDefault(false)

    // --- Message routing ------------------------------------------------------

    /**
     * Dispatches a webview message.
     *
     * Port of `src/extension/message-router.ts`. Every branch is best-effort: a
     * handler that throws logs and drops the message rather than tearing down the
     * channel, because the webview has no way to recover a dead bridge short of a
     * reload.
     */
    fun handleMessage(host: WebviewHost?, message: JsonObject) {
        val type = message.str("type") ?: return
        val payload = message.obj("payload")

        try {
            when (type) {
                "ready" -> {
                    broadcast("server/status", lastStatus.get().toJson())
                    broadcast("context/update", context.context)
                    broadcastConfig()
                    broadcast("permission-modes/sync", Json.obj("modes" to store.sessionPermissionModes))
                    broadcast("session-models/sync", Json.obj("models" to store.sessionSelectedModels))
                    broadcast(
                        "session-plan-state/sync",
                        Json.obj("state" to store.sessionPlanState, "agents" to store.sessionPlanAgents),
                    )
                    broadcast("model-preferences/sync", store.modelPreferences)
                    broadcast("queued-messages/sync", Json.obj("messages" to store.queuedMessages))
                    ensureServerStarted()
                }

                "api/request" -> payload?.let(restProxy::handleRequest)
                "api/cancel" -> payload?.let(restProxy::cancelRequest)

                "context/request" -> context.replay()

                // --- Files ------------------------------------------------------
                "files/pick" -> editor.pickFile()?.let { picked ->
                    broadcast(
                        "files/dropped",
                        JsonArray().apply {
                            add(
                                Json.obj(
                                    "path" to picked.str("path"),
                                    "relativePath" to picked.str("path"),
                                    "type" to "file",
                                ),
                            )
                        },
                    )
                }

                "files/search" -> {
                    val requestId = payload.int("requestId") ?: return
                    val query = payload.str("query").orEmpty()
                    val limit = payload.int("limit") ?: 30
                    broadcast(
                        "files/search-results",
                        Json.obj(
                            "requestId" to requestId,
                            "query" to query,
                            "files" to editor.searchFiles(query, limit),
                        ),
                    )
                }

                "file/read" -> payload.str("path")?.let { editor.readWorkspaceFile(it) }

                // --- Editor integration -----------------------------------------
                "vscode/open" -> {
                    val path = payload.str("path") ?: return
                    val status = editor.openPath(path, payload.int("line"), payload.str("kind"))
                    payload.int("requestId")?.let { requestId ->
                        broadcast(
                            "vscode/open-result",
                            Json.obj("requestId" to requestId, "status" to status),
                        )
                    }
                }

                "vscode/open-text" -> editor.openText(
                    content = payload.str("content").orEmpty(),
                    title = payload.str("title") ?: "Varro",
                    language = payload.str("language"),
                )

                "vscode/open-external" -> payload.str("url")?.let(::openExternal)

                "vscode/open-settings" -> openSettings()

                "vscode/show-output" -> showLog()

                "vscode/open-folder" -> ApplicationManager.getApplication().invokeLater {
                    com.intellij.ide.actions.OpenFileAction.openFile(
                        project.basePath ?: return@invokeLater,
                        project,
                    )
                }

                "webview/reload" -> panels.forEach { it.reload() }

                // --- Terminal ---------------------------------------------------
                "terminal/run" -> payload.str("command")?.let { command ->
                    terminal.run(command, payload.str("title") ?: "OpenCode")
                }
                "terminal-selection/clear" -> broadcast("terminal-selection/update", null)

                // --- Server -----------------------------------------------------
                "server/restart" -> restartServer(payload.bool("force") == true)
                "server/restart/check" -> broadcast(
                    "server/restart-blocked",
                    Json.obj(
                        "totalSessionCount" to 0,
                        "directories" to JsonArray(),
                        "checkId" to (payload.int("checkId") ?: 0),
                    ),
                )

                // --- Persisted state --------------------------------------------
                "permission-mode/update" -> updateRecord(
                    store.sessionPermissionModes,
                    payload.str("sessionId"),
                    payload?.get("mode"),
                ) { store.sessionPermissionModes = it }

                "permission-modes/migrate" -> payload.obj("modes")?.let {
                    store.sessionPermissionModes = it
                }

                "session-model/update" -> updateRecord(
                    store.sessionSelectedModels,
                    payload.str("sessionId"),
                    payload?.get("model"),
                ) { store.sessionSelectedModels = it }

                "session-models/migrate" -> payload.obj("models")?.let {
                    store.sessionSelectedModels = it
                }

                "session-plan-state/update" -> {
                    val sessionId = payload.str("sessionId") ?: return
                    if (payload != null && payload.has("skippedAt")) {
                        val state = store.sessionPlanState
                        state.add(sessionId, payload.get("skippedAt"))
                        store.sessionPlanState = state
                    }
                    payload.text("agent")?.let { agent ->
                        val agents = store.sessionPlanAgents
                        agents.addProperty(sessionId, agent)
                        store.sessionPlanAgents = agents
                    }
                }

                "session-unread-state/update" -> {
                    val sessionId = payload.str("sessionId") ?: return
                    val unread = store.sessionUnreadState
                    unread.add(
                        sessionId,
                        Json.obj(
                            "kind" to payload.str("kind"),
                            "unread" to (payload.bool("unread") ?: false),
                            "markerAt" to payload.int("markerAt"),
                        ),
                    )
                    store.sessionUnreadState = unread
                }

                "model-preferences/update" -> payload.obj("preferences")?.let {
                    store.modelPreferences = it
                    broadcast("model-preferences/sync", it)
                }
                "model-preferences/migrate" -> payload?.let {
                    store.modelPreferences = it
                    broadcast("model-preferences/sync", it)
                }

                "queued-messages/update" -> {
                    val messages = payload?.getAsJsonArray("messages") ?: JsonArray()
                    store.queuedMessages = messages
                    broadcast("queued-messages/sync", Json.obj("messages" to messages))
                }

                "queued-messages/claim" -> {
                    // Single-surface host: a claim is always granted. The lease still
                    // has to be a fresh positive number, because the webview sends it
                    // back on the dispatching request and the proxy compares it.
                    val requestId = payload.int("requestId") ?: return
                    broadcast(
                        "queued-messages/claim-result",
                        Json.obj(
                            "requestId" to requestId,
                            "itemId" to payload.str("itemId"),
                            "sessionId" to payload.str("sessionId"),
                            "granted" to true,
                            "lease" to System.currentTimeMillis(),
                        ),
                    )
                }

                "queued-messages/release" -> Unit

                "recovery/interrupted-sessions-ack" -> {
                    val consumed = payload?.getAsJsonArray("consumedSessionIds")?.strings().orEmpty()
                    interruptedSessionIds.updateAndGet { current -> current - consumed.toSet() }
                    store.interruptedSessionIds = interruptedSessionIds.get()
                }

                // --- Host-side extras added by this port -------------------------
                "host/view-state" -> payload?.let { state ->
                    store.setViewState(WebviewHost.Surface.SIDEBAR.id, state)
                }
                "host/hide-panel" -> hideToolWindow()

                // --- Session surfaces --------------------------------------------
                "session/open-in-opencode" -> payload.str("sessionId")
                    // The id is interpolated into a shell command line, so anything
                    // outside OpenCode's own id alphabet is rejected rather than quoted.
                    ?.takeIf { SESSION_ID.matches(it) }
                    ?.let { sessionId ->
                        terminal.runTrusted("opencode --session $sessionId", "OpenCode")
                    }

                // Editor-tab surfaces are not implemented yet; opening the session in
                // the tool window is the closest behaviour and keeps the action from
                // silently doing nothing.
                "session/open-in-editor", "session/open-in-sidebar" ->
                    payload.str("sessionId")?.let { sessionId ->
                        showToolWindow()
                        broadcast("command/open-session", Json.obj("sessionId" to sessionId))
                    }

                "chat/new-editor" -> {
                    showToolWindow()
                    broadcast("command/new-session")
                }

                "config/update" -> applyWebviewConfig(payload)

                "log" -> logFromWebview(payload)

                // Accepted and intentionally inert: these drive VS Code affordances
                // with no JetBrains counterpart, and the webview does not wait on them.
                "commands/state", "session/seen", "webview/focus", "permission/reveal",
                "providers/watch", "editor/route-changed", "vscode/mermaid-preview",
                "files/remove", "files/clear", "composer/images-update",
                -> Unit

                "providers/refresh", "providers/auth-changed" -> broadcast("providers/refresh")

                "session/export" -> exportSession(payload.str("sessionId"))

                "usage/report" -> generateUsageReport(payload.bool("includeAllTime") == true)

                "ralph/start", "ralph/stop", "ralph/pause", "ralph/resume",
                "ralph/update-model", "ralph/sync",
                -> broadcast("ralph/state", Json.obj("runs" to store.ralphRuns, "activeIds" to JsonArray()))

                else -> log.debug("Unhandled webview message: $type")
            }
        } catch (failure: Exception) {
            log.warn("handleMessage($type) failed", failure)
        }
    }

    /** Sets or removes one key of a persisted record, then writes it back. */
    private inline fun updateRecord(
        record: JsonObject,
        key: String?,
        value: JsonElement?,
        write: (JsonObject) -> Unit,
    ) {
        if (key == null) return
        if (value == null || value.isJsonNull) record.remove(key) else record.add(key, value)
        write(record)
    }

    private fun applyWebviewConfig(payload: JsonObject?) {
        payload.str("desktopSessionPaneSide")?.let { settings.chatDesktopSessionPaneSide = it }
        payload.str("defaultPermissionMode")?.let { settings.chatDefaultPermissionMode = it }
        payload.bool("showFileDiffs")?.let { settings.chatShowFileDiffs = it }
        payload.bool("expandThinking")?.let { settings.chatExpandThinking = it }
        payload.bool("showChangedFiles")?.let { settings.chatShowChangedFiles = it }
        payload.bool("showTurnTimer")?.let { settings.chatShowTurnTimer = it }
        broadcastConfig()
    }

    private fun logFromWebview(payload: JsonObject?) {
        val message = payload.str("msg") ?: return
        val detail = payload.str("error") ?: payload.str("data")
        when (payload.str("level")) {
            "error" -> log.warn("webview: $message${detail?.let { " - $it" }.orEmpty()}")
            "warn" -> log.info("webview: $message${detail?.let { " - $it" }.orEmpty()}")
            else -> log.debug("webview: $message")
        }
    }

    // --- Commands the IDE side triggers ---------------------------------------

    fun restartServer(force: Boolean) {
        when (server.restart(force)) {
            OpenCodeServer.RestartOutcome.NOT_MANAGED -> notify(
                "The OpenCode server was not started by this IDE. Restart it where you launched it.",
                NotificationType.INFORMATION,
            )
            OpenCodeServer.RestartOutcome.BUSY -> notify(
                "A restart is already in progress.",
                NotificationType.INFORMATION,
            )
            OpenCodeServer.RestartOutcome.RESTARTED -> Unit
        }
    }

    fun newSession() {
        showToolWindow()
        broadcast("command/new-session")
    }

    fun focusInput() {
        showToolWindow()
        broadcast("command/focus-input")
    }

    fun searchSessions() {
        showToolWindow()
        broadcast("command/search-sessions")
    }

    fun abort() = broadcast("command/abort")

    fun switchSession(direction: String) =
        broadcast("command/switch-session", Json.obj("direction" to direction))

    /** Adds the current editor selection (or file) to the composer's context. */
    fun addToContext() {
        context.refresh()
        showToolWindow()
        broadcast("command/focus-input")
    }

    private fun exportSession(sessionId: String?) {
        if (sessionId == null) return
        val response = runCatching {
            server.transport.request("GET", "/session/${java.net.URLEncoder.encode(sessionId, Charsets.UTF_8)}/message")
        }.getOrNull() ?: return
        editor.openText(
            content = Json.stringify(response.data),
            title = "varro-session-$sessionId.json",
            language = "json",
        )
    }

    private fun generateUsageReport(includeAllTime: Boolean) {
        // Usage accounting reads OpenCode's retained history, which this port does
        // not index yet. Telling the user beats opening an empty report.
        notify(
            "Usage reports are not available in Varro OpenJet yet. " +
                "Run `opencode stats` in a terminal for the same accounting.",
            NotificationType.INFORMATION,
        )
    }

    private fun openExternal(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        com.intellij.ide.BrowserUtil.browse(url)
    }

    private fun openSettings() = ApplicationManager.getApplication().invokeLater {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "Varro")
    }

    private fun showLog() = notify(
        "Varro logs are written to the IDE log. Use Help | Show Log in Finder/Explorer.",
        NotificationType.INFORMATION,
    )

    /** Opens JCEF devtools on every attached surface, for debugging the webview. */
    fun openDevTools() = panels.forEach { it.openDevTools() }

    fun showToolWindow() = ApplicationManager.getApplication().invokeLater {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show(null)
    }

    private fun hideToolWindow() = ApplicationManager.getApplication().invokeLater {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.hide(null)
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Varro", message, type)
            .notify(project)
    }

    override fun dispose() {
        restProxy.dispose()
        panels.clear()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Varro"
        const val NOTIFICATION_GROUP = "Varro Notifications"

        /** OpenCode session id alphabet, matching upstream's own validation. */
        private val SESSION_ID = Regex("""^[A-Za-z0-9_-]+$""")

        fun getInstance(project: Project): VarroProjectService = project.service()
    }
}
