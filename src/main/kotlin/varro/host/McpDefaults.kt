package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path

/** File defaults, independent of live connections and per-session selections. */
internal class McpDefaults(
    private val directory: Path,
    private val globalDirectory: Path,
    private val native: Boolean,
    private val beforeWrite: (Path) -> Unit = {},
    private val refresh: (Boolean) -> Unit = {},
) {
    private data class Config(val path: Path, val raw: String, val document: JsonObject, val global: Boolean)
    private data class Snapshot(val files: List<Config>, val target: Path)

    fun read(): JsonObject = defaults(snapshot().files)

    fun add(body: JsonElement?): JsonObject {
        val request = body.asObjectOrNull()
        val scope = request.str("scope")
        require(scope in setOf("global", "project")) { "Choose Global or This project for the MCP configuration." }
        val text = request.str("json") ?: error("MCP configuration must contain JSON and an optional server name.")
        require(request?.has("name") != true || request.str("name") != null) { "MCP server name must be a string." }
        val snippet = McpConfigSnippet.parse(text, request.str("name").orEmpty())
        require(snippet.servers.size() > 0) { "Enter a name for this MCP server." }
        val global = scope == "global"
        fun target(snapshot: Snapshot) = if (global) snapshot.files.lastOrNull { it.global }?.path
            ?: globalDirectory.resolve("opencode.json") else snapshot.target
        while (true) {
            val selected = canonical(target(snapshot()))
            var retry = false
            ProjectConfigEdit.update(selected, { beforeWrite(selected) }) edit@{ original ->
                val current = snapshot()
                if (canonical(target(current)) != selected) { retry = true; return@edit original }
                val configured = current.files.find { canonical(it.path) == selected }
                require(configured?.raw.orEmpty() == original) { "OpenCode configuration changed while saving. Try again." }
                beforeWrite(selected)
                val duplicate = snippet.servers.keySet().firstOrNull { name -> current.files.any { it.global == global && servers(it.document).has(name) } }
                require(duplicate == null) { "MCP server $duplicate is already configured in this scope. Open the config file to edit it." }
                val document = configured?.document ?: Json.obj()
                val mcp = document.obj("mcp")
                require((!document.has("mcp") || mcp != null) && (mcp?.has("servers") != true || mcp.obj("servers") != null)) {
                    "The existing MCP configuration is invalid. Open the config file to fix it before adding servers."
                }
                val nested = native && (mcp.obj("servers") != null || servers(document).size() == 0)
                var raw = original.ifBlank { "{}\n" }
                if (!document.hasNonNull("\$schema")) raw = JsoncEdit.set(raw, listOf("\$schema"), Json.toElement("https://opencode.ai/config.json"))
                snippet.servers.entrySet().forEach { (name, value) ->
                    val server = value.asJsonObject.deepCopy()
                    server.bool(if (nested) "enabled" else "disabled")?.let { server.addProperty(if (nested) "disabled" else "enabled", !it) }
                    server.remove(if (nested) "enabled" else "disabled")
                    raw = JsoncEdit.set(raw, (if (nested) listOf("mcp", "servers") else listOf("mcp")) + name, server)
                }
                if (raw.endsWith('\n')) raw else "$raw\n"
            }
            if (retry) continue
            refresh(global)
            return read()
        }
    }

    /** Opening an invalid config must still work so the user can repair it. */
    fun open(scope: String, isOpen: (Path) -> Boolean = { false }): Path {
        require(scope in setOf("global", "project")) { "Choose Global or This project for the MCP configuration." }
        val global = scope == "global"
        val paths = paths().filter { (it in globalPaths()) == global }
        val selected = paths.lastOrNull(Files::exists) ?: if (global) globalDirectory.resolve("opencode.json") else directory.resolve("opencode.json")
        val target = canonical(selected)
        if (Files.exists(target) || isOpen(target)) return target
        ProjectConfigEdit.update(target, { require(!isOpen(target)) { "$target is already open; save before creating configuration." } }) { original ->
            if (Files.exists(target) || isOpen(target)) original else "{\n  \"\$schema\": \"https://opencode.ai/config.json\"\n}\n"
        }
        return target
    }

    fun update(body: JsonElement?): JsonObject {
        val request = body.asObjectOrNull()
        val name = request.text("name")
        val scope = request.text("scope")
        val enabled = request.bool("enabled")
        require(name != null && name.length in 1..512 && scope in setOf("global", "project") &&
            (enabled != null || scope == "project" && request?.get("enabled")?.isJsonNull == true)) { "Invalid MCP default update" }
        val global = scope == "global"
        fun target(snapshot: Snapshot): Path = if (!global) snapshot.target else snapshot.files.lastOrNull {
            it.global && servers(it.document).obj(name) != null
        }?.path ?: error("This MCP is only configured in the project. Add it globally before changing its global default.")
        while (true) {
            val selected = canonical(target(snapshot()))
            var retry = false
            var changed = false
            ProjectConfigEdit.update(selected, { beforeWrite(selected) }) edit@{ original ->
                val current = snapshot()
                if (canonical(target(current)) != selected) {
                    retry = true
                    return@edit original
                }
                val configured = current.files.find { canonical(it.path) == selected }
                require(configured?.raw.orEmpty() == original) { "OpenCode configuration changed while saving. Try again." }
                beforeWrite(selected)
                val globalServer = resolve(current.files.filter { it.global }, name)
                val inherited = (if (global) globalServer else resolve(current.files, name))
                    ?: error("MCP server $name is no longer configured")
                val local = configured?.let { servers(it.document).obj(name) }
                val otherProject = current.files.firstOrNull { !it.global && canonical(it.path) != selected && servers(it.document).obj(name) != null }
                if (enabled == null) {
                    require(globalServer != null) { "MCP server $name has no global configuration to fall back to" }
                    require(otherProject == null) { "MCP server $name is also configured in ${otherProject?.path}" }
                    require(local == null || inheritedCopy(local, globalServer)) {
                        "$selected has its own connection settings for $name; edit that file to use the global MCP"
                    }
                }
                val remove = enabled == null || !global && otherProject == null && globalServer != null &&
                    enabled == enabled(globalServer) && (local == null || inheritedCopy(local, globalServer))
                if (remove && local == null) return@edit original
                val document = configured?.document ?: Json.obj()
                val nested = native && (document.obj("mcp").obj("servers") != null || local == null)
                val path = if (nested) listOf("mcp", "servers", name) else listOf("mcp", name)
                var raw = original
                if (!document.has("\$schema") && !remove) raw = JsoncEdit.set(raw, listOf("\$schema"), Json.toElement("https://opencode.ai/config.json"))
                raw = when {
                    remove -> JsoncEdit.set(raw, path, null)
                    local != null -> {
                        val key = if (nested) "disabled" else "enabled"
                        JsoncEdit.set(JsoncEdit.set(raw, path + key, Json.toElement(if (nested) enabled == false else enabled == true)),
                            path + if (nested) "enabled" else "disabled", null)
                    }
                    else -> JsoncEdit.set(raw, path, inherited.deepCopy().apply {
                        remove(if (nested) "enabled" else "disabled")
                        addProperty(if (nested) "disabled" else "enabled", if (nested) enabled == false else enabled == true)
                    })
                }
                changed = raw != original
                raw
            }
            if (retry) continue
            if (changed) refresh(global)
            return read()
        }
    }

    private fun globalPaths() = listOf("config.json", "opencode.json", "opencode.jsonc").map(globalDirectory::resolve)

    private fun paths(): List<Path> {
        val root = directory.toAbsolutePath().normalize()
        val ancestors = mutableListOf<Path>()
        var current: Path? = root
        while (current != null) {
            ancestors.add(current)
            if (!native && Files.exists(current.resolve(".git"))) break
            current = current.parent
        }
        val globals = globalPaths()
        val projects = ancestors.asReversed().flatMap { ancestor -> listOf("opencode.json", "opencode.jsonc").map(ancestor::resolve) } +
            if (native) ancestors.asReversed().flatMap { ancestor -> listOf("opencode.json", "opencode.jsonc").map(ancestor.resolve(".opencode")::resolve) } else emptyList()
        return (globals + projects).distinct()
    }

    private fun snapshot(): Snapshot {
        val globals = globalPaths()
        val files = paths().filter(Files::exists).map { path ->
            require(Files.size(path) <= 4 * 1024 * 1024) { "OpenCode configuration is too large" }
            val raw = Files.readString(path)
            val document = Json.parseOrNull(ProjectPermissionConfig.stripJsonComments(raw.ifBlank { "{}" })).asObjectOrNull()
                ?: error("Could not parse $path")
            Config(path, raw, document, path in globals)
        }
        return Snapshot(files, ProjectConfigPath.resolve(directory.toAbsolutePath().normalize(), native))
    }

    private fun servers(document: JsonObject): JsonObject {
        val mcp = document.obj("mcp") ?: return Json.obj()
        if (!native) return mcp
        return JsonObject().apply { (mcp.obj("servers") ?: mcp).entrySet().forEach { (name, value) ->
            val server = value.asObjectOrNull()
            if (server.str("type") in setOf("local", "remote") || server.bool("enabled") != null || server.bool("disabled") != null) add(name, value)
        } }
    }

    private fun resolve(files: List<Config>, name: String): JsonObject? {
        var result: JsonObject? = null
        files.forEach { file -> servers(file.document).obj(name)?.let { server ->
            result = if (native) server.deepCopy() else (result ?: Json.obj()).apply { server.entrySet().forEach { (key, value) -> add(key, value) } }
        } }
        return result
    }

    private fun inheritedCopy(server: JsonObject, inherited: JsonObject): Boolean {
        fun settings(value: JsonObject) = value.deepCopy().apply { remove("enabled"); remove("disabled") }
        val parent = settings(inherited)
        val local = settings(server)
        return (if (native) local else parent.deepCopy().apply { local.entrySet().forEach { (key, value) -> add(key, value) } }) == parent
    }

    private fun defaults(files: List<Config>): JsonObject {
        val global = Json.obj()
        val project = Json.obj()
        val effective = Json.obj()
        val inherited = mutableMapOf<String, JsonObject>()
        files.forEach { file -> servers(file.document).entrySet().forEach { (name, value) ->
            val server = value.asObjectOrNull() ?: return@forEach
            val resolved = if (native) server else (inherited[name]?.deepCopy() ?: Json.obj()).apply {
                server.entrySet().forEach { (key, setting) -> add(key, setting) }
            }
            inherited[name] = resolved
            (if (file.global) global else project).addProperty(name, enabled(resolved))
            effective.addProperty(name, enabled(resolved))
        } }
        return Json.obj("global" to global, "project" to project, "effective" to effective, "editable" to true).apply {
            val oauth = inherited.filter { (_, server) -> server.str("type") == "remote" && server.bool("oauth") != false }.keys
            if (oauth.isNotEmpty()) add("oauthServers", Json.array(oauth))
        }
    }

    fun attached(document: JsonObject): JsonObject = defaults(listOf(Config(directory, "", document, false))).apply {
        add("global", Json.obj()); add("project", Json.obj()); addProperty("editable", false)
    }

    private fun enabled(server: JsonObject) = server.bool("disabled") != true && server.bool("enabled") != false
    private fun canonical(path: Path): Path = if (Files.exists(path)) path.toRealPath() else
        (if (Files.exists(path.parent)) path.parent.toRealPath().resolve(path.fileName) else path.toAbsolutePath().normalize())
}
