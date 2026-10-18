package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Native identities and acknowledgements, never inferred from local busy state. */
internal class OpenCodeV2ChildTasks {
    private val generation = AtomicLong()
    private val progress = ConcurrentHashMap<String, String>()

    fun reset() { generation.incrementAndGet(); progress.clear() }
    fun observe(type: String, data: JsonObject) {
        val key = listOf("sessionID", "assistantMessageID", "id").map { data.str(it) ?: return }.joinToString(":")
        if (type == "session.tool.progress" && data.obj("metadata").str("status") == "running")
            data.obj("metadata").str("sessionID")?.let { progress[key] = it }
        if (type in setOf("session.tool.success", "session.tool.failed", "session.tool.called")) progress.remove(key)
        while (progress.size > 4096) progress.keys.firstOrNull()?.let(progress::remove)
    }

    fun request(id: String, action: String, directory: String?, options: RequestOptions,
                raw: (String, String, JsonElement?) -> JsonElement?): JsonObject {
        val start = generation.get()
        fun checkConnection() {
            options.checkCancelled()
            check(start == generation.get()) { "Connection changed while managing child work. Refresh before retrying." }
        }
        fun data(path: String) = raw("GET", path, null).asObjectOrNull()?.get("data") ?: error("Invalid child task response")
        val endpoint = "/api/session/${OpenCodeV2Adapter.encode(id)}"
        val child = data(endpoint).asObjectOrNull()
        val location = child.obj("location").str("directory") ?: error("Invalid child task directory")
        require(child.str("id") == id && (directory == null || WorkspacePaths.isSame(location, directory))) {
            "Conversation moved. Refresh before managing this child."
        }
        val parentID = child.str("parentID")
        val parentEndpoint = parentID?.let { "/api/session/${OpenCodeV2Adapter.encode(it)}" }
        if (parentEndpoint != null) {
            val parent = data(parentEndpoint).asObjectOrNull()
            require(parent.str("id") == parentID && WorkspacePaths.isSame(parent.obj("location").str("directory"), location)) {
                "Child task controls require parent and child in the same directory"
            }
        }
        val paths = raw("GET", "/openapi.json", null).asObjectOrNull().obj("paths")
        val interrupt = paths.obj("/api/session/{sessionID}/interrupt").obj("post")
        val background = paths.obj("/api/session/{sessionID}/background").obj("post")
        val resume = interrupt.arr("parameters").orEmpty().mapNotNull { it.asObjectOrNull() }.firstOrNull { it.str("name") == "resume" }
        val schema = resume.obj("schema")
        val variants = schema.arr("anyOf")?.toList() ?: listOfNotNull(schema)
        val stop = parentID != null && interrupt.str("operationId") == "session.interrupt" && resume.str("in") == "query" &&
            variants.any { it.asObjectOrNull().arr("enum")?.strings()?.containsAll(listOf("false", "true")) == true }
        checkConnection()
        if (action == "interrupt-child") {
            check(stop) { "Child-only interruption is unavailable on this server" }
            val ack = raw("POST", "$endpoint/interrupt?resume=false", Json.obj()).asObjectOrNull()
            checkConnection()
            check(ack.bool("interrupted") != null) { "Invalid native child interruption acknowledgement" }
            return Json.obj("interrupted" to ack!!.get("interrupted"))
        }
        var messages = emptyList<JsonObject>()
        if (parentEndpoint != null && background.str("operationId") == "session.background")
            messages = OpenCodeV2Adapter.objects(data("$parentEndpoint/message?limit=50&order=desc"))
        val latest = messages.firstOrNull { it.str("type") == "assistant" }
        val eligible = latest.arr("content").orEmpty().any { entry ->
            val part = entry.asObjectOrNull()
            val state = part.obj("state")
            part.str("type") == "tool" && part.str("name") == "subagent" && state.str("status") == "running" &&
                state.obj("input").bool("background") != true &&
                ((state.obj("metadata").str("sessionID") == id && state.obj("metadata").str("status") == "running") ||
                    progress["$parentID:${latest.str("id")}:${part.str("id")}"] == id)
        }
        checkConnection()
        if (action == "child-task-controls") return Json.obj("parentID" to parentID, "stopChild" to stop,
            "backgroundEligible" to eligible, "directory" to location)
        check(eligible && parentEndpoint != null) { "No verified foreground child work is eligible for background handoff" }
        val before = messages.mapNotNull { it.str("id") }.toMutableSet()
        before += OpenCodeV2Adapter.objects(data("$parentEndpoint/inbox")).mapNotNull { it.str("id") }
        checkConnection()
        raw("POST", "$parentEndpoint/background", Json.obj())
        checkConnection()
        val pending = OpenCodeV2Adapter.objects(data("$parentEndpoint/inbox"))
        val after = OpenCodeV2Adapter.objects(data("$parentEndpoint/message?limit=50&order=desc"))
        checkConnection()
        fun guidance(item: JsonObject, payload: JsonObject?) = item.str("id") !in before && item.str("type") == "synthetic" &&
            payload.str("text")?.startsWith("User requested that active blocking work be moved to the background.\n") == true
        return Json.obj("backgrounded" to (pending.any { guidance(it, it.obj("payload")) } || after.any { guidance(it, it) }))
    }
}
