package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.VarroStore

/** Saves standing rules; the webview owns the subsequent pending-request reply. */
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
            ?.mapNotNull { it.asObjectOrNull()?.let { record -> record.obj("info") ?: record } }?.firstOrNull {
                (it.text("id") ?: it.text("permissionID") ?: it.text("requestID")) == permissionId && it.str("sessionID") == sessionId
            } ?: error("Permission request is no longer pending")
        val name = pending.text("permission") ?: pending.text("type") ?: error("Missing permission name")
        val patterns = pending.arr("always")?.mapNotNull {
            it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
                ?.asString?.trim()?.takeIf(String::isNotEmpty)
        }?.distinct().orEmpty()
        require(patterns.isNotEmpty()) { "Standing approval scope is unavailable for this request" }
        val additions = validateRules(Json.array(patterns.map { pattern ->
            Json.obj("permission" to name, "pattern" to pattern, "action" to "allow")
        }))
        if (project) {
            val rules = projectRules?.invoke(directory) ?: error("Project rule storage is unavailable")
            val effective = request("GET", "/config", null, directory).asObjectOrNull()
                ?: error("Could not read effective OpenCode permissions")
            val merged = mergeProjectAllow(rules, additions, effective.get("permission"))
            (saveProjectRules ?: error("Project rule storage is unavailable"))(merged, directory)
            return merged
        }
        val rules = sessionRules(sessionId, null, directory)
        additions.forEach(rules::add)
        return sessionRules(sessionId, rules, directory)
    }

    companion object {
        /** Merge within each permission's config entry instead of appending a second group. */
        private fun mergeProjectAllow(rules: JsonArray, additions: JsonArray, effective: JsonElement?): JsonArray {
            val config = toConfig(rules)
            // Turning an inherited scalar into a project object must preserve its default.
            if (!config.has("*") && effective?.isJsonPrimitive == true) config.add("*", effective)
            val added = toConfig(additions)
            added.entrySet().forEach { (name, value) ->
                if (value.isJsonPrimitive) config.add(name, value)
                else {
                    val current = config.get(name) ?: effective.asObjectOrNull()?.get(name)?.takeIf { it.isJsonPrimitive }
                    val patterns = current.asObjectOrNull() ?: JsonObject().apply {
                        if (current?.isJsonPrimitive == true) add("*", current)
                    }
                    value.asJsonObject.entrySet().forEach { (pattern, action) ->
                        patterns.remove(pattern)
                        patterns.add(pattern, action)
                    }
                    config.add(name, patterns)
                }
            }
            return fromConfig(config)
        }

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
