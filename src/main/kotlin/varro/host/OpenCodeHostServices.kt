package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
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

    /**
     * `OpenCodePermissionConfig`. Rules stay OpenCode-owned in this port, so the
     * lists are empty — but all four fields are present, because the client
     * reads them unconditionally.
     */
    override fun readOpenCodePermissions(): JsonObject = Json.obj(
        "targetPath" to (projectConfigPath()?.toString() ?: ""),
        "projectRules" to com.google.gson.JsonArray(),
        "inheritedSources" to com.google.gson.JsonArray(),
        "effectiveRules" to com.google.gson.JsonArray(),
    )

    /**
     * Permission rules stay OpenCode-owned. Varro reads them but does not write
     * them, so users keep a single place to reason about what an agent may do.
     */
    override fun updateOpenCodePermissions(body: JsonElement?): JsonObject =
        // Answering with the unchanged configuration keeps the client's type
        // contract; it re-reads and shows the rules were not adopted.
        readOpenCodePermissions()

    private fun projectConfigPath(): Path? {
        val root = project.guessProjectDir()?.path ?: project.basePath ?: return null
        return Paths.get(root, "opencode.json")
    }

    // --- Automatic permission approval ----------------------------------------

    /**
     * Decides an eligible permission request automatically.
     *
     * The full upstream judge asks a model to classify the request. This port
     * implements only the deterministic half: obviously-safe read and search tools
     * are allowed, and everything else falls through to `ask`, which leaves the
     * request in the UI for the user. Erring toward `ask` is the safe direction -
     * a missed auto-approval costs a click, a wrong one runs something unwanted.
     */
    override fun judgePermission(body: JsonElement?): JsonObject {
        val permission = body.asObjectOrNull()?.get("permission").asObjectOrNull()
            ?: return decision("ask", "No permission payload to evaluate.")

        val tool = (permission.str("type") ?: permission.str("tool") ?: "").lowercase()
        val title = permission.str("title").orEmpty()

        if (tool in ALWAYS_SAFE_TOOLS) {
            return decision("allow", "`$tool` only reads workspace state.")
        }
        return decision("ask", "Varro OpenJet defers ${title.ifBlank { tool }.ifBlank { "this request" }} to you.")
    }

    private fun decision(decision: String, reason: String): JsonObject =
        Json.obj("decision" to decision, "reason" to reason)

    /**
     * The model Varro would use for automatic review. Reported as unset when the
     * user has not configured one, which the webview renders as "no judge model".
     */
    override fun judgeModel(providerId: String?, modelId: String?, variant: String?): JsonObject {
        val configured = settings.chatAutoApproveModel.trim()
        if (configured.isEmpty()) return Json.obj("model" to null)
        val provider = configured.substringBefore('/', "")
        val model = configured.substringAfter('/', "")
        if (provider.isEmpty() || model.isEmpty()) return Json.obj("model" to null)
        return Json.obj("model" to Json.obj("providerID" to provider, "modelID" to model))
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

    companion object {
        /**
         * Tools that cannot modify anything. Kept deliberately short: every entry
         * here is a request the user will never be asked about again.
         */
        private val ALWAYS_SAFE_TOOLS = setOf("read", "list", "glob", "grep", "todoread", "todowrite")
    }
}
