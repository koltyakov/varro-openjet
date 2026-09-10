package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.VarroStore

/** Rules are applied to OpenCode before the host acknowledges a save. */
class PermissionService(
    private val store: VarroStore,
    private val projectRules: ((String?) -> JsonArray)? = null,
    private val saveProjectRules: ((JsonArray, String?) -> Unit)? = null,
    private val request: (String, String, JsonElement?, String?) -> JsonElement?,
) {
    @Synchronized
    fun sessionRules(sessionId: String, rules: JsonElement?, directory: String?): JsonArray {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session id" }
        if (rules != null) {
            val validated = validateRules(rules)
            request("PATCH", "/session/$sessionId", Json.obj("permission" to validated), directory)
            val saved = store.permissionRules
            saved.add(sessionId, validated)
            store.permissionRules = saved
            return validated
        }
        return request("GET", "/session/$sessionId", null, directory).asObjectOrNull().arr("permission")
            ?: store.permissionRules.arr(sessionId) ?: JsonArray()
    }

    @Synchronized
    fun allow(body: JsonObject, project: Boolean, directory: String?): JsonArray {
        val sessionId = body.text("sessionId") ?: error("Missing session id")
        val permissionId = body.text("permissionId") ?: error("Missing permission id")
        val pending = request("GET", "/permission", null, directory).asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull() }?.firstOrNull {
                it.str("id") == permissionId && it.str("sessionID") == sessionId
            } ?: error("Permission request is no longer pending")
        val name = pending.text("permission") ?: pending.text("type") ?: error("Missing permission name")
        val patterns = pending.arr("always")?.takeIf { it.size() > 0 }
            ?: pending.arr("patterns") ?: pending.arr("pattern")
            ?: pending.text("pattern")?.let { Json.array(listOf(it)) }
            ?: error("Permission request has no approval patterns")
        require(patterns.size() > 0) { "Permission request has no approval patterns" }
        val additions = validateRules(Json.array(patterns.map { pattern ->
            Json.obj("permission" to name, "pattern" to pattern, "action" to "allow")
        }))
        if (project) {
            val rules = projectRules?.invoke(directory) ?: error("Project rule storage is unavailable")
            additions.forEach(rules::add)
            (saveProjectRules ?: error("Project rule storage is unavailable"))(rules, directory)
        }
        val rules = sessionRules(sessionId, null, directory)
        additions.forEach(rules::add)
        val saved = sessionRules(sessionId, rules, directory)
        request("POST", "/permission/${java.net.URLEncoder.encode(permissionId, Charsets.UTF_8)}/reply",
            Json.obj("reply" to "once"), directory)
        return saved
    }

    companion object {
        fun validateRules(value: JsonElement?): JsonArray {
            val rules = value.asArrayOrNull() ?: error("Rules must be an array")
            require(rules.size() <= 4096) { "Too many permission rules" }
            return Json.array(rules.map {
                val rule = it.asObjectOrNull() ?: error("Invalid permission rule")
                val permission = rule.text("permission") ?: error("Missing permission name")
                val pattern = rule.text("pattern") ?: error("Missing permission pattern")
                val action = rule.str("action")
                require(action in setOf("allow", "ask", "deny")) { "Invalid permission action" }
                Json.obj("permission" to permission, "pattern" to pattern, "action" to action)
            })
        }

        fun fromConfig(value: JsonElement?): JsonArray {
            if (value == null || value.isJsonNull) return JsonArray()
            if (value.isJsonPrimitive) return validateRules(Json.array(listOf(
                Json.obj("permission" to "*", "pattern" to "*", "action" to value))))
            val rules = JsonArray()
            value.asJsonObject.entrySet().forEach { (name, actions) ->
                if (actions.isJsonPrimitive) rules.add(Json.obj("permission" to name, "pattern" to "*", "action" to actions))
                else actions.asJsonObject.entrySet().forEach { (pattern, action) ->
                    rules.add(Json.obj("permission" to name, "pattern" to pattern, "action" to action))
                }
            }
            return validateRules(rules)
        }

        fun toConfig(rules: JsonArray): JsonObject = JsonObject().apply {
            var previousName: String? = null
            validateRules(rules).forEach {
                val rule = it.asJsonObject
                val name = rule.str("permission")!!
                require(name == previousName || !has(name)) { "Rules for $name must be grouped together to preserve their order" }
                previousName = name
                if (name in setOf("todowrite", "question", "webfetch", "websearch", "doom_loop")) {
                    require(rule.str("pattern") == "*") { "$name only supports a catch-all configuration rule" }
                    addProperty(name, rule.str("action"))
                    return@forEach
                }
                val patterns = obj(name) ?: JsonObject().also { add(name, it) }
                patterns.remove(rule.str("pattern")!!)
                patterns.addProperty(rule.str("pattern")!!, rule.str("action"))
            }
        }
    }
}
