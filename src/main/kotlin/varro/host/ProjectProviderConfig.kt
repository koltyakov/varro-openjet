package varro.host

import varro.protocol.*
import java.nio.file.Path

/** Workspace policy for automatically discovered V2 providers. */
internal class ProjectProviderConfig(private val directory: Path, private val beforeWrite: () -> Unit = {}) {
    fun path(): Path = ProjectConfigPath.resolve(directory, native = true)

    @Synchronized fun disable(providerID: String) {
        require(providerID in setOf("ollama", "lmstudio", "vllm")) { "Invalid local provider" }
        val file = path()
        ProjectConfigEdit.update(file, beforeWrite) { original ->
            val document = if (original.isNotBlank()) {
                Json.parseOrNull(ProjectPermissionConfig.stripJsonComments(original)).asObjectOrNull()
                    ?: error("Could not parse $file")
            } else Json.obj()
            val native = document.has("policies")
            if (!native) require(!document.has("experimental") || document.get("experimental").isJsonObject) { "Invalid OpenCode experimental configuration" }
            val container = if (native) document else document.obj("experimental") ?: Json.obj()
            require(!container.has("policies") || container.get("policies").isJsonArray) { "Invalid OpenCode provider policies" }
            val policies = container.arr("policies")?.filter {
                val policy = it.asObjectOrNull()
                policy.str("action") != "provider.use" || policy.str("resource") != providerID
            }.orEmpty()
            JsoncEdit.set(original, if (native) listOf("policies") else listOf("experimental", "policies"),
                Json.array(policies + Json.obj("action" to "provider.use", "resource" to providerID, "effect" to "deny")))
        }
    }
}
