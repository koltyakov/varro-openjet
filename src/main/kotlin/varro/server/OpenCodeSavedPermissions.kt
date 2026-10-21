package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.net.URLDecoder
import java.net.URLEncoder

/** Only allowances observed through this host are user-managed, never arbitrary server rules. */
internal class OpenCodeSavedPermissions(
    private val apiVersion: () -> Int,
    initial: JsonElement? = null,
    persist: (com.google.gson.JsonArray) -> Unit = {},
    private val serverUrl: () -> String = { "" },
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
) {
    private val observed = ServerMemoryPermissionStore(initial, persist)
    private fun server() = "${apiVersion()}:${serverUrl()}"
    fun reset() = observed.clearLegacy()
    private fun scope(rule: JsonObject) = "${rule.str("permission")}\u0000${rule.str("pattern")}"
    fun remember(rules: List<JsonObject>) = observed.remember(server(), rules)

    @Synchronized fun approved(rules: List<JsonObject>, options: RequestOptions) {
        remember(rules)
        // Capture native IDs before returning the approval, even if settings never opens.
        if (apiVersion() == 2) rules.mapNotNull { it.str("projectID") }.distinct().forEach { list(it, options) }
    }

    fun prepare(method: String, path: String, body: JsonElement?, options: RequestOptions): List<JsonObject> {
        if (method != "POST" || body.asObjectOrNull().str("reply") != "always") return emptyList()
        val match = Regex("^/permission/([^/]+)/reply$").matchEntire(path.substringBefore('?')) ?: return emptyList()
        return try {
            val id = URLDecoder.decode(match.groupValues[1], Charsets.UTF_8)
            val pending = request("GET", "/permission", null, options).data.asArrayOrNull().orEmpty()
                .mapNotNull { it.asObjectOrNull()?.let { record -> record.obj("info") ?: record } }
                .firstOrNull { (it.str("id") ?: it.str("permissionID") ?: it.str("requestID")) == id } ?: return emptyList()
            val sessionID = pending.text("sessionID") ?: return emptyList()
            val permission = (pending.str("permission") ?: pending.str("type"))?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val projectID = request("GET", "/session/${encode(sessionID)}", null, options).data.asObjectOrNull().text("projectID") ?: return emptyList()
            pending.arr("always").orEmpty().mapNotNull {
                it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf(String::isNotEmpty)
            }.distinct().mapIndexed { index, pattern -> Json.obj("id" to "legacy:$id:$index", "projectID" to projectID,
                "permission" to permission, "pattern" to pattern, "retractable" to false) }
        } catch (failure: Exception) {
            com.intellij.openapi.diagnostic.logger<OpenCodeSavedPermissions>().warn("Could not mirror server-memory permission", failure)
            emptyList()
        }
    }

    @Synchronized fun handle(method: String, sessionID: String?, body: JsonElement?, options: RequestOptions): JsonObject {
        val input = body.asObjectOrNull()
        val id = if (method != "GET") input.text("id")?.trim() ?: error("Saved permission ID is required") else null
        val permission = input.str("permission")
        val pattern = input.str("pattern")
        if (method == "PATCH") require(!permission.isNullOrBlank() && permission.length <= 200 &&
            !pattern.isNullOrBlank() && pattern.length <= 2000) { "Every saved rule needs a permission name and pattern" }
        val project = request("GET", sessionID?.let { "/session/${encode(it)}" } ?: "/project/current", null, options).data.asObjectOrNull()
        val projectID = project.text(if (sessionID == null) "id" else "projectID") ?: error("Project is unavailable")
        val current = list(projectID, options)
        if (id == null) return current
        check(current.bool("supported") == true) { "Saved permissions are unavailable on this OpenCode server" }
        require(!id.startsWith("legacy:")) { "Restart OpenCode to clear this server-memory allowance" }
        val rule = current.arr("rules").orEmpty().mapNotNull { it.asObjectOrNull() }
            .firstOrNull { it.str("id") == id && it.str("projectID") == projectID } ?: error("Saved permission not found for this project")
        val path = "/api/permission/saved/${encode(id)}"
        if (method == "PATCH") {
            check(rule.bool("editable") == true) { "Saved-permission editing is unavailable on this OpenCode server" }
            val action = OpenCodeV2Projection.nativeAction(permission!!)
            val updated = request("PATCH", path, Json.obj("action" to action, "resource" to pattern), options).data.asObjectOrNull().obj("data")
            check(updated.str("id") == id && updated.str("projectID") == projectID && updated.str("action") == action && updated.str("resource") == pattern) {
                "Could not verify the saved-permission update. Refresh before retrying."
            }
            remember(listOf(Json.obj("id" to id, "projectID" to projectID, "permission" to OpenCodeV2Projection.legacyAction(action), "pattern" to pattern)))
        } else {
            require(method == "DELETE") { "Unsupported saved-permission mutation" }
            request("DELETE", path, null, options)
            observed.forget(server(), rule)
        }
        return list(projectID, options)
    }

    private fun list(projectID: String, options: RequestOptions): JsonObject {
        val legacy = observed.get(server(), projectID)
        return try {
            val response = request("GET", "/api/permission/saved?projectID=${encode(projectID)}", null, options).data
            val values = response.asArrayOrNull() ?: response.asObjectOrNull().arr("data")
                ?: error("The OpenCode server returned an unsupported saved-permission response.")
            val editable = apiVersion() == 2 && try {
                OpenCodeApiCapabilities.read(request("GET", "/openapi.json", null, options).data).savedPermissionEditing
            } catch (failure: Exception) {
                com.intellij.openapi.diagnostic.logger<OpenCodeSavedPermissions>().warn("Could not discover saved-permission editing", failure)
                false
            }
            val saved = values.mapNotNull { value -> value.asObjectOrNull()?.let { rule ->
                if (rule.str("id") == null || rule.str("projectID") != projectID || rule.str("action") == null || rule.str("resource") == null) null
                else Json.obj("id" to rule.get("id"), "projectID" to projectID,
                    "permission" to if (apiVersion() == 2) OpenCodeV2Projection.legacyAction(rule.str("action")) else rule.str("action"),
                    "pattern" to rule.get("resource")).apply { if (editable) addProperty("editable", true) }
            } }.filter { rule -> legacy.any { it.str("id") == rule.str("id") ||
                (it.str("id").orEmpty().startsWith("legacy:") && scope(it) == scope(rule)) } }
            val scopes = saved.map(::scope).toSet()
            if (apiVersion() == 2) {
                val ids = saved.map { it.str("id") }.toSet()
                legacy.filter { if (it.str("id").orEmpty().startsWith("legacy:")) scope(it) in scopes else it.str("id") !in ids }
                    .forEach { observed.forget(server(), it) }
                remember(saved)
            }
            Json.obj("supported" to true, "rules" to saved + legacy.filter {
                (apiVersion() != 2 || it.str("id").orEmpty().startsWith("legacy:")) && scope(it) !in scopes
            })
        } catch (failure: Exception) {
            if (legacy.isNotEmpty()) Json.obj("supported" to true, "rules" to legacy.map { it.apply { remove("editable") } })
            else Json.obj("supported" to false, "rules" to emptyList<Any>(), "reason" to failure.message)
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
