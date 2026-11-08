package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.util.EnvironmentUtil
import varro.protocol.Json
import varro.protocol.Followups
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.obj
import varro.protocol.str
import varro.host.quota.QuotaCredentials
import varro.server.OpenCodeServer
import varro.server.RequestOptions
import varro.settings.VarroSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The host-side half of the `/varro` API namespace.
 *
 * Port of the local endpoints in `rest-proxy.ts` that reach into the IDE, the
 * filesystem, or OpenCode configuration rather than the OpenCode API.
 */
class OpenCodeHostServices(
    private val project: Project,
    private val server: OpenCodeServer,
    private val editor: EditorIntegration,
    private val settings: VarroSettings,
    private val workspaceRoots: () -> List<String> = { listOfNotNull(project.basePath) },
    onProviderLimitUpdate: (JsonObject) -> Unit = {},
) : RestProxy.HostServices {

    private val providerQuotas = ProviderQuotaBackend(
        credentials = QuotaCredentials(Path.of(System.getProperty("user.home")), EnvironmentUtil.getEnvironmentMap()),
        request = { method, path, body, directory ->
            server.transport.request(method, path, body, RequestOptions(directory = directory)).data
        },
        onUpdate = onProviderLimitUpdate,
        apiVersion = { server.transport.apiVersion },
        attachOnly = server::isAttachOnly,
        requestUsageLimits = { directory -> server.transport.request("GET", "/api/experimental/usage/limits", options =
            RequestOptions(directory = directory, maxResponseBytes = 1024 * 1024)).data },
    )

    fun clearProviderQuotaCache() {
        providerQuotas.clearCache()
        promptCompletion.invalidate()
        invalidateFollowups()
    }

    fun dispose() {
        promptCompletion.invalidate()
        followups.close()
        providerQuotas.close()
    }

    override fun openPlanDocument(content: String, title: String?): String? =
        editor.openPlanDocument(content, title)

    override fun pickWorkspaceFile(): JsonObject? = editor.pickFile()

    override fun readWorkspaceFile(path: String): JsonObject? = editor.readWorkspaceFile(path)

    override fun resolveWorkspacePath(path: String): JsonObject? = editor.resolveWorkspacePath(path)

    // --- OpenCode configuration -----------------------------------------------

    private val followups = FollowupService({ server.transport.apiVersion }, server.transport::request)
    private fun followupConfiguration() = Triple(settings.chatFollowupsEnabled, settings.chatFollowupModel.trim(),
        server.requestGeneration() to server.url())
    private var followupPreferences = followupConfiguration()

    @Synchronized fun invalidateFollowups() {
        val preferences = followupConfiguration()
        if (preferences != followupPreferences) {
            followupPreferences = preferences
            followups.invalidate()
        }
    }

    override fun followups(sessionId: String, body: JsonElement?, directory: String?, dismiss: Boolean, isCancelled: () -> Boolean): JsonElement {
        require(directory == null || varro.server.WorkspacePaths.isSame(directory, server.workspaceDirectory()) ||
            workspaceRoots().any { varro.server.WorkspacePaths.isSame(directory, it) }) { "Workspace directory is not an open project folder" }
        val source = Followups.source(body)
        val scope = directory ?: server.workspaceDirectory()
        if (dismiss) { followups.dismiss(sessionId, source, scope); return com.google.gson.JsonPrimitive(true) }
        val modelRef = settings.chatFollowupModel.trim()
        val serverIdentity = server.requestGeneration() to server.url()
        val selected = if (modelRef.isEmpty()) body.asObjectOrNull()?.get("model").asObjectOrNull() else {
            val provider = modelRef.substringBefore('/', "")
            val id = modelRef.substringAfter('/', "")
            if (provider.isEmpty() || id.isEmpty() || modelRef.any(Char::isWhitespace)) null
            else Json.obj("providerID" to provider, "modelID" to id)
        }
        val model = Followups.model(selected)
        if (!settings.chatFollowupsEnabled || model == null) return Followups.empty()
        return followups.suggest(sessionId, source, model, scope, body.asObjectOrNull().bool("allowGenerate") == true,
            isCancelled, { settings.chatFollowupsEnabled && settings.chatFollowupModel.trim() == modelRef &&
            (server.requestGeneration() to server.url()) == serverIdentity }, cacheOnly = body.asObjectOrNull().bool("cacheOnly") == true)
    }

    override fun clearFollowups(sessionId: String, directory: String?, messageId: String?) =
        followups.clearPreviousTurn(sessionId, directory ?: server.workspaceDirectory(), messageId)

    private val promptCompletion = PromptCompletionService(
        apiVersion = { server.transport.apiVersion },
        serverUrl = server::url,
        request = { method, path, body, options -> server.transport.request(method, path, body, options).data },
    )

    override fun promptCompletionModels(isCancelled: () -> Boolean): JsonObject =
        promptCompletion.availability(server.workspaceDirectory(), isCancelled)

    override fun completePrompt(body: JsonElement?, directory: String?, isCancelled: () -> Boolean): JsonObject {
        require(directory == null || varro.server.WorkspacePaths.isSame(directory, server.workspaceDirectory()) ||
            workspaceRoots().any { varro.server.WorkspacePaths.isSame(directory, it) }) { "Workspace directory is not an open project folder" }
        return promptCompletion.complete(body, settings.chatPromptCompletionModel, directory ?: server.workspaceDirectory(), isCancelled)
    }

    override fun testPromptCompletion(body: JsonElement?, isCancelled: () -> Boolean): JsonObject {
        val model = PromptCompletionService.parseModel(body)
        val result = promptCompletion.test(body, server.workspaceDirectory(), isCancelled)
        if (!isCancelled()) {
            val label = "${model.providerID}/${model.modelID}"
            val duration = "%.2f s".format(java.util.Locale.ROOT, (result.get("elapsedMs").asLong / 1000.0))
            if (result.get("success").asBoolean) notify("Prompt completion test succeeded for $label in $duration.", NotificationType.INFORMATION)
            else notify("Prompt completion test failed for $label after $duration: ${result.str("error")}", NotificationType.ERROR)
        }
        return result
    }

    private val modelRouting = ModelRoutingService(
        settings = settings,
        readGlobalConfig = { requestGlobalConfig("GET") },
        patchGlobalConfig = { requestGlobalConfig("PATCH", it) },
        onChanged = {
            ApplicationManager.getApplication().messageBus.syncPublisher(VarroSettings.TOPIC).settingsChanged()
        },
        assertCompletionAvailable = { provider, model ->
            promptCompletion.assertAvailable(provider, model, server.workspaceDirectory())
        },
    )

    private fun requestGlobalConfig(method: String, body: JsonObject? = null): JsonObject {
        if (server.isAttachOnly()) {
            if (method != "GET") requireLocalConfig()
            return server.transport.request("GET", "/config").data.asObjectOrNull()
                ?: error("OpenCode returned an invalid server configuration")
        }
        if (method == "PATCH" && server.transport.apiVersion == 2) {
            val environment = server.cli.serverEnvironment()
            val root = Path.of(environment["XDG_CONFIG_HOME"] ?: Path.of(System.getProperty("user.home"), ".config").toString(), "opencode")
            OpenCodeGlobalConfig(root).patch(body ?: error("Missing configuration patch"))
            server.transport.request("POST", "/global/dispose")
            return requestGlobalConfig("GET")
        }
        return server.transport.request(method, "/global/config", body, RequestOptions(unscoped = true))
            .data.asObjectOrNull() ?: error("OpenCode returned an invalid global configuration")
    }

    override fun readOpenCodeConfig(): JsonObject = modelRouting.read()

    override fun updateModelRouting(body: JsonElement?): JsonObject = modelRouting.update(body)

    override fun mcpDefaults(method: String, body: JsonElement?): JsonObject {
        val defaults = mcpDefaultsService()
        if (server.isAttachOnly()) {
            if (method != "GET") requireLocalConfig()
            return defaults.attached(server.transport.request("GET", "/config").data.asObjectOrNull()
                ?: error("OpenCode returned an invalid MCP configuration"))
        }
        return if (method == "GET") defaults.read() else defaults.update(body)
    }

    override fun mcpConfig(body: JsonElement?, open: Boolean): JsonElement {
        requireLocalConfig()
        val defaults = mcpDefaultsService()
        if (!open) return defaults.add(body)
        val path = defaults.open(body.asObjectOrNull().str("scope").orEmpty()) { target ->
            var opened = false
            ApplicationManager.getApplication().invokeAndWait {
                val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                opened = documents.unsavedDocuments.any { document -> documents.getFile(document)?.path?.let {
                    varro.server.WorkspacePaths.isSame(it, target.toString())
                } == true } || com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.any {
                    varro.server.WorkspacePaths.isSame(it.path, target.toString())
                }
            }
            opened
        }
        require(editor.openConfiguration(path)) { "Could not open the OpenCode configuration in the editor." }
        return Json.toElement(true)
    }

    private fun mcpDefaultsService(): McpDefaults {
        val environment = server.cli.serverEnvironment()
        val global = Path.of(environment["XDG_CONFIG_HOME"] ?: Path.of(System.getProperty("user.home"), ".config").toString(), "opencode")
        return McpDefaults(Path.of(server.workspaceDirectory() ?: project.basePath ?: error("Project has no workspace directory")),
            global, server.transport.apiVersion == 2, beforeWrite = { path ->
                // The UI only knows this view's sessions. Include other projects and pending input.
                require(server.readRestartBlockers().get("totalSessionCount").asInt == 0) {
                    "OpenCode has active work or pending decisions. Wait for it to finish before changing MCP configuration."
                }
                val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                require(documents.unsavedDocuments.none { document -> documents.getFile(document)?.path?.let {
                    varro.server.WorkspacePaths.isSame(it, path.toString()) || runCatching { Path.of(it).toRealPath() == path }.getOrDefault(false)
                } == true }) { "$path has unsaved changes; save before changing MCP defaults" }
            }, refresh = { globalScope ->
                if (globalScope && server.transport.apiVersion == 1) {
                    // Released V1's global dispose does not invalidate its cached global config.
                    require(server.restart(false) == OpenCodeServer.RestartOutcome.RESTARTED) {
                        "MCP defaults were saved, but OpenCode could not restart safely. Restart the managed server when it is idle."
                    }
                } else if (globalScope) server.transport.request("POST", "/global/dispose", options = RequestOptions(unscoped = true))
                else server.transport.request("POST", "/instance/dispose", options = RequestOptions(directory = server.workspaceDirectory()))
            })
    }

    private val projectProviderConfig by lazy { ProjectProviderConfig(
        Path.of(project.guessProjectDir()?.path ?: project.basePath ?: error("Project has no workspace directory")),
    ) }

    @Synchronized override fun disableProvider(body: JsonElement?): JsonElement {
        requireLocalConfig()
        require(server.transport.apiVersion == 2) { "Local provider policies require OpenCode V2" }
        val providerID = body.asObjectOrNull()?.get("providerID")
        require(providerID?.isJsonPrimitive == true && providerID.asJsonPrimitive.isString) { "Invalid local provider" }
        val file = projectProviderConfig.path()
        val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        require(documents.unsavedDocuments.none { documents.getFile(it)?.path == file.toString() }) {
            "$file has unsaved changes; save or revert before disabling a provider"
        }
        projectProviderConfig.disable(providerID.asString)
        server.transport.request("POST", "/global/dispose", options = RequestOptions(directory = project.basePath))
        return com.google.gson.JsonPrimitive(true)
    }

    override fun readOpenCodePermissions(): JsonObject {
        requireLocalConfig()
        val effective = server.transport.request("GET", "/config", options = RequestOptions(directory = project.basePath)).data.asObjectOrNull()
        val inherited = requestGlobalConfig("GET")
        val local = projectPermissions()
        return Json.obj(
            "targetPath" to local.path().toString(),
            "projectRules" to local.read(),
            "inheritedSources" to Json.array(listOf(Json.obj("path" to "global/config",
                "rules" to PermissionService.fromConfig(inherited.get("permission"))))),
            "effectiveRules" to PermissionService.fromConfig(effective?.get("permission")),
            "supportsPatterns" to local.supportsPatterns(),
        )
    }

    @Synchronized override fun updateOpenCodePermissions(body: JsonElement?): JsonObject {
        val rules = PermissionService.validateRules(body.asObjectOrNull()?.get("rules"))
        projectPermissions().write(rules)
        if (server.transport.apiVersion != 2) server.transport.request("PATCH", "/config", JsonObject(), RequestOptions(directory = project.basePath))
        return readOpenCodePermissions()
    }

    private val permissions = PermissionService(varro.store.VarroStore.getInstance(project),
        apiVersion = { server.transport.apiVersion }, allowConfigPermission = { name, patterns, scope, directory ->
        // The browser replies once on V2 after saving. Never reload the pending request's instance.
        if (scope == "global") globalPermissionConfig().allow(name, patterns)
        else {
            val effective = server.transport.request("GET", "/config", options = RequestOptions(directory = directory)).data.asObjectOrNull()
                ?: error("Could not read effective OpenCode permissions")
            projectPermissions(directory).allow(name, patterns, effective.get("permission"))
        }
    }) { method, path, body, directory ->
        server.transport.request(method, path, body, RequestOptions(directory = directory)).data
    }

    override fun permissionRules(sessionId: String, rules: JsonElement?, directory: String?) =
        permissions.sessionRules(sessionId, rules, directory)

    @Synchronized override fun allowPermission(body: JsonObject, project: Boolean, directory: String?): JsonArray =
        try {
            permissions.allow(body, project, directory)
        } catch (failure: Exception) {
            notify("Could not save Always Allow: ${failure.message}", NotificationType.ERROR)
            throw failure
        }

    override fun globalPermissions(method: String, body: JsonElement?): JsonObject {
        val config = globalPermissionConfig()
        return if (method == "GET") config.read()
        else config.write(PermissionService.validateRules(body.asObjectOrNull()?.get("rules")))
    }

    override fun allowGlobalPermission(body: JsonObject, directory: String?): JsonArray {
        globalPermissionConfig() // Reject V1/attach-only connections before any permission lookup.
        return permissions.allowGlobal(body, directory)
    }

    private fun globalPermissionConfig(): GlobalPermissionConfig {
        requireLocalConfig()
        require(server.transport.apiVersion == 2) { "Global permission management requires OpenCode V2" }
        val environment = server.cli.serverEnvironment()
        val root = Path.of(environment["XDG_CONFIG_HOME"] ?: Path.of(System.getProperty("user.home"), ".config").toString(), "opencode")
        return GlobalPermissionConfig(root, ::checkPermissionDocument)
    }

    private fun checkPermissionDocument(path: Path) {
        val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        require(documents.unsavedDocuments.none { document -> documents.getFile(document)?.path?.let {
            runCatching { ProjectPermissionConfig.canonical(Path.of(it)) == path }.getOrDefault(false)
        } == true }) { "$path has unsaved changes; save or revert the document before updating permissions" }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(VarroProjectService.NOTIFICATION_GROUP)
            .createNotification("Varro", message, type)
            .notify(project)
    }

    private val projectPermissionConfig by lazy { ProjectPermissionConfig(
        Path.of(project.guessProjectDir()?.path ?: project.basePath ?: error("Project has no workspace directory")),
        native = { server.transport.apiVersion == 2 },
        beforeSave = ::checkPermissionDocument,
    ) }
    private fun requireLocalConfig() {
        check(!server.isAttachOnly()) {
            "File-based OpenCode configuration is not supported in attach-only mode. Edit model, provider, and project permission settings on the server host or inside the container. Session permissions remain available through the API."
        }
    }

    private fun projectPermissions(directory: String? = null): ProjectPermissionConfig {
        requireLocalConfig()
        if (directory != null) return ProjectPermissionConfig(Path.of(directory), native = { server.transport.apiVersion == 2 }, beforeSave = ::checkPermissionDocument)
        return projectPermissionConfig
    }

    // --- Automatic permission approval ----------------------------------------

    private val nativeDecisions = NativeDecisions(
        apiVersion = { server.transport.apiVersion },
        configuredModel = { settings.chatAutoApproveDecisionModel },
        request = server.transport::request,
    )

    private val decisionProviders = DecisionProviders(
        settings = settings,
        ui = object : DecisionProviders.Ui {
            override fun promptApiKey(): String? {
                var value: String? = null
                ApplicationManager.getApplication().invokeAndWait({
                    value = Messages.showPasswordDialog(project,
                        "Paste a TypeSafe API key. Varro stores it in the IDE password safe.",
                        "Connect TypeSafe Jev", null)
                }, ModalityState.any())
                return value
            }

            override fun showError(message: String) = notify(message, NotificationType.ERROR)

            override fun showInfo(message: String) = notify(message, NotificationType.INFORMATION)
        },
        onChanged = {
            ApplicationManager.getApplication().messageBus.syncPublisher(VarroSettings.TOPIC).settingsChanged()
        },
        native = nativeDecisions,
    )

    override fun decisionProviders(method: String, body: JsonElement?): JsonObject =
        if (method == "GET") decisionProviders.status() else decisionProviders.handle(body)

    private val judge = PermissionJudge(
        configuredModel = { settings.chatAutoApproveModel },
        request = { method, path, body, timeout ->
            server.transport.request(method, path, body, RequestOptions(timeoutMs = timeout)).data
        },
        hide = { id ->
            val store = varro.store.VarroStore.getInstance(project)
            synchronized(store) { store.hiddenSessionIds = store.hiddenSessionIds + id }
        },
        jev = JevDecisions(JevClient(decisionProviders::apiKey), decisionProviders::readSettings, decisionProviders::hasApiKey),
        native = nativeDecisions,
    )

    override fun judgePermission(body: JsonElement?): JsonObject = judge.judge(body)

    override fun judgeModel(providerId: String?, modelId: String?, variant: String?): JsonObject {
        return Json.obj("model" to judge.model(Json.obj("providerID" to providerId, "modelID" to modelId, "variant" to variant)))
    }

    // --- Provider quotas ------------------------------------------------------

    override fun providerLimit(providerId: String, modelId: String?): JsonObject =
        providerQuotas.get(providerId, modelId, project.guessProjectDir()?.path ?: project.basePath)

    internal fun pinnedProviderLimit(providerId: String, directory: String?): JsonObject = providerQuotas.get(providerId, null, directory)

    // --- Session diff summary -------------------------------------------------

    private val sessionSummaries = SessionSummaryService(
        apiVersion = { server.transport.apiVersion },
        readLocal = { id -> if (server.isAttachOnly() || server.transport.apiVersion == 2) null else LocalSessionSummary(LocalUsageDatabase.defaultPath(EnvironmentUtil.getEnvironmentMap())).read(id) },
        request = { path, directory ->
            server.transport.request("GET", path, options = RequestOptions(directory = directory)).data
        },
    )

    override fun sessionDiffSummary(sessionId: String, directory: String?, revision: String?): JsonObject =
        sessionSummaries.read(sessionId, directory)

}
