package varro.server

import com.google.gson.JsonObject
import varro.protocol.*

internal object OpenCodeV2Events {
    fun project(event: JsonObject, context: JsonObject): List<JsonObject> {
        val type = event.str("type") ?: return emptyList()
        val data = event.obj("data") ?: return emptyList()
        val properties = data.deepCopy().apply { add("timestamp", event.get("created")) }
        val sessionID = data.str("sessionID").orEmpty()
        val base = Json.obj("id" to event.get("id"), "seq" to event.obj("durable")?.get("seq"),
            "workspaceDirectory" to ((event.obj("location") ?: data.obj("location")).str("directory") ?: context.str("directory")))
        fun emit(name: String, props: JsonObject = properties) = base.deepCopy().apply { addProperty("type", name); add("properties", props) }
        fun next(props: JsonObject = properties) = listOf(emit(type.replaceFirst("session.", "session.next."), props))
        when (type) {
            "server.connected" -> return listOf(emit(type))
            "session.created" -> return listOf(emit(type, Json.obj("info" to data.deepCopy().apply {
                addProperty("id", sessionID); addProperty("title", data.str("title").orEmpty()); add("directory", base.get("workspaceDirectory"))
                add("time", Json.obj("created" to event.get("created"), "updated" to event.get("created")))
            })))
            "session.renamed" -> return listOf(emit("session.updated", Json.obj("info" to Json.obj("id" to sessionID, "title" to data.get("title"), "time" to Json.obj("updated" to event.get("created"))))))
            "session.deleted" -> return listOf(emit(type, Json.obj("sessionID" to sessionID, "info" to Json.obj("id" to sessionID))))
            "session.status.updated" -> return listOf(emit("session.status"))
            "session.execution.started" -> return listOf(emit("session.status", Json.obj("sessionID" to sessionID, "status" to Json.obj("type" to "busy"))))
            "session.execution.succeeded", "session.execution.interrupted" -> return listOf(emit("session.status", Json.obj("sessionID" to sessionID, "status" to Json.obj("type" to "idle"))))
            "session.execution.failed" -> {
                val result = mutableListOf<JsonObject>()
                if (context.bool("hasAssistant") != true && event.str("id") != null) {
                    val message = OpenCodeV2Projection.message(Json.obj("id" to event.str("id")!!.replaceFirst("evt_", "msg_"), "type" to "idle", "outcome" to "failed",
                        "time" to Json.obj("created" to event.get("created"))), sessionID, context.str("directory").orEmpty(), context.str("parentID").orEmpty(), context.deepCopy().apply { add("error", data.get("error")) })
                    result.add(emit("message.updated", Json.obj("info" to message.get("info"))).apply { addProperty("id", "${event.str("id")}:message"); remove("seq") })
                }
                result.add(emit("session.error", Json.obj("sessionID" to sessionID, "error" to Json.obj("name" to "APIError", "data" to Json.obj(
                    "message" to (data.obj("error").str("message") ?: "OpenCode execution failed"), "statusCode" to data.obj("error")?.get("status"))))))
                result.add(emit("session.status", Json.obj("sessionID" to sessionID, "status" to Json.obj("type" to "idle"))).apply { addProperty("id", "${event.str("id")}:idle"); remove("seq") })
                return result
            }
            "permission.asked" -> return listOf(emit(type, OpenCodeV2Projection.permission(data)))
            "permission.replied" -> return listOf(emit(type))
            "form.created" -> return data.obj("form")?.let { listOf(emit("question.asked", OpenCodeV2Projection.form(it))) }.orEmpty()
            "form.replied", "form.cancelled" -> return listOf(emit(if (type == "form.replied") "question.replied" else "question.rejected", properties.apply { add("requestID", data.get("id")) }))
            "session.inbox.enqueued", "session.inbox.delivered" -> return listOf(emit(if (type.endsWith("enqueued")) "session.next.prompt.admitted" else "session.next.prompted", properties.apply { add("messageID", data.get("inboxID")) }))
            "session.skill.activated" -> return listOf(emit("session.next.synthetic"))
            "session.shell.started", "session.shell.ended" -> return next(properties.apply { add("callID", data.obj("shell")?.get("id")); add("output", data.obj("output")?.get("output")) })
            "session.agent.selected" -> return listOf(emit("session.next.agent.switched"))
            "session.model.selected" -> return listOf(emit("session.next.model.switched", properties.apply { add("model", data.obj("model")?.deepCopy()?.apply { add("modelID", get("id")) }) }))
            "session.retry.scheduled" -> return listOf(emit("session.status", Json.obj("sessionID" to sessionID, "status" to Json.obj("type" to "retry", "attempt" to data.get("attempt"), "next" to data.get("at"), "message" to (data.obj("error").str("message") ?: "Retrying")))))
            "session.step.streamed" -> return listOf(emit("session.next.context.updated", Json.obj("sessionID" to sessionID)))
            "session.compaction.failed" -> return listOf(emit("session.next.compaction.ended"), emit("session.error", Json.obj("sessionID" to sessionID,
                "error" to Json.obj("name" to "APIError", "data" to Json.obj("message" to (data.obj("error").str("message") ?: "Compaction failed"))))).apply { addProperty("id", "${event.str("id")}:error"); remove("seq") })
            "provider.updated", "model.updated", "agent.updated", "integration.updated", "credential.updated" -> return listOf(emit("catalog.updated"))
            "config.updated" -> return listOf(emit("global.disposed"))
        }
        if (Regex("^session\\.(text|reasoning)\\.").containsMatchIn(type)) {
            val id = data.str("assistantMessageID") ?: return emptyList()
            val ordinal = data.int("ordinal") ?: return emptyList()
            val kind = if (type.startsWith("session.text.")) "text" else "reasoning"
            return next(properties.apply { addProperty("textID", "$id:$kind:$ordinal"); addProperty("reasoningID", "$id:$kind:$ordinal") })
        }
        if (type.startsWith("session.tool.")) return next(properties.apply {
            add("callID", data.get("id")); add("structured", data.get("metadata")); add("provider", data.get("state")); add("result", data.get("resultState"))
            addProperty("name", OpenCodeV2Projection.legacyAction(data.str("name"))); addProperty("output", OpenCodeV2Projection.toolOutput(data.get("content")))
        })
        if (type.startsWith("session.step.")) return next(properties.apply {
            if (type == "session.step.started" && data.num("started") != null) add("timestamp", data.get("started"))
            add("model", data.obj("model")?.deepCopy()?.apply { add("modelID", get("id")) }); addProperty("executionContinues", true)
        })
        if (Regex("^session\\.(compaction|revert)\\.").containsMatchIn(type) || type in setOf("session.synthetic", "session.moved")) return next()
        return if (sessionID.isNotEmpty() && event.obj("durable").long("seq") != null)
            listOf(emit("session.next.context.updated", Json.obj("sessionID" to sessionID)).apply { addProperty("sequenceOnly", true) }) else emptyList()
    }
}
