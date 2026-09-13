package varro.settings

import com.google.gson.JsonObject
import varro.protocol.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Cross-process storage. The separate lock file survives atomic settings replacement. */
class SharedSettingsFile(val path: Path) {
    fun read(): JsonObject? {
        if (!Files.exists(path)) return null
        val document = Json.parseOrNull(Files.readString(path))
        require(document != null && document.isJsonObject) { "Invalid OpenJet settings JSON: $path" }
        return document.asJsonObject.also {
            require(it.get("version")?.asInt == 1) { "Unsupported OpenJet settings version: $path" }
            require(it.get("core")?.isJsonObject == true && it.get("models")?.isJsonObject == true) {
                "Invalid OpenJet settings sections: $path"
            }
            SharedCoreSettings.apply(it.getAsJsonObject("core"), VarroSettings())
        }
    }

    /** A missing file is created only by explicit migration, never by background synchronization. */
    @Synchronized
    fun update(before: JsonObject, current: JsonObject, initialize: Boolean = false): JsonObject? {
        if (!initialize && !Files.exists(path)) return null
        Files.createDirectories(path.parent)
        FileChannel.open(path.resolveSibling("settings.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val existing = read()
                if (initialize && existing != null) return existing
                if (!initialize && existing == null) return null
                val next = existing?.deepCopy() ?: current.deepCopy()
                if (existing != null) mergeChanges(before, current, next)
                next.addProperty("version", 1)
                if (next != existing) {
                    val temporary = Files.createTempFile(path.parent, "settings-", ".tmp")
                    try {
                        Files.writeString(temporary, Json.stringify(next))
                        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
                return next
            }
        }
    }

    companion object {
        fun location(
            os: String = System.getProperty("os.name"),
            home: Path = Path.of(System.getProperty("user.home")),
            environment: Map<String, String> = System.getenv(),
        ): Path {
            val base = when {
                os.startsWith("Windows", ignoreCase = true) ->
                    environment["APPDATA"]?.takeIf(String::isNotBlank)?.let(Path::of)
                        ?.resolve("OpenJet") ?: home.resolve("AppData/Roaming/OpenJet")
                os.startsWith("Mac", ignoreCase = true) -> home.resolve("Library/Application Support/OpenJet")
                else -> environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?.takeIf(Path::isAbsolute)?.resolve("openjet") ?: home.resolve(".config/openjet")
            }
            return base.resolve("settings.json")
        }

        private fun mergeChanges(before: JsonObject, current: JsonObject, target: JsonObject) {
            (before.keySet() + current.keySet()).forEach { key ->
                val old = before.get(key)
                val value = current.get(key)
                if (old == value) return@forEach
                if (old?.isJsonObject == true && value?.isJsonObject == true && target.get(key)?.isJsonObject == true) {
                    mergeChanges(old.asJsonObject, value.asJsonObject, target.getAsJsonObject(key))
                } else if (value == null) target.remove(key)
                else target.add(key, value.deepCopy())
            }
        }
    }
}
