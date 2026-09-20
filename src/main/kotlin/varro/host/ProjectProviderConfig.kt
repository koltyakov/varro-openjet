package varro.host

import varro.protocol.*
import varro.store.JsonJournal
import java.nio.file.Files
import java.nio.file.Path

/** Workspace policy for automatically discovered V2 providers. */
internal class ProjectProviderConfig(private val directory: Path) {
    fun path(): Path = ProjectConfigPath.resolve(directory, native = true)

    @Synchronized fun disable(providerID: String) {
        require(providerID in setOf("ollama", "lmstudio", "vllm")) { "Invalid local provider" }
        val file = path()
        val document = if (Files.exists(file)) {
            require(Files.size(file) <= 4 * 1024 * 1024) { "OpenCode project configuration is too large" }
            Json.parseOrNull(ProjectPermissionConfig.stripJsonComments(Files.readString(file))).asObjectOrNull()
                ?: error("Could not parse $file")
        } else Json.obj()
        require(!document.has("experimental") || document.get("experimental").isJsonObject) { "Invalid OpenCode experimental configuration" }
        val experimental = document.obj("experimental") ?: Json.obj().also { document.add("experimental", it) }
        require(!experimental.has("policies") || experimental.get("policies").isJsonArray) { "Invalid OpenCode provider policies" }
        val policies = experimental.arr("policies")?.filter {
            val policy = it.asObjectOrNull()
            policy.str("action") != "provider.use" || policy.str("resource") != providerID
        }.orEmpty()
        experimental.add("policies", Json.array(policies + Json.obj("action" to "provider.use", "resource" to providerID, "effect" to "deny")))
        JsonJournal(file).write(document)
    }
}
