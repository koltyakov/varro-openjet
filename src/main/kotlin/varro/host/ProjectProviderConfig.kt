package varro.host

import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path

/** Workspace policy for automatically discovered V2 providers. */
internal class ProjectProviderConfig(private val directory: Path, private val beforeWrite: () -> Unit = {}) {
    fun path(): Path = ProjectConfigPath.resolve(directory, native = true)

    @Synchronized fun disable(providerID: String) {
        require(providerID in setOf("ollama", "lmstudio", "vllm")) { "Invalid local provider" }
        val file = path()
        if (Files.exists(file)) require(Files.size(file) <= 4 * 1024 * 1024) { "OpenCode project configuration is too large" }
        val original = if (Files.exists(file)) Files.readString(file) else null
        val document = if (original != null) {
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
        val updated = JsoncEdit.set(original.orEmpty(), if (native) listOf("policies") else listOf("experimental", "policies"),
            Json.array(policies + Json.obj("action" to "provider.use", "resource" to providerID, "effect" to "deny")))
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "provider-policy-", ".tmp")
        try {
            Files.writeString(temporary, updated)
            beforeWrite()
            require((if (Files.exists(file)) Files.readString(file) else null) == original) { "OpenCode configuration changed while updating provider policies. Try again." }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
