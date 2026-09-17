package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.JsonJournal
import java.nio.file.Files
import java.nio.file.Path

/** V2 has no legacy global-config patch route. Edit only the selected global file. */
internal class OpenCodeGlobalConfig(private val directory: Path) {
    @Synchronized fun patch(patch: JsonObject) {
        val path = directory.resolve("opencode.jsonc").takeIf(Files::exists) ?: directory.resolve("opencode.json")
        val document = if (Files.exists(path)) {
            require(Files.size(path) <= 4 * 1024 * 1024) { "OpenCode configuration is too large" }
            Json.parse(ProjectPermissionConfig.stripJsonComments(Files.readString(path))).asJsonObject
        } else Json.obj()
        val native = listOf("agents", "permissions", "providers", "commands", "plugins").any(document::has)
        patch.entrySet().forEach { (key, value) ->
            if (native && key == "small_model") {
                val agents = document.obj("agents") ?: Json.obj().also { document.add("agents", it) }
                val title = agents.obj("title") ?: Json.obj().also { agents.add("title", it) }
                if (value.asString.isEmpty()) title.remove("model") else title.add("model", value)
            } else if (key == "agent") {
                val targetKey = if (native) "agents" else "agent"
                val agents = document.obj(targetKey) ?: Json.obj().also { document.add(targetKey, it) }
                value.asJsonObject.entrySet().forEach { (name, fields) ->
                    val agent = agents.obj(name) ?: Json.obj().also { agents.add(name, it) }
                    fields.asJsonObject.entrySet().forEach { (field, setting) ->
                        if (field == "model" && setting.asString.isEmpty()) agent.remove(field) else agent.add(field, setting)
                    }
                }
            } else if (key == "small_model" && value.asString.isEmpty()) document.remove(key)
            else document.add(key, value)
        }
        JsonJournal(path).write(document)
    }
}
