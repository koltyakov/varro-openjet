package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.EnvironmentUtil
import varro.protocol.Json
import varro.protocol.asObjectOrNull
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
    onProviderLimitUpdate: (JsonObject) -> Unit = {},
) : RestProxy.HostServices {

    private val providerQuotas = ProviderQuotaBackend(
        credentials = QuotaCredentials(Path.of(System.getProperty("user.home")), EnvironmentUtil.getEnvironmentMap()),
        request = { method, path, body, directory ->
            server.transport.request(method, path, body, RequestOptions(directory = directory)).data
        },
        onUpdate = onProviderLimitUpdate,
    )

    fun clearProviderQuotaCache() = providerQuotas.clearCache()

    fun dispose() = providerQuotas.close()

    override fun openPlanDocument(content: String, title: String?): String? =
        editor.openPlanDocument(content, title)

    override fun pickWorkspaceFile(): JsonObject? = editor.pickFile()

    override fun readWorkspaceFile(path: String): JsonObject? = editor.readWorkspaceFile(path)

    override fun resolveWorkspacePath(path: String): JsonObject? = editor.resolveWorkspacePath(path)

    // --- OpenCode configuration -----------------------------------------------

    private val modelRouting = ModelRoutingService(
        settings = settings,
        readGlobalConfig = { requestGlobalConfig("GET") },
        patchGlobalConfig = { requestGlobalConfig("PATCH", it) },
        onChanged = {
            ApplicationManager.getApplication().messageBus.syncPublisher(VarroSettings.TOPIC).settingsChanged()
        },
    )

    private fun requestGlobalConfig(method: String, body: JsonObject? = null): JsonObject =
        server.transport.request(method, "/global/config", body, RequestOptions(unscoped = true))
            .data.asObjectOrNull() ?: error("OpenCode returned an invalid global configuration")

    override fun readOpenCodeConfig(): JsonObject = modelRouting.read()

    override fun updateModelRouting(body: JsonElement?): JsonObject = modelRouting.update(body)

    override fun readOpenCodePermissions(): JsonObject {
        val effective = server.transport.request("GET", "/config", options = RequestOptions(directory = project.basePath)).data.asObjectOrNull()
        val inherited = requestGlobalConfig("GET")
        val local = projectPermissions()
        return Json.obj(
            "targetPath" to local.path().toString(),
            "projectRules" to local.read(),
            "inheritedSources" to Json.array(listOf(Json.obj("path" to "global/config",
                "rules" to PermissionService.fromConfig(inherited.get("permission"))))),
            "effectiveRules" to PermissionService.fromConfig(effective?.get("permission")),
        )
    }

    @Synchronized override fun updateOpenCodePermissions(body: JsonElement?): JsonObject {
        val rules = PermissionService.validateRules(body.asObjectOrNull()?.get("rules"))
        projectPermissions().write(rules)
        server.transport.request("PATCH", "/config", JsonObject(), RequestOptions(directory = project.basePath))
        return readOpenCodePermissions()
    }

    private val permissions = PermissionService(varro.store.VarroStore.getInstance(project), projectRules = { directory ->
        require(directory == null || project.basePath?.let { varro.server.WorkspacePaths.isSame(it, directory) } == true) {
            "Project permission rules must be saved in this project's workspace"
        }
        projectPermissions().read()
    }, saveProjectRules = { rules, _ ->
        projectPermissions().write(rules)
        // Reloading config can dispose the instance that owns the pending request.
        // The webview's next `always` reply applies the rule to the current runtime.
    }) { method, path, body, directory ->
        server.transport.request(method, path, body, RequestOptions(directory = directory)).data
    }

    override fun permissionRules(sessionId: String, rules: JsonElement?, directory: String?) =
        permissions.sessionRules(sessionId, rules, directory)

    @Synchronized override fun allowPermission(body: JsonObject, project: Boolean, directory: String?): JsonArray =
        try {
            permissions.allow(body, project, directory)
        } catch (failure: Exception) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(VarroProjectService.NOTIFICATION_GROUP)
                .createNotification("Varro", "Could not save Always Allow: ${failure.message}", NotificationType.ERROR)
                .notify(this.project)
            throw failure
        }

    private val projectPermissionConfig by lazy { ProjectPermissionConfig(
        Path.of(project.guessProjectDir()?.path ?: project.basePath ?: error("Project has no workspace directory")),
    ) }
    private fun projectPermissions() = projectPermissionConfig

    // --- Automatic permission approval ----------------------------------------

    private val judge = PermissionJudge({ settings.chatAutoApproveModel }, { method, path, body, timeout ->
        server.transport.request(method, path, body, RequestOptions(timeoutMs = timeout)).data
    }) { id ->
        val store = varro.store.VarroStore.getInstance(project)
        synchronized(store) { store.hiddenSessionIds = store.hiddenSessionIds + id }
    }

    override fun judgePermission(body: JsonElement?): JsonObject = judge.judge(body)

    override fun judgeModel(providerId: String?, modelId: String?, variant: String?): JsonObject {
        return Json.obj("model" to judge.model(Json.obj("providerID" to providerId, "modelID" to modelId, "variant" to variant)))
    }

    // --- Provider quotas ------------------------------------------------------

    override fun providerLimit(providerId: String, modelId: String?): JsonObject =
        providerQuotas.get(providerId, modelId, project.guessProjectDir()?.path ?: project.basePath)

    // --- Session diff summary -------------------------------------------------

    private val sessionSummaries = SessionSummaryService(
        readLocal = LocalSessionSummary(LocalUsageDatabase.defaultPath(EnvironmentUtil.getEnvironmentMap()))::read,
        request = { path, directory ->
            server.transport.request("GET", path, options = RequestOptions(directory = directory)).data
        },
    )

    override fun sessionDiffSummary(sessionId: String, directory: String?, revision: String?): JsonObject =
        sessionSummaries.read(sessionId, directory)

}
