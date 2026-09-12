package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.VarroStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** OpenCode metadata is authoritative; IDE storage remains the fallback for older sessions. */
class SessionSelections(
    private val store: VarroStore,
    private val publishAgent: (String, String) -> Unit = { _, _ -> },
    private val request: (String, String, JsonElement?, String?) -> JsonElement?,
) {
    private class SessionLock {
        val lock = ReentrantLock(true)
        var users = 0
    }
    // Share locks across views and selection types; release entries after the last waiter.
    private val locks = ConcurrentHashMap<String, SessionLock>()
    private fun retain(id: String): SessionLock = locks.compute(id) { _, current ->
        (current ?: SessionLock()).apply { users++ }
    }!!
    private fun release(id: String) {
        locks.computeIfPresent(id) { _, entry -> if (--entry.users == 0) null else entry }
    }
    private fun <T> write(id: String, action: () -> T): T {
        val entry = retain(id)
        try { return entry.lock.withLock(action) } finally { release(id) }
    }

    fun observe(session: JsonObject) {
        val id = session.text("id") ?: return
        val entry = retain(id)
        try {
            // A response/event during a local write must not publish an unconfirmed selection.
            if (entry.lock.isHeldByCurrentThread || !entry.lock.tryLock()) return
            try { restore(id, session.obj("metadata")) } finally { entry.lock.unlock() }
        } finally { release(id) }
    }

    private fun restore(id: String, metadata: JsonObject?) {
        val varro = metadata.obj("varro")
        model(varro?.get("model"), "provider", "model")?.let {
            if (store.sessionSelectedModels.get(id) != it) store.updateSessionModel(id, it)
        }
        varro.text("agent")?.let { agent ->
            if (store.sessionPlanAgents.str(id) != agent) {
                store.updateSessionAgent(id, agent)
                publishAgent(id, agent)
            }
        }
        varro.str("permissionMode")?.takeIf { it in MODES }?.let {
            if (store.sessionPermissionModes.str(id) != it) store.updateSessionPermissionMode(id, Json.toElement(it))
        }
    }

    fun updateModel(id: String, value: JsonElement?, directory: String?) = write(id) {
        if (value == null || value.isJsonNull) store.updateSessionModel(id, null)
        else {
            val selection = model(value) ?: error("Invalid session model")
            val stored = Json.obj("provider" to selection.str("providerID"), "model" to selection.str("modelID")).apply {
                selection.str("variant")?.let { addProperty("variant", it) }
            }
            update(id, Json.obj("model" to stored), directory)
        }
    }

    fun updateAgent(id: String, agent: String, directory: String?) = write(id) {
        require(agent.isNotBlank()) { "Invalid session agent" }
        update(id, Json.obj("agent" to agent), directory)
    }

    fun updateMode(id: String, body: JsonObject, directory: String?): JsonObject = write(id) {
        val mode = body.str("mode")
        require(mode in MODES) { "Invalid permission mode" }
        val rules = if (body.bool("preconfigured") == true) null
            else if (mode == "default" && body.has("defaultPermission")) PermissionService.validateRules(body.get("defaultPermission"))
            else rules(mode!!)
        update(id, Json.obj("permissionMode" to mode), directory, rules)
    }

    private fun update(id: String, selection: JsonObject, directory: String?, rules: JsonArray? = null): JsonObject {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session id" }
        val path = "/session/$id"
        val session = request("GET", path, null, directory).asObjectOrNull()
        require(session.str("id") == id) { "Cannot verify session selection metadata" }
        val metadata = session.obj("metadata")?.deepCopy() ?: JsonObject()
        val varro = metadata.obj("varro") ?: JsonObject()
        val changed = selection.entrySet().any { (key, value) -> varro.get(key) != value }
        varro.addProperty("schemaVersion", 1)
        selection.entrySet().forEach { (key, value) -> varro.add(key, value) }
        metadata.add("varro", varro)
        val confirmed = if (changed || rules != null) {
            val body = Json.obj("metadata" to metadata)
            rules?.let { body.add("permission", it) }
            request("PATCH", path, body, directory).asObjectOrNull()
                ?: error("Could not save session selection metadata")
        } else session!!
        restore(id, metadata)
        return confirmed
    }

    companion object {
        private val MODES = setOf("default", "auto", "full")
        private val KNOWN = listOf("read", "edit", "glob", "grep", "list", "bash", "shell", "task",
            "external_directory", "todowrite", "question", "webfetch", "websearch", "codesearch", "lsp", "doom_loop", "skill")
        private val DIRECT = setOf("read", "glob", "grep", "list", "codesearch", "lsp", "task", "todowrite", "question")

        private fun rules(mode: String): JsonArray {
            fun rule(name: String, action: String) = Json.obj("permission" to name, "pattern" to "*", "action" to action)
            return Json.array(when (mode) {
                "full" -> KNOWN.map { rule(it, "allow") } + rule("*", "allow")
                "auto" -> listOf(rule("*", "ask")) + KNOWN.map { rule(it, if (it in DIRECT) "allow" else "ask") }
                else -> listOf(rule("*", "ask"), rule("todowrite", "allow"), rule("question", "allow"))
            })
        }

        private fun model(value: JsonElement?, providerKey: String = "providerID", modelKey: String = "modelID"): JsonObject? {
            val record = value.asObjectOrNull() ?: return null
            val provider = record.text(providerKey) ?: return null
            val model = record.text(modelKey) ?: return null
            val variant = record.get("variant")
            if (variant != null && (variant.isJsonPrimitive.not() || !variant.asJsonPrimitive.isString)) return null
            return Json.obj("providerID" to provider, "modelID" to model).apply {
                record.str("variant")?.takeIf { it.isNotEmpty() }?.let { addProperty("variant", it) }
            }
        }
    }
}
