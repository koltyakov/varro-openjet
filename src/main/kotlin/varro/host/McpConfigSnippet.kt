package varro.host

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import varro.protocol.*
import java.net.URI

/** Host-side validation of pasted JSONC. Secret references remain literal configuration values. */
internal object McpConfigSnippet {
    private val json = GsonBuilder().setStrictness(Strictness.STRICT).create()
    data class Snippet(val servers: JsonObject, val requiresName: Boolean)

    fun parse(text: String, name: String = ""): Snippet {
        require(text.length <= 100_000) { "MCP JSON must be smaller than 100 KB." }
        var config = try { json.fromJson(ProjectPermissionConfig.stripJsonComments(text), JsonElement::class.java).asObjectOrNull() }
        catch (_: Exception) { error("Invalid MCP JSON. Paste valid JSON or JSONC configuration.") }
        require(config != null) { "Paste an MCP server object or a named map of servers." }
        config = when {
            config.has("mcp") -> config.obj("mcp")?.let { if (it.has("servers")) it.obj("servers") else it }
            config.has("mcpServers") -> config.obj("mcpServers")
            config.has("servers") -> config.obj("servers")
            else -> config
        }
        require(config != null) { "The MCP servers section must be an object." }
        val requiresName = config.str("type") != null || config.str("url") != null || config.str("command") != null || config.arr("command") != null
        if (requiresName) {
            val server = server(config)
            return Snippet(if (name.isBlank()) Json.obj() else Json.obj(serverName(name.trim()) to server), true)
        }
        require(config.size() > 0) { "The JSON contains no MCP servers." }
        return Snippet(JsonObject().apply { config.entrySet().forEach { (key, value) ->
            add(serverName(key), server(value.asObjectOrNull() ?: error("Each MCP server must be a JSON object.")))
        } }, false)
    }

    private fun serverName(name: String): String {
        require(name.isNotBlank() && name == name.trim() && name.length <= 512 && name !in setOf("__proto__", "constructor", "prototype")) {
            "Use a non-empty MCP server name without surrounding spaces or reserved object keys."
        }
        return name
    }

    private fun server(value: JsonObject): JsonObject = value.deepCopy().apply {
        val command = str("command")
        if (command != null) {
            require(!has("args") || arr("args")?.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true) {
                "MCP command args must be an array of strings."
            }
            add("command", Json.array(listOf(command) + arr("args")?.strings().orEmpty()))
            remove("args")
        } else require(!has("args")) { "Put command arguments in the command array, or use a command string with args." }
        if (has("env")) {
            require(!has("environment")) { "Use either env or environment, not both." }
            add("environment", remove("env"))
        }
        if (!hasNonNull("type")) {
            when { has("command") -> addProperty("type", "local"); has("url") -> addProperty("type", "remote") }
        }
        when (str("type")) {
            "local" -> require(arr("command")?.let { parts -> !parts.isEmpty && parts.all {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            } && parts.first().asString.isNotBlank() } == true) { "A local MCP server needs a non-empty command array." }
            "remote" -> {
                val uri = str("url")?.let { runCatching { URI(it) }.getOrNull() }
                require(uri != null && uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https") && uri.rawAuthority != null) {
                    "The MCP server URL must be an absolute HTTP or HTTPS URL."
                }
            }
            else -> error("An MCP server needs type local with a command, or type remote with a URL.")
        }
        for (key in listOf("headers", "environment")) require(!has(key) || obj(key)?.entrySet()?.all {
            it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString
        } == true) { "MCP $key must be an object of strings." }
        for (key in listOf("enabled", "disabled")) require(!has(key) || bool(key) != null) { "MCP $key must be a boolean." }
        require(!has("enabled") || !has("disabled")) { "Use either enabled or disabled, not both." }
    }
}
