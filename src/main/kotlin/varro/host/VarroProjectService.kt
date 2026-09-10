package varro.host

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
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.int
import varro.protocol.long
import varro.protocol.obj
import varro.protocol.str
import varro.protocol.strings
import varro.protocol.text
import varro.server.OpenCodeServer
import varro.server.ServerEvents
import varro.server.ServerStatus
import varro.settings.VarroSettings
import varro.store.VarroStore
import varro.store.VarroModelStore
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
    private val routes = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    private val proxies = java.util.concurrent.ConcurrentHashMap<WebviewHost, RestProxy>()
    private val queueClaims = mutableMapOf<String, QueueClaim>()
    private var nextLease = 0L
    private data class QueueClaim(val viewId: String, val itemId: String, val lease: Long, var admitted: Boolean = false)
    @Volatile private var focusedHost: WebviewHost? = null

    private val started = AtomicBoolean(false)
    private val lastStatus = AtomicReference<ServerStatus>(ServerStatus.Stopped)

    /** Sessions whose run was cut short by a reload, offered back for recovery. */
    private val interruptedSessionIds = AtomicReference<List<String>>(emptyList())

    val settings: VarroSettings = VarroSettings.getInstance()
    val store: VarroStore = VarroStore.getInstance(project)
    private val modelStore = VarroModelStore.getInstance()
    val editor: EditorIntegration = EditorIntegration(project)
    val terminal: TerminalService = TerminalService(project)
    private val attachments = AttachmentStore(
        java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getSystemPath(), "varro", "attachments", project.locationHash),
    ) { project.basePath }

    val server: OpenCodeServer = OpenCodeServer(
        settings = settings,
        workspaceCwd = { project.guessProjectDir()?.path ?: project.basePath },
    )

    val context: ContextProvider = ContextProvider(project)

    private val hostServices = OpenCodeHostServices(project, server, editor, settings) { update ->
        broadcast("provider-limit/updated", update)
    }

    init {
        store.migrateBrowserSessionSelections()
        val removeSelectionListener = store.addSelectionListener { selection ->
            when (selection) {
                VarroStore.SessionSelection.PERMISSION_MODE ->
                    broadcast("permission-modes/sync", Json.obj("modes" to store.sessionPermissionModes))
                VarroStore.SessionSelection.MODEL ->
                    broadcast("session-models/sync", Json.obj("models" to store.sessionSelectedModels))
            }
        }
        Disposer.register(this, Disposable { removeSelectionListener() })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VarroModelStore.TOPIC, VarroModelStore.Listener {
                broadcast("model-preferences/sync", modelStore.modelPreferences)
            })
        if (modelStore.migrate(store.legacyModelPreferences)) broadcastModelPreferences()
        store.legacyModelPreferences = JsonObject()
        store.editorRoutes.entrySet().forEach { (id, route) -> route.asObjectOrNull()?.let { routes[id] = it } }
        interruptedSessionIds.set(store.interruptedSessionIds)
        Disposer.register(this, server)
        Disposer.register(this, context)

        // Every callback into this service is wired here, after all properties
        // exist. Passing these to a collaborator's constructor instead would let
        // it fire while this object was still half-built.
        context.addListener { snapshot -> broadcast("context/update", snapshot) }

        server.onStatus { status ->
            val previous = lastStatus.getAndSet(status)
            if (previous::class != status::class ||
                (previous is ServerStatus.Running && status is ServerStatus.Running && previous.url != status.url) ||
                (previous is ServerStatus.Error && status is ServerStatus.Error && previous.message != status.message)
            ) hostServices.clearProviderQuotaCache()
            broadcast("server/status", status.toJson())
            reportStatusFailure(status)
        }

        server.onEvent { event -> forwardServerEvent(event) }

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VarroSettings.TOPIC, VarroSettings.Listener {
                hostServices.clearProviderQuotaCache()
                broadcastConfig()
                broadcast("providers/refresh")
            })
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
    fun createPanel(
        surface: WebviewHost.Surface,
        viewId: String = surface.id,
        route: JsonObject? = null,
    ): WebviewHost {
        route?.let { rememberRoute(viewId, it) }
        lateinit var host: WebviewHost
        host = WebviewHost(
            project = project,
            surface = surface,
            viewId = viewId,
            initialStateProvider = { buildInitialState(surface, viewId) },
            viewStateProvider = { store.viewState(viewId) },
            onMessage = { message -> handleMessage(host, message) },
        )
        proxies[host] = RestProxy(project, server, store, context, hostServices, host::post) { request ->
            synchronized(queueClaims) {
                val dispatch = request.obj("queuedMessageDispatch")
                val sessionId = request.str("path")?.substringBefore('?')
                    ?.let { Regex("^/session/([^/]+)/(prompt_async|message)$").matchEntire(it)?.groupValues?.get(1) }
                val claim = queueClaims[sessionId]
                val valid = request.str("method")?.uppercase() == "POST" && claim != null && !claim.admitted &&
                    claim.viewId == viewId && claim.itemId == dispatch.str("itemId") &&
                    claim.lease == dispatch.long("lease")
                if (valid) claim.admitted = true
                valid
            }
        }
        panels.add(host)
        Disposer.register(host) {
            panels.remove(host)
            proxies.remove(host)?.dispose()
            if (focusedHost === host) focusedHost = null
            synchronized(queueClaims) { queueClaims.entries.removeIf { it.value.viewId == viewId } }
            broadcastEditorTabs()
        }
        broadcastEditorTabs()
        host.reload()
        return host
    }

    private fun broadcast(type: String, payload: Any? = Unit) =
        panels.forEach { it.post(type, payload) }

    private fun broadcastEnvelope(message: JsonElement) = panels.forEach { it.post(message) }

    private fun broadcastModelPreferences() {
        ApplicationManager.getApplication().messageBus.syncPublisher(VarroModelStore.TOPIC).preferencesChanged()
    }

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
    private fun buildInitialState(surface: WebviewHost.Surface, viewId: String): JsonObject {
        val theme = ThemeBridge.current()
        return JsonObject().apply {
            addProperty("theme", theme.kind.id)
            add("serverStatus", lastStatus.get().toJson())
            add("editorContext", context.context)
            add("terminalSelection", terminal.currentSelection())
            add("droppedFiles", JsonArray())
            addProperty("emptyStateLogoUri", WebviewAssets.EMPTY_STATE_LOGO_URL)
            addProperty("remoteExtensionHost", false)
            add("browserStorage", store.browserStorage())

            add(
                "webviewContext",
                Json.obj(
                    "viewId" to viewId,
                    "surface" to surface.id,
                    "initialRoute" to (routes[viewId] ?: Json.obj("type" to "new-session")),
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
            add("modelPreferences", modelStore.modelPreferences)
            add("pinnedSessionIds", Json.array(store.pinnedSessionIds))
            add("queuedMessages", store.queuedMessages)
            add("recycleBinEntries", store.recycleBin)
            add("interruptedSessionIds", Json.array(interruptedSessionIds.get()))

            addProperty("editorTabsOpen", panels.any { it.surface == WebviewHost.Surface.EDITOR })
            add("editorSessionIds", Json.array(editorSessionIds()))
            add("openEditorSessionIds", Json.array(editorSessionIds()))
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
            !varro.server.WorkspacePaths.isSame(eventDirectory, projectDirectory)
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
    @Synchronized
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
                    broadcast("model-preferences/sync", modelStore.modelPreferences)
                    broadcast("queued-messages/sync", Json.obj("messages" to store.queuedMessages))
                    ensureServerStarted()
                }

                "api/request" -> payload?.let { request ->
                    val proxy = proxies[host] ?: return
                    ApplicationManager.getApplication().executeOnPooledThread { proxy.handleRequest(request) }
                }
                "api/cancel" -> payload?.let { proxies[host]?.cancelRequest(it) }

                "context/request" -> context.replay()

                // --- Files ------------------------------------------------------
                "files/pick" -> editor.pickFile()?.str("path")?.let { path ->
                    attachments.describe(path)?.let { host?.post("files/dropped", Json.array(listOf(it))) }
                }
                "files/drop" -> {
                    val files = payload?.getAsJsonArray("paths")?.strings().orEmpty().distinct().take(100)
                        .mapNotNull { attachments.describe(it) }
                    host?.post("files/dropped", Json.array(files))
                }
                "files/drop-content" -> {
                    payload?.getAsJsonArray("files")?.take(20)?.forEach { file ->
                        host?.post("files/dropped", Json.array(listOf(attachments.store(file.asJsonObject))))
                    }
                }
                "pdfs/store", "images/store" -> payload?.let {
                    val file = attachments.store(it)
                    host?.post(if (type == "pdfs/store") "pdfs/stored" else "images/stored",
                        Json.obj("id" to payload.str("id"), "contextFile" to file))
                }

                "files/search" -> {
                    val requestId = payload.int("requestId") ?: return
                    val query = payload.str("query").orEmpty()
                    val limit = payload.int("limit") ?: 30
                    host?.post(
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
                        host?.post(
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

                "webview/reload" -> host?.reload()

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
                "permission-mode/update" -> payload.str("sessionId")?.let {
                    store.updateSessionPermissionMode(it, payload?.get("mode"))
                }

                "permission-modes/migrate" -> payload.obj("modes")?.let {
                    store.migrateSessionPermissionModes(it)
                }

                "session-model/update" -> payload.str("sessionId")?.let {
                    store.updateSessionModel(it, payload?.get("model"))
                }

                "session-models/migrate" -> payload.obj("models")?.let {
                    store.migrateSessionModels(it)
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
                            "markerAt" to payload.long("markerAt"),
                        ),
                    )
                    store.sessionUnreadState = unread
                }

                "model-preferences/update" -> payload.obj("preferences")?.let {
                    modelStore.modelPreferences = it
                    broadcastModelPreferences()
                }
                "model-preferences/migrate" -> payload?.let {
                    modelStore.migrate(it)
                    broadcastModelPreferences()
                }

                "queued-messages/update" -> {
                    val messages = payload?.getAsJsonArray("messages") ?: JsonArray()
                    synchronized(queueClaims) {
                        val viewId = host?.viewId ?: "sidebar"
                        val merged = JsonArray()
                        store.queuedMessages.forEach { item ->
                            if ((item.asObjectOrNull().str("ownerViewId") ?: "sidebar") != viewId) merged.add(item)
                        }
                        messages.forEach { item ->
                            if ((item.asObjectOrNull().str("ownerViewId") ?: "sidebar") == viewId) merged.add(item)
                        }
                        store.queuedMessages = merged
                        broadcast("queued-messages/sync", Json.obj("messages" to merged))
                    }
                }

                "queued-messages/claim" -> {
                    val requestId = payload.int("requestId") ?: return
                    val sessionId = payload.str("sessionId") ?: return
                    val itemId = payload.str("itemId") ?: return
                    val viewId = host?.viewId ?: return
                    val claim = synchronized(queueClaims) {
                        val item = store.queuedMessages.firstOrNull {
                            val value = it.asObjectOrNull()
                            value.str("sessionId") == sessionId &&
                                (if (payload.str("mode") == "steer") value.str("id") == itemId else value.bool("paused") != true)
                        }.asObjectOrNull()
                        val existing = queueClaims[sessionId]
                        when {
                            existing != null -> existing.takeIf { it.viewId == viewId && it.itemId == itemId }
                            item.str("id") == itemId && (item.str("ownerViewId") ?: "sidebar") == viewId ->
                                QueueClaim(viewId, itemId, ++nextLease).also { queueClaims[sessionId] = it }
                            else -> null
                        }
                    }
                    host.post(
                        "queued-messages/claim-result",
                        Json.obj(
                            "requestId" to requestId,
                            "itemId" to payload.str("itemId"),
                            "sessionId" to payload.str("sessionId"),
                            "granted" to (claim != null),
                            "lease" to claim?.lease,
                        ),
                    )
                }

                "queued-messages/release" -> synchronized(queueClaims) {
                    val sessionId = payload.str("sessionId")
                    val claim = queueClaims[sessionId]
                    if (claim?.viewId == host?.viewId && claim?.itemId == payload.str("itemId") &&
                        claim?.lease == payload.long("lease")
                    ) queueClaims.remove(sessionId)
                    Unit
                }

                "recovery/interrupted-sessions-ack" -> {
                    val consumed = payload?.getAsJsonArray("consumedSessionIds")?.strings().orEmpty()
                    interruptedSessionIds.updateAndGet { current -> current - consumed.toSet() }
                    store.interruptedSessionIds = interruptedSessionIds.get()
                }

                // --- Host-side extras added by this port -------------------------
                "host/view-state" -> payload.obj("state")?.let { state ->
                    store.setViewState(host?.viewId ?: "sidebar", state)
                }
                "host/storage" -> payload.str("key")?.let { key ->
                    synchronized(store) {
                        store.updateBrowserStorage(key, payload.str("value"))
                        panels.filter { it !== host }.forEach { it.post("host/storage", payload) }
                    }
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

                "session/open-in-editor" -> payload?.let { openEditor(it) }
                "session/open-in-sidebar" ->
                    payload.str("sessionId")?.let { sessionId ->
                        sidebarCommand("command/open-session", Json.obj("sessionId" to sessionId, "directory" to payload.str("directory")))
                    }

                "chat/new-editor" -> openEditor()
                "editor/route-changed" -> payload.obj("route")?.let { route ->
                    host?.let { rememberRoute(it.viewId, route) }
                    broadcastEditorTabs()
                }
                "webview/focus" -> if (payload.bool("focused") == true) focusedHost = host

                "config/update" -> applyWebviewConfig(payload)

                "log" -> logFromWebview(payload)

                // Accepted and intentionally inert: these drive VS Code affordances
                // with no JetBrains counterpart, and the webview does not wait on them.
                "commands/state", "session/seen", "permission/reveal",
                "providers/watch", "vscode/mermaid-preview",
                "files/remove", "files/clear", "composer/images-update",
                -> Unit

                "providers/refresh", "providers/auth-changed" -> {
                    hostServices.clearProviderQuotaCache()
                    broadcast("providers/refresh")
                }

                "session/export" -> exportSession(payload.str("sessionId"))

                "usage/report" -> generateUsageReport(payload.bool("includeAllTime") == true)

                "ralph/start", "ralph/stop", "ralph/pause", "ralph/resume",
                "ralph/update-model", "ralph/sync",
                -> broadcast("ralph/state", Json.obj("runs" to store.ralphRuns, "activeIds" to JsonArray()))

                else -> log.debug("Unhandled webview message: $type")
            }
        } catch (failure: Exception) {
            log.warn("handleMessage($type) failed", failure)
            if (type in setOf("files/drop", "files/drop-content", "images/store", "pdfs/store")) {
                notify("Could not attach file: ${failure.message}", NotificationType.ERROR)
            }
        }
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
        sidebarCommand("command/new-session")
    }

    fun focusInput() {
        sidebarCommand("command/focus-input")
    }

    fun searchSessions() {
        sidebarCommand("command/search-sessions")
    }

    fun abort() { focusedHost?.post("command/abort") ?: sidebarCommand("command/abort") }

    fun switchSession(direction: String) {
        (focusedHost ?: panels.firstOrNull { it.surface == WebviewHost.Surface.SIDEBAR })
            ?.post("command/switch-session", Json.obj("direction" to direction))
    }

    /** Adds the current editor selection (or file) to the composer's context. */
    fun addToContext(files: List<com.intellij.openapi.vfs.VirtualFile> = emptyList()) {
        context.refresh()
        val snapshot = context.context
        val paths = files.map { it.path }.ifEmpty { listOfNotNull(snapshot.obj("activeFile").str("path")) }
        val dropped = paths.mapNotNull { path -> attachments.describe(path)?.apply {
            if (path == snapshot.obj("activeFile").str("path")) {
                snapshot.obj("selection")?.let { add("lineRanges", Json.array(listOf(it))) }
            }
        } }
        val target = focusedHost
        if (target != null && target.surface == WebviewHost.Surface.EDITOR) {
            target.post("files/dropped", Json.array(dropped))
            target.post("command/focus-input")
            target.requestFocus()
        } else {
            sidebarCommand("files/dropped", Json.array(dropped))
            sidebarCommand("command/focus-input")
        }
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

    fun generateUsageReport(includeAllTime: Boolean = false) {
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Building OpenCode usage report", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        val report = UsageReport(ensureServerStarted = {
                            ensureServerStarted()
                            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                            while (lastStatus.get() !is ServerStatus.Running) {
                                indicator.checkCanceled()
                                val status = lastStatus.get()
                                if (status is ServerStatus.Error) error(status.message)
                                check(System.nanoTime() < deadline) { "OpenCode did not become available within 30 seconds" }
                                Thread.sleep(100)
                            }
                        }) { path, options -> server.transport.request("GET", path, options = options) }
                            .build(includeAllTime, checkCancelled = indicator::checkCanceled)
                        editor.openText(report, "OpenCode Usage Report.md", "markdown")
                    } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        notify("Could not build usage report: ${failure.message}", NotificationType.ERROR)
                    }
                }
            },
        )
    }

    fun showAbout() {
        val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("dev.koltyakov.varro-openjet"),
        )
        val ide = com.intellij.openapi.application.ApplicationInfo.getInstance()
        editor.openText(
            """
            # Varro OpenJet ${plugin?.version.orEmpty()}

            OpenCode workbench for JetBrains IDEs. Port of [Varro for VS Code](https://github.com/koltyakov/varro).

            - IDE: ${ide.fullApplicationName}, build ${ide.build}
            - Runtime: ${System.getProperty("java.runtime.version")}
            - JCEF available: ${JcefSupport.isAvailable()}
            - Server: ${Json.stringify(lastStatus.get().toJson())}
            - Webview revision: ${WebviewAssets.assetVersion}

            ## Links

            - [Varro OpenJet and issue tracker](https://github.com/koltyakov/varro-openjet)
            - [Upstream Varro](https://github.com/koltyakov/varro)
            - [OpenCode documentation](https://opencode.ai/docs)

            Licensed under MIT. Settings are under Settings | Tools | Varro.
            IDE diagnostics are available under Help | Show Log in Finder/Explorer.
            """.trimIndent(),
            "About Varro OpenJet.md", "markdown",
            previewOnly = true,
        )
    }

    fun setShowFileDiffs(enabled: Boolean) {
        settings.chatShowFileDiffs = enabled
        ApplicationManager.getApplication().messageBus.syncPublisher(VarroSettings.TOPIC).settingsChanged()
    }

    private fun openExternal(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        com.intellij.ide.BrowserUtil.browse(url)
    }

    fun openSettings() = ApplicationManager.getApplication().invokeLater {
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

    private fun sidebarCommand(type: String, payload: Any? = Unit) = ApplicationManager.getApplication().invokeLater {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show {
            panels.firstOrNull { it.surface == WebviewHost.Surface.SIDEBAR }?.let {
                it.post(type, payload)
                it.requestFocus()
            }
        }
    }

    fun openEditor(payload: JsonObject? = null) = ApplicationManager.getApplication().invokeLater {
        if (!JcefSupport.isAvailable()) {
            notify("The editor view requires an IDE runtime with JCEF.", NotificationType.WARNING)
            return@invokeLater
        }
        val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val sessionId = payload.str("sessionId")
        val existing = manager.openFiles.filterIsInstance<VarroChatFile>().firstOrNull {
            sessionId != null && routes[it.viewId].str("sessionId") == sessionId
        }
        val route = payload?.deepCopy()?.apply { addProperty("type", "session") } ?: Json.obj("type" to "new-session")
        val rememberedId = sessionId?.let { id -> routes.entries.firstOrNull {
            it.key != "sidebar" && it.value.str("sessionId") == id
        }?.key }
        val viewId = rememberedId ?: "editor-${sessionId ?: java.util.UUID.randomUUID()}"
        if (existing == null) rememberRoute(viewId, route)
        val file = existing ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance()
            .getFileSystem(VarroChatFileSystem.PROTOCOL).findFileByPath("/${project.locationHash}/$viewId")
            ?: return@invokeLater
        manager.openFile(file, true)
    }

    private fun editorSessionIds(): List<String> = panels.filter { it.surface == WebviewHost.Surface.EDITOR }
        .mapNotNull { routes[it.viewId].str("sessionId") }.distinct()

    private fun rememberRoute(viewId: String, route: JsonObject) {
        routes[viewId] = route
        synchronized(store) {
            val saved = store.editorRoutes
            saved.add(viewId, route)
            store.editorRoutes = saved
        }
        if (viewId != "sidebar") ApplicationManager.getApplication().invokeLater({
            val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val file = manager.openFiles.filterIsInstance<VarroChatFile>().firstOrNull { it.viewId == viewId }
                ?: return@invokeLater
            file.route = route
            val title = route.str("title")?.takeIf { it.isNotBlank() } ?: "Varro Chat"
            if (file.name != title) ApplicationManager.getApplication().runWriteAction {
                file.rename(this, title.replace('/', ' ').replace('\\', ' '))
                (manager as? com.intellij.openapi.fileEditor.ex.FileEditorManagerEx)?.updateFilePresentation(file)
            }
        }, project.disposed)
    }

    private fun broadcastEditorTabs() = broadcast("editor-tabs/state", Json.obj(
        "open" to panels.any { it.surface == WebviewHost.Surface.EDITOR },
        "sessionIds" to Json.array(editorSessionIds()),
        "openSessionIds" to Json.array(editorSessionIds()),
    ))

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
        hostServices.dispose()
        proxies.values.forEach { it.dispose() }
        proxies.clear()
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
