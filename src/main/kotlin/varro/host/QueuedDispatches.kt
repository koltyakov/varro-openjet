package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.*
import java.nio.file.Path

/** Write-ahead admission journal. Ambiguous sends are never retried automatically. */
class QueuedDispatches(private val path: Path, initialMessages: JsonArray) {
    private val journal = varro.store.JsonJournal(path)
    private var state = journal.read(Json.obj("messages" to initialMessages, "dispatches" to JsonObject(), "nextLease" to 0))
    private val claims = mutableMapOf<String, JsonObject>()

    @Synchronized fun messages(): JsonArray = state.arr("messages")!!.deepCopy()
    @Synchronized fun hasPending(): Boolean = claims.isNotEmpty() || dispatches().entrySet().any { it.value.asJsonObject.str("status") == "admitting" }

    @Synchronized fun update(viewId: String, incoming: JsonArray): JsonArray {
        val current = messages()
        val next = JsonArray()
        current.filter { owner(it.asJsonObject) != viewId }.forEach(next::add)
        incoming.map { it.asJsonObject }.filter { owner(it) == viewId }.distinctBy { it.str("id") }.forEach { item ->
            val admission = dispatches().obj(item.str("id").orEmpty())
            if (admission.str("status") != "sent") next.add(item.deepCopy().apply {
                if (admission != null) addProperty("paused", true)
            })
        }
        // A renderer disappearing or optimistically dropping an item cannot erase an uncertain admission.
        current.map { it.asJsonObject }.filter { dispatches().obj(it.str("id").orEmpty()).str("status") == "admitting" }
            .forEach { item -> if (next.none { it.asJsonObject.str("id") == item.str("id") }) next.add(item.deepCopy().apply { addProperty("paused", true) }) }
        state.add("messages", next)
        save()
        return messages()
    }

    @Synchronized fun claim(viewId: String, sessionId: String, itemId: String, steer: Boolean): Long? {
        claims[sessionId]?.let { return if (it.str("viewId") == viewId && it.str("itemId") == itemId) it.long("lease") else null }
        if (dispatches().has(itemId) || dispatches().entrySet().any {
            it.value.asJsonObject.str("sessionId") == sessionId && it.value.asJsonObject.str("status") == "admitting"
        }) return null
        val item = messages().firstOrNull {
            val record = it.asJsonObject
            record.str("sessionId") == sessionId && if (steer) record.str("id") == itemId else record.bool("paused") != true
        }.asObjectOrNull() ?: return null
        if (item.str("id") != itemId || owner(item) != viewId) return null
        val lease = (state.long("nextLease") ?: 0) + 1
        state.addProperty("nextLease", lease)
        save()
        claims[sessionId] = Json.obj("viewId" to viewId, "itemId" to itemId, "lease" to lease)
        return lease
    }

    @Synchronized fun admit(viewId: String, request: JsonObject): Boolean {
        val dispatch = request.obj("queuedMessageDispatch") ?: return false
        val sessionId = Regex("^/session/([A-Za-z0-9_-]+)/(prompt_async|message)$")
            .matchEntire(request.str("path").orEmpty().substringBefore('?'))?.groupValues?.get(1) ?: return false
        val claim = claims[sessionId] ?: return false
        val itemId = dispatch.str("itemId") ?: return false
        if (claim.str("viewId") != viewId ||
            claim.str("itemId") != itemId || claim.long("lease") != dispatch.long("lease") || dispatches().has(itemId)) return false
        val item = messages().firstOrNull { it.asObjectOrNull().str("id") == itemId }.asObjectOrNull() ?: return false
        if (owner(item) != viewId || item.str("sessionId") != sessionId) return false
        val body = request.obj("body") ?: return false
        if (item.text("messageId") != null && item.str("messageId") != body.str("messageID")) return false
        if (request.str("method")?.uppercase() == "GET") return request.str("path")?.substringBefore('?') == "/session/$sessionId/message"
        if (request.str("method")?.uppercase() != "POST") return false
        val messageId = body.text("messageID") ?: "msg_${java.util.UUID.randomUUID().toString().replace("-", "")}".also {
            body.addProperty("messageID", it)
        }
        dispatches().add(itemId, Json.obj("sessionId" to sessionId, "messageId" to messageId,
            "status" to "admitting", "lease" to claim.long("lease"), "viewId" to viewId))
        state.add("messages", Json.array(messages().map { it.asJsonObject.apply {
            if (str("id") == itemId) { addProperty("messageId", messageId); addProperty("paused", true) }
        } }))
        save() // Must reach disk before the POST can start.
        return true
    }

    @Synchronized fun complete(request: JsonObject, success: Boolean, rejected: Boolean = false) {
        val itemId = request.obj("queuedMessageDispatch").str("itemId") ?: return
        val admission = dispatches().obj(itemId) ?: return
        if (admission.long("lease") != request.obj("queuedMessageDispatch").long("lease")) return
        if (success) markSent(itemId)
        else if (rejected) dispatches().remove(itemId)
        claims.remove(admission.str("sessionId"))
        save()
    }

    @Synchronized fun release(viewId: String, sessionId: String, itemId: String, lease: Long?) {
        val claim = claims[sessionId] ?: return
        if (claim.str("viewId") == viewId && claim.str("itemId") == itemId && claim.long("lease") == lease && !dispatches().has(itemId)) claims.remove(sessionId)
    }

    @Synchronized fun detach(viewId: String) { claims.entries.removeIf { it.value.str("viewId") == viewId } }

    fun recover(readMessages: (String) -> JsonArray) {
        val pending = synchronized(this) { dispatches().entrySet().filter { it.value.asJsonObject.str("status") == "admitting" }
            .map { it.key to it.value.asJsonObject.deepCopy() } }
        pending.forEach { (itemId, admission) ->
            val found = runCatching { readMessages(admission.str("sessionId")!!).any {
                it.asObjectOrNull().obj("info").str("id") == admission.str("messageId")
            } }.getOrDefault(false)
            if (found) synchronized(this) { markSent(itemId); save() }
        }
    }

    private fun markSent(itemId: String) {
        dispatches().obj(itemId)?.addProperty("status", "sent")
        state.add("messages", Json.array(messages().filter { it.asObjectOrNull().str("id") != itemId }))
    }
    private fun dispatches() = state.obj("dispatches")!!
    private fun owner(item: JsonObject) = item.str("ownerViewId") ?: "sidebar"
    private fun save() = journal.write(state)
}
