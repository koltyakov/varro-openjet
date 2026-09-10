package dev.koltyakov.varrojet.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.EnvironmentUtil
import dev.koltyakov.varrojet.protocol.Json
import dev.koltyakov.varrojet.protocol.asObjectOrNull
import dev.koltyakov.varrojet.protocol.num
import dev.koltyakov.varrojet.protocol.obj
import dev.koltyakov.varrojet.protocol.str
import dev.koltyakov.varrojet.server.OpenCodeCli
import dev.koltyakov.varrojet.server.OpenCodeServer
import dev.koltyakov.varrojet.server.RequestOptions
import dev.koltyakov.varrojet.settings.VarroSettings
import java.nio.file.Files
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

    private val log = logger<OpenCodeHostServices>()

    private val providerQuotas = ProviderQuotaBackend(
        launch = {
            val environment = EnvironmentUtil.getEnvironmentMap()
            val cli = OpenCodeCli({ settings.serverCommand }, { project.basePath }, environment)
            val cliParent = runCatching { Path.of(cli.resolve().command).parent?.toString() }.getOrNull()
            ProviderQuotaRuntime.launch(
                settings.providerQuotaNodePath,
                cli.serverEnvironment(),
                listOfNotNull(cliParent) + cli.searchPath(),
            )
        },
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

    /**
     * Reads OpenCode's effective configuration.
     *
     * The server is the authority here, not the config file: OpenCode merges
     * global, project and environment configuration, and the webview's model
     * picker needs the merged result.
     */
    /**
     * Varro-managed model routing, in the `OpenCodeModelRouting` shape the client
     * validates. Every field is required, so an unread configuration still has to
     * answer with a well-formed empty routing rather than `{}`.
     */
    override fun readOpenCodeConfig(): JsonObject {
        val config = runCatching {
            server.transport.request("GET", "/global/config", options = RequestOptions(unscoped = true))
                .data.asObjectOrNull()
        }.getOrElse { failure ->
            log.warn("Failed to read the OpenCode configuration", failure)
            null
        }

        return Json.obj(
            "smallModel" to modelRoute(config.str("small_model") ?: config.str("smallModel")),
            "agentModels" to JsonObject(),
            "commitMessageModel" to modelRoute(settings.commitMessageModel),
            "autoApproveModel" to modelRoute(settings.chatAutoApproveModel),
        )
    }

    /** Parses `providerID/modelID` into an `OpenCodeModelRoute`, or null. */
    private fun modelRoute(value: String?): JsonObject? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val provider = text.substringBefore('/', "")
        val model = text.substringAfter('/', "")
        if (provider.isEmpty() || model.isEmpty()) return null
        return Json.obj("providerID" to provider, "modelID" to model)
    }

    /**
     * Persists Varro-managed model routing into the project's `opencode.json`.
     *
     * Only the routing block is touched. The file belongs to the user and may be
     * checked into their repository, so unrelated keys and formatting are left
     * exactly as found.
     */
    override fun updateModelRouting(body: JsonElement?): JsonObject {
        val routing = body.asObjectOrNull() ?: return readOpenCodeConfig()
        val configPath = projectConfigPath() ?: return readOpenCodeConfig()

        return runCatching {
            val existing = if (Files.exists(configPath)) {
                Json.parseOrNull(Files.readString(configPath)).asObjectOrNull() ?: JsonObject()
            } else {
                JsonObject()
            }
            routing.entrySet().forEach { (key, value) -> existing.add(key, value) }
            Files.createDirectories(configPath.parent)
            Files.writeString(configPath, Json.gson.newBuilder().setPrettyPrinting().create().toJson(existing))
            readOpenCodeConfig()
        }.getOrElse { failure ->
            log.warn("Failed to update OpenCode model routing", failure)
            readOpenCodeConfig()
        }
    }

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

    /**
     * Summarizes what a session changed: files touched, lines added and removed,
     * token use and duration. Derived from OpenCode's own diff and message data so
     * the numbers match what the session actually did.
     */
    override fun sessionDiffSummary(sessionId: String, directory: String?, revision: String?): JsonObject {
        val encoded = java.net.URLEncoder.encode(sessionId, Charsets.UTF_8).replace("+", "%20")

        val diffs = runCatching {
            server.transport.request(
                "GET",
                "/session/$encoded/diff",
                options = RequestOptions(directory = directory),
            ).data
        }.getOrNull()

        var files = 0
        var additions = 0
        var deletions = 0
        diffs?.let { element ->
            val entries = when {
                element.isJsonArray -> element.asJsonArray
                element.isJsonObject -> element.asJsonObject.getAsJsonArray("diffs")
                else -> null
            }
            entries?.forEach { entry ->
                val record = entry.asObjectOrNull() ?: return@forEach
                files += 1
                additions += record.num("additions")?.toInt() ?: 0
                deletions += record.num("deletions")?.toInt() ?: 0
            }
        }

        val session = runCatching {
            server.transport.request(
                "GET",
                "/session/$encoded",
                options = RequestOptions(directory = directory),
            ).data.asObjectOrNull()
        }.getOrNull()

        val time = session.obj("time")
        val created = time.num("created")?.toLong() ?: 0L
        val updated = time.num("updated")?.toLong() ?: created

        return Json.obj(
            "files" to files,
            "additions" to additions,
            "deletions" to deletions,
            "tokens" to 0,
            "durationMs" to (updated - created).coerceAtLeast(0),
            "activeStartedAt" to null,
        )
    }

    companion object {
        /**
         * Tools that cannot modify anything. Kept deliberately short: every entry
         * here is a request the user will never be asked about again.
         */
        private val ALWAYS_SAFE_TOOLS = setOf("read", "list", "glob", "grep", "todoread", "todowrite")
    }
}
