package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path

/** Targets only the last global config file, never a workspace or injected runtime document. */
internal class GlobalPermissionConfig(directory: Path, beforeWrite: (Path) -> Unit = {}) {
    private val paths = listOf("config.json", "opencode.json", "opencode.jsonc").map(directory::resolve)
    private val config = ProjectPermissionConfig(directory, { true },
        target = { paths.lastOrNull(Files::exists) ?: directory.resolve("opencode.json") }, beforeSave = beforeWrite)

    fun read(): JsonObject = Json.obj("targetPath" to config.path().toString(), "rules" to config.read(), "supportsPatterns" to config.supportsPatterns())
    fun write(rules: JsonArray): JsonObject { config.write(rules); return read() }
    fun allow(permission: String, patterns: List<String>): JsonArray {
        var effective: com.google.gson.JsonElement? = null
        for (path in paths.filter(Files::exists)) {
            require(Files.size(path) <= 4 * 1024 * 1024) { "OpenCode global configuration is too large" }
            val document = Json.parseOrNull(ProjectPermissionConfig.stripJsonComments(Files.readString(path))).asObjectOrNull()
                ?: error("Could not parse $path")
            document.get("permission")?.let { value ->
                if (value.isJsonObject && effective?.isJsonObject == true) {
                    val merged = effective.asJsonObject.deepCopy()
                    value.asJsonObject.entrySet().forEach { (key, action) -> merged.add(key, action) }
                    effective = merged
                } else effective = value
            }
        }
        return config.allow(permission, patterns, effective)
    }
}
