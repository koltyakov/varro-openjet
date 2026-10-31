package varro.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

/** Translate managed-server paths at the wire boundary, leaving IDE path identity intact. */
internal class OpenCodePathAliases(
    private val isEnabled: () -> Boolean = { true },
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", true),
) {
    private data class Alias(val logical: String, val physical: String)
    private val physicalDirectories = linkedMapOf<String, String?>()
    private val aliases = mutableListOf<Alias>()

    private fun enabled(): Boolean = !isWindows && isEnabled()

    @Synchronized fun toServerDirectory(directory: String?): String? {
        if (directory.isNullOrEmpty() || !enabled()) return directory
        val root = directory.trimEnd('/').ifEmpty { directory }
        if (!root.startsWith('/')) return directory
        if (!physicalDirectories.containsKey(root)) {
            val physical = runCatching { Path.of(root).toRealPath().toString() }.getOrNull()
            if (physicalDirectories.size >= 256) physicalDirectories.clear()
            physicalDirectories[root] = physical
            if (physical != null && physical != root && aliases.none { it.logical == root }) {
                aliases.add(Alias(root, physical))
            }
        }
        // Missing descendants can still use an already resolved workspace root.
        return physicalDirectories[root] ?: replaceRoot(directory, true)
    }

    @Synchronized fun toServerRequestPath(path: String): String {
        if (!enabled() || '?' !in path) return path
        var changed = false
        val query = path.substringAfter('?').substringBefore('#').split('&').map { pair ->
            val key = decode(pair.substringBefore('='))
            if (key !in setOf("directory", "location[directory]")) return@map pair
            val directory = decode(pair.substringAfter('=', ""))
            val physical = toServerDirectory(directory)
            if (physical == directory) return@map pair
            changed = true
            "${pair.substringBefore('=')}=${URLEncoder.encode(physical, UTF_8).replace("+", "%20")}"
        }.joinToString("&")
        // Keep the path intact so the request-scope origin guard remains authoritative.
        return if (changed) path.substringBefore('?') + "?" + query else path
    }

    @Synchronized fun toServer(value: JsonElement?): JsonElement? =
        if (!enabled()) value else mapStrings(value) { text, key ->
            if (key == "directory") toServerDirectory(text)!! else replaceRoot(text, true)
        }

    @Synchronized fun fromServer(value: JsonElement?): JsonElement? =
        if (!enabled() || aliases.isEmpty()) value else mapStrings(value) { text, _ -> replaceRoot(text, false) }

    private fun replaceRoot(text: String, toServer: Boolean): String {
        // Rewrite whole single-line path values only, never multiline tool output.
        if (!text.startsWith('/') || '\n' in text) return text
        val match = aliases.filter {
            val from = if (toServer) it.logical else it.physical
            text == from || text.startsWith("$from/")
        }.maxByOrNull { if (toServer) it.logical.length else it.physical.length } ?: return text
        val from = if (toServer) match.logical else match.physical
        return (if (toServer) match.physical else match.logical) + text.substring(from.length)
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%20"), UTF_8)
    }.getOrDefault(value)

    /** Copy only changed branches. Callers and unchanged responses retain their original objects. */
    private fun mapStrings(value: JsonElement?, key: String? = null, map: (String, String?) -> String): JsonElement? {
        if (value == null) return null
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val text = value.asString
            val next = map(text, key)
            return if (next == text) value else JsonPrimitive(next)
        }
        if (value.isJsonArray) {
            var mapped: JsonArray? = null
            value.asJsonArray.forEachIndexed { index, item ->
                val next = mapStrings(item, map = map)
                if (next !== item) {
                    if (mapped == null) mapped = JsonArray().apply { value.asJsonArray.forEach(::add) }
                    mapped.set(index, next)
                }
            }
            return mapped ?: value
        }
        if (value.isJsonObject) {
            var mapped: JsonObject? = null
            value.asJsonObject.entrySet().forEach { (name, item) ->
                val next = mapStrings(item, name, map)
                if (next !== item) {
                    if (mapped == null) mapped = JsonObject().apply { value.asJsonObject.entrySet().forEach { add(it.key, it.value) } }
                    mapped.add(name, next)
                }
            }
            return mapped ?: value
        }
        return value
    }
}
