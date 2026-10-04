package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.ParsedServerEvent

/** Notification-only state. Never changes permissions, sessions or server state. Confined to the EDT. */
internal class AttentionNotifications(
    private val settings: () -> Settings,
    private val isFocused: () -> Boolean,
    private val inScope: (String) -> Boolean?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val label: String) {
        PERMISSION("Permission approval needed"), QUESTION("Your answer is needed"),
        COMPLETED("Response ready"), PLAN_READY("Plan ready for review"),
    }
    data class Settings(val native: Boolean = false, val sounds: Set<Kind> = emptySet()) {
        fun enabled(kind: Kind) = native || kind in sounds
    }
    data class Delivery(val sessionId: String, val title: String, val message: String, val native: Boolean, val sound: Boolean)
    private data class Session(var title: String = "Untitled chat", var parent: String? = null, var agent: String? = null,
        var subagent: Boolean = false, var ancestryKnown: Boolean = false)
    private data class Request(val sessionId: String, val kind: Kind, var revealAt: Long, var observed: Boolean = false)
    private data class Notice(val sessionId: String, val kind: Kind)
    private data class Run(var serverBusy: Boolean = false, var failed: Boolean = false, var startedAt: Long? = null)

    private val sessions = linkedMapOf<String, Session>()
    private val runs = mutableMapOf<String, Run>()
    private val pending = linkedMapOf<String, Request>()
    private val queued = linkedMapOf<String, Notice>()
    private val seenEvents = linkedSetOf<String>()
    private val seenUserMessages = linkedSetOf<String>()
    private val finished = linkedSetOf<String>()
    private val awaitingMetadata = linkedSetOf<String>()
    private val resolved = linkedSetOf<String>()
    private val mutations = linkedMapOf<String, Long>()
    private var revision = 0L
    private var mutationFloor = 0L
    private var batchAt: Long? = null
    private var nextDeliveryAt = 0L

    fun knowsSession(id: String) = sessions[id]?.ancestryKnown == true

    fun observeSession(info: JsonObject, full: Boolean = true) {
        val id = info.text("id") ?: return
        val session = sessions.getOrPut(id) { Session() }
        session.ancestryKnown = session.ancestryKnown || full
        info.text("title")?.let { session.title = clean(it) }
        if (info.has("parentID")) session.parent = info.text("parentID")
        info.obj("metadata").obj("varro").text("agent")?.let { session.agent = it }
        if (session.ancestryKnown && awaitingMetadata.remove(id)) complete(id)
        while (sessions.size > 2048) {
            val oldest = sessions.keys.firstOrNull { it !in runs && pending.values.none { request -> request.sessionId == it } } ?: break
            sessions.remove(oldest)
        }
        update()
    }

    fun handle(event: ParsedServerEvent) {
        if (event.sequenceOnly) return
        event.id?.let { if (!remember(seenEvents, it)) return }
        val props = event.properties
        val info = props.obj("info") ?: props
        val sessionId = props.text("sessionID") ?: info.text("sessionID")
        when (event.type) {
            "session.created", "session.updated" -> info?.let {
                observeSession(it, full = event.type == "session.created" || knowsSession(it.text("id").orEmpty()))
            }
            "session.deleted" -> (sessionId ?: info.text("id"))?.let(::removeSession)
            "permission.asked", "permission.updated", "permission.v2.asked" -> ask(info, Kind.PERMISSION)
            "question.asked", "question.v2.asked" -> ask(info, Kind.QUESTION)
            "permission.replied", "permission.v2.replied" -> requestId(info)?.let { resolve(Kind.PERMISSION, it) }
            "question.replied", "question.rejected", "question.v2.replied", "question.v2.rejected" ->
                requestId(info)?.let { resolve(Kind.QUESTION, it) }
            "session.status" -> if (sessionId != null) when (props.obj("status").str("type")) {
                "busy", "retry" -> {
                    // V1 can repeat a trailing busy/idle pair after terminal message events.
                    if (sessionId in finished && props.obj("status").bool("background") != true) return
                    queued.remove("completion:$sessionId")
                    awaitingMetadata.remove(sessionId)
                    runs.getOrPut(sessionId) { Run() }.apply { serverBusy = true; failed = false }
                }
                "idle" -> if (props.bool("interrupted") == true) aborted(sessionId) else finish(sessionId)
            }
            "session.idle" -> sessionId?.let(::finish)
            "session.error", "session.next.step.failed" -> sessionId?.let(::cancelCompletion)
            "session.next.prompt.admitted", "session.next.prompted", "session.next.step.started" -> if (sessionId != null) {
                finished.remove(sessionId)
                awaitingMetadata.remove(sessionId)
                queued.remove("completion:$sessionId")
                runs.getOrPut(sessionId) { Run() }.apply {
                    failed = false
                    props.long("timestamp")?.let { startedAt = maxOf(startedAt ?: it, it) }
                }
            }
            "session.next.agent.switched" -> if (sessionId != null) {
                sessions.getOrPut(sessionId) { Session() }.agent = props.text("agent")
            }
            "message.updated" -> if (sessionId != null) {
                info.text("agent")?.let { sessions.getOrPut(sessionId) { Session() }.agent = it }
                if (info.str("mode") == "subagent") sessions.getOrPut(sessionId) { Session() }.subagent = true
                if (info.str("role") == "assistant") {
                    val completedAt = info.obj("time").long("completed")
                    val startedAt = runs[sessionId]?.startedAt
                    if (completedAt != null && startedAt != null && completedAt < startedAt) return
                    if (info.obj("error") != null) cancelCompletion(sessionId)
                    else if (completedAt != null && info.str("finish") !in CONTINUATIONS) terminal(sessionId)
                    else if (info.obj("time").long("completed") == null && sessionId !in finished &&
                        pending.values.none { root(it.sessionId) == root(sessionId) }) {
                        // An automatic permission may resume without a fresh busy transition.
                        runs.getOrPut(sessionId) { Run(serverBusy = true) }
                    }
                } else if (info.str("role") == "user") {
                    val messageId = info.text("id")
                    if (messageId != null && remember(seenUserMessages, "$sessionId:$messageId")) {
                        finished.remove(sessionId)
                        awaitingMetadata.remove(sessionId)
                        queued.remove("completion:$sessionId")
                        runs.getOrPut(sessionId) { Run() }.apply {
                            failed = false
                            info.obj("time").long("created")?.let { startedAt = maxOf(startedAt ?: it, it) }
                        }
                    }
                }
            }
            "session.next.step.ended" -> if (sessionId != null && props.bool("executionContinues") != true && props.str("finish") !in CONTINUATIONS) {
                val completedAt = props.long("timestamp")
                val startedAt = runs[sessionId]?.startedAt
                if (completedAt == null || startedAt == null || completedAt >= startedAt) terminal(sessionId)
            }
            "server.connected", "server.instance.disposed", "global.disposed" -> {
                // Reconnect snapshots may restore pending asks, but must not announce historical completions.
                runs.clear()
                awaitingMetadata.clear()
                queued.clear()
                batchAt = null
            }
        }
        update()
    }

    private fun ask(info: JsonObject?, kind: Kind, fromSnapshot: Boolean = false) {
        val id = requestId(info) ?: return
        val sessionId = info.text("sessionID") ?: return
        val key = key(kind, id)
        if (!fromSnapshot) {
            resolved.remove(key)
            mutate(key)
        }
        if (key in pending || key in resolved) return
        invalidateTree(sessionId)
        pending[key] = Request(sessionId, kind, now() + if (kind == Kind.PERMISSION) 20_000 else 0)
    }

    fun revealPermission(id: String) {
        pending[key(Kind.PERMISSION, id)]?.revealAt = now()
        update()
    }

    fun resolve(kind: Kind, id: String) {
        val key = key(kind, id)
        mutate(key)
        remember(resolved, key)
        val request = pending.remove(key)
        queued.remove(key)
        // An answered question is a continuation, not a completed reply.
        if (kind == Kind.QUESTION && request != null) invalidateTree(request.sessionId)
        update()
    }

    /** Captured before the GET so a late snapshot cannot undo a newer ask/reply/deletion. */
    fun snapshotRevision(): Long = revision

    fun reconcile(kind: Kind, values: JsonArray, startedAt: Long) {
        if (startedAt < mutationFloor) return
        val ids = values.mapNotNull { requestId(it.asObjectOrNull()) }.toSet()
        pending.filter { (id, request) -> request.kind == kind && id.substringAfter(':') !in ids && (mutations[id] ?: 0) <= startedAt }
            .keys.toList().forEach { resolve(kind, it.substringAfter(':')) }
        values.forEach { value ->
            val info = value.asObjectOrNull() ?: return@forEach
            val id = requestId(info) ?: return@forEach
            if ((mutations[key(kind, id)] ?: 0) <= startedAt && (mutations["session:${info.text("sessionID")}"] ?: 0) <= startedAt) {
                ask(info, kind, fromSnapshot = true)
            }
        }
        update()
    }

    fun seen(sessionId: String) {
        awaitingMetadata.remove(root(sessionId))
        queued.entries.removeIf { root(it.value.sessionId) == root(sessionId) }
        update()
    }

    private fun terminal(sessionId: String) {
        val run = runs[sessionId] ?: return
        if (!run.serverBusy) finish(sessionId)
    }

    private fun finish(sessionId: String) {
        val run = runs.remove(sessionId) ?: return
        if (run.failed) return
        remember(finished, sessionId)
        if (!knowsSession(sessionId)) {
            remember(awaitingMetadata, sessionId)
            return
        }
        complete(sessionId)
    }

    private fun complete(sessionId: String) {
        val session = sessions[sessionId] ?: return
        if (!canComplete(sessionId)) return
        val kind = if (session.agent == "plan") Kind.PLAN_READY else Kind.COMPLETED
        if (shouldNotify(sessionId, kind)) queued["completion:$sessionId"] = Notice(sessionId, kind)
    }

    private fun cancelCompletion(sessionId: String) {
        runs[sessionId]?.failed = true
        awaitingMetadata.remove(sessionId)
        queued.remove("completion:$sessionId")
    }

    fun aborted(sessionId: String) {
        cancelCompletion(sessionId)
        runs.remove(sessionId)
        remember(finished, sessionId)
        update()
    }

    private fun invalidateTree(sessionId: String) {
        val root = root(sessionId)
        runs.remove(root)
        runs.remove(sessionId)
        awaitingMetadata.remove(root)
        awaitingMetadata.remove(sessionId)
        queued.remove("completion:$root")
        queued.remove("completion:$sessionId")
    }

    private fun root(id: String): String {
        var current = id
        val visited = mutableSetOf<String>()
        while (visited.add(current)) current = sessions[current]?.parent ?: return current
        return id
    }

    private fun removeSession(id: String) {
        mutate("session:$id")
        pending.filterValues { it.sessionId == id }.keys.toList().forEach { key ->
            pending.remove(key)
            queued.remove(key)
        }
        runs.remove(id)
        awaitingMetadata.remove(id)
        sessions.remove(id)
        queued.remove("completion:$id")
    }

    private fun canComplete(id: String): Boolean {
        val session = sessions[id] ?: return false
        return session.ancestryKnown && session.parent == null && !session.subagent && pending.values.none {
            inScope(it.sessionId) != false && (!knowsSession(it.sessionId) || root(it.sessionId) == id)
        }
    }

    private fun shouldNotify(id: String, kind: Kind) = settings().enabled(kind) && inScope(id) == true && !isFocused()

    private fun mutate(key: String) {
        mutations.remove(key)
        mutations[key] = ++revision
        if (mutations.size > 8192) {
            val oldest = mutations.entries.first()
            mutationFloor = oldest.value
            mutations.remove(oldest.key)
        }
    }

    fun update() {
        queued.entries.removeIf {
            !shouldNotify(it.value.sessionId, it.value.kind) ||
                it.key.startsWith("completion:") && !canComplete(it.value.sessionId)
        }
        for ((id, request) in pending) {
            if (request.observed || request.revealAt > now()) continue
            // A global pending snapshot may contain another workspace's asks. Wait for metadata.
            if (inScope(request.sessionId) == null && settings().enabled(request.kind) && !isFocused()) continue
            request.observed = true
            if (shouldNotify(request.sessionId, request.kind)) queued[id] = Notice(request.sessionId, request.kind)
        }
        if (queued.isEmpty()) batchAt = null
        else if (batchAt == null) batchAt = maxOf(now() + 300, nextDeliveryAt)
    }

    fun nextDeadline(): Long? = (pending.values.filter { !it.observed && inScope(it.sessionId) != null }
        .map { it.revealAt } + listOfNotNull(batchAt)).minOrNull()

    /** Visibility is rechecked at delivery, including macOS occlusion. A visible burst is consumed. */
    fun deliver(editorVisible: Boolean): Delivery? {
        update()
        if (batchAt == null || now() < batchAt!!) return null
        val events = queued.values.toList()
        queued.clear()
        batchAt = null
        if (editorVisible || isFocused() || events.isEmpty()) return null
        val first = events.first()
        val descriptions = events.filter { it.sessionId == first.sessionId }.map { it.kind.label }.distinct()
        val otherChats = events.map { it.sessionId }.distinct().size - 1
        val message = descriptions.joinToString("; ") + if (otherChats > 0) "; Updates in $otherChats other ${if (otherChats == 1) "chat" else "chats"}" else ""
        nextDeliveryAt = now() + 5_000
        val settings = settings()
        return Delivery(first.sessionId, sessions[first.sessionId]?.title ?: "Untitled chat", message,
            settings.native, events.any { it.kind in settings.sounds })
    }

    companion object {
        private val CONTINUATIONS = setOf("tool-calls", "unknown")
        private fun key(kind: Kind, id: String) = "${kind.name}:$id"
        private fun requestId(info: JsonObject?) = info.text("id") ?: info.text("permissionID") ?: info.text("requestID")
        fun clean(value: String) = value.replace(Regex("[\\p{Cc}]"), " ").trim().take(180)
        private fun remember(set: MutableSet<String>, id: String): Boolean {
            if (!set.add(id)) return false
            if (set.size > 4096) set.remove(set.first())
            return true
        }
    }
}
