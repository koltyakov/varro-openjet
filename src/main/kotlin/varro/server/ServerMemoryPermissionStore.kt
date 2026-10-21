package varro.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import varro.protocol.*

/** Project-local management links, not permission rules or authority to approve pending requests. */
internal class ServerMemoryPermissionStore(
    initial: JsonElement?,
    private val persist: (JsonArray) -> Unit,
) {
    private val entries = linkedMapOf<String, JsonObject>()

    init {
        initial.asArrayOrNull().orEmpty().forEach { value ->
            val entry = value.asObjectOrNull() ?: return@forEach
            val server = entry.text("server") ?: return@forEach
            val rule = entry.obj("rule") ?: return@forEach
            val id = rule.text("id")?.takeUnless { it.startsWith("legacy:") } ?: return@forEach
            val project = rule.text("projectID") ?: return@forEach
            val permission = rule.str("permission") ?: return@forEach
            val pattern = rule.str("pattern") ?: return@forEach
            val restored = Json.obj("id" to id, "projectID" to project, "permission" to permission, "pattern" to pattern)
            entries[key(server, restored)] = Json.obj("server" to server, "rule" to restored)
        }
    }

    @Synchronized fun get(server: String, projectID: String): List<JsonObject> = entries.values
        .filter { it.str("server") == server && it.obj("rule").str("projectID") == projectID }
        .map { it.obj("rule")!!.deepCopy() }

    @Synchronized fun remember(server: String, rules: List<JsonObject>) {
        if (server.isBlank() || rules.isEmpty()) return
        rules.forEach { entries[key(server, it)] = Json.obj("server" to server, "rule" to it.deepCopy()) }
        if (rules.any { !legacy(it) }) save()
    }

    @Synchronized fun forget(server: String, rule: JsonObject) {
        if (entries.remove(key(server, rule)) != null && !legacy(rule)) save()
    }

    @Synchronized fun clearLegacy() {
        entries.entries.removeIf { legacy(it.value.obj("rule")!!) }
    }

    private fun key(server: String, rule: JsonObject): String = Json.array(listOf(server, rule.str("projectID"),
        if (legacy(rule)) Json.array(listOf(rule.str("permission"), rule.str("pattern"))) else rule.get("id"))).toString()

    private fun legacy(rule: JsonObject) = rule.str("id").orEmpty().startsWith("legacy:")

    private fun save() {
        val snapshot = entries.values.filter { !legacy(it.obj("rule")!!) }.map { entry ->
            val rule = entry.obj("rule")!!
            Json.obj("server" to entry.str("server"), "rule" to Json.obj("id" to rule.str("id"),
                "projectID" to rule.str("projectID"), "permission" to rule.str("permission"), "pattern" to rule.str("pattern")))
        }
        try { persist(Json.array(snapshot)) }
        catch (failure: Exception) {
            // A storage failure cannot turn an acknowledged approval into a failed reply.
            logger<ServerMemoryPermissionStore>().warn("Could not persist saved-permission observations", failure)
        }
    }
}
