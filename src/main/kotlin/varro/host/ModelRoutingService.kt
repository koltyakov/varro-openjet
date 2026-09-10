package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.obj
import varro.protocol.str
import varro.settings.VarroSettings

/** Translates Models menu actions into global OpenCode config or IDE settings. */
internal class ModelRoutingService(
    private val settings: VarroSettings,
    private val readGlobalConfig: () -> JsonObject,
    private val patchGlobalConfig: (JsonObject) -> JsonObject,
    private val onChanged: () -> Unit,
) {
    fun read(): JsonObject = routing(readGlobalConfig())

    fun update(body: JsonElement?): JsonObject {
        val request = requireNotNull(body.asObjectOrNull()) { "Model routing request must be an object" }
        val target = request.str("target")
        require(target in setOf("small_model", "agent", "commit_message", "auto_approve")) {
            "Unsupported model routing target: $target"
        }
        val provider = request.str("providerID")?.trim().orEmpty()
        val model = request.str("modelID")?.trim().orEmpty()
        require(provider.isNotEmpty() && '/' !in provider && model.isNotEmpty()) { "Provider and model are required" }
        // OpenCode's config API accepts strings, not null. Empty strings restore automatic selection.
        val modelRef = if (request.bool("unset") == true) "" else "$provider/$model"
        val config = when (target) {
            "small_model" -> patchGlobalConfig(Json.obj("small_model" to modelRef))
            "agent" -> {
                val agent = request.str("agentName")?.trim().orEmpty()
                require(agent.isNotEmpty()) { "Agent name is required" }
                patchGlobalConfig(Json.obj("agent" to Json.obj(agent to Json.obj("model" to modelRef))))
            }
            else -> {
                // Read first so a failed request cannot partially save an IDE setting.
                val current = readGlobalConfig()
                if (target == "commit_message") settings.commitMessageModel = modelRef
                else settings.chatAutoApproveModel = modelRef
                current
            }
        }
        onChanged()
        return routing(config)
    }

    private fun routing(config: JsonObject): JsonObject {
        val agents = JsonObject()
        config.obj("agent")?.entrySet()?.forEach { (name, value) ->
            modelRoute(value.asObjectOrNull().str("model"))?.let { agents.add(name, it) }
        }
        return Json.obj(
            "smallModel" to modelRoute(config.str("small_model") ?: config.str("smallModel")),
            "agentModels" to agents,
            "commitMessageModel" to modelRoute(settings.commitMessageModel),
            "autoApproveModel" to modelRoute(settings.chatAutoApproveModel),
        )
    }

    private fun modelRoute(value: String?): JsonObject? {
        val text = value?.trim().orEmpty()
        val provider = text.substringBefore('/', "")
        val model = text.substringAfter('/', "")
        if (provider.isEmpty() || model.isEmpty()) return null
        return Json.obj("providerID" to provider, "modelID" to model)
    }
}
