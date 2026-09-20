package varro.host

import java.nio.file.Files
import java.nio.file.Path

internal object ProjectConfigPath {
    fun resolve(directory: Path, native: Boolean): Path {
        // V2 applies all .opencode configs after direct configs, including ancestors above Git roots.
        val hasNestedConfig = native && generateSequence(directory.toAbsolutePath().normalize()) { it.parent }.any { ancestor ->
            listOf("opencode.json", "opencode.jsonc").any { Files.exists(ancestor.resolve(".opencode").resolve(it)) }
        }
        val target = if (hasNestedConfig) directory.resolve(".opencode") else directory
        return target.resolve("opencode.jsonc").takeIf(Files::exists) ?: target.resolve("opencode.json")
    }
}
