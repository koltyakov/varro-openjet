package varro.host

import com.google.gson.JsonElement
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sets or removes OpenCode's global `warming` setting, which keeps every root session's prompt
 * cache alive. Port of `OpenCodeConfigService.updateGlobalWarming`.
 *
 * Setting a value edits the highest-precedence global file that already has `warming`, else the
 * highest-precedence existing global file, else a new `opencode.json`. Removing it edits every
 * global file that has the key, so no lower-precedence copy keeps warming on. Edits preserve
 * comments and formatting, and refuse files with unsaved IDE changes or concurrent edits.
 */
internal class GlobalWarmingConfig(
    private val directory: Path,
    private val beforeWrite: (Path) -> Unit = {},
) {
    /** @return whether any file changed. */
    fun update(value: JsonElement?): Boolean {
        val paths = GLOBAL_FILES.map(directory::resolve)
        val existing = paths.filter(Files::exists)
        val configured = existing.filter { read(it).has(KEY) }
        val targets = if (value == null) configured
            else listOf(configured.lastOrNull() ?: existing.lastOrNull() ?: directory.resolve("opencode.json"))
        var changed = false
        for (path in targets) {
            val target = ProjectConfigEdit.canonical(path)
            ProjectConfigEdit.update(target, { beforeWrite(target) }) { original ->
                val raw = original.ifBlank { "{\n  \"\$schema\": \"https://opencode.ai/config.json\"\n}\n" }
                val current = parse(raw, target)
                if (value == null && !current.has(KEY)) return@update original
                if (value != null && current.get(KEY) == value) return@update original
                val next = JsoncEdit.set(raw, listOf(KEY), value)
                (if (next.endsWith("\n")) next else "$next\n").also { changed = changed || it != original }
            }
        }
        return changed
    }

    private fun read(path: Path) = parse(Files.readString(path).also {
        require(it.length <= 4 * 1024 * 1024) { "OpenCode configuration is too large" }
    }, path)

    private fun parse(raw: String, path: Path) =
        Json.parseOrNull(ProjectPermissionConfig.stripJsonComments(raw.ifBlank { "{}" })).asObjectOrNull()
            ?: error("Could not parse $path")

    companion object {
        private const val KEY = "warming"
        /** Lowest to highest precedence, matching `getOpenCodeConfigPaths`. */
        private val GLOBAL_FILES = listOf("config.json", "opencode.json", "opencode.jsonc")
    }
}
