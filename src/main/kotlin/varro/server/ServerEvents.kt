package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.asObjectOrNull
import varro.protocol.int
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.str
import varro.protocol.text

/** A normalized OpenCode event. */
data class ParsedServerEvent(
    val type: String,
    val id: String? = null,
    val sequenceOnly: Boolean = false,
    val sequenceStart: Int? = null,
    val workspaceDirectory: String? = null,
    val seq: Int? = null,
    val properties: JsonObject? = null,
)

/**
 * Normalizes OpenCode's event envelopes and maintains the caches the host routes
 * on.
 *
 * Port of `parseServerEvent` in `src/shared/protocol.ts` plus
 * `observeServerEvent` in `src/extension/open-code-transport.ts`.
 *
 * OpenCode has shipped several envelope shapes over time - a direct
 * `{ type, properties }`, a `{ type: 'sync', syncEvent }` wrapper, and versioned
 * names such as `session.updated.2` - so all of them are accepted and collapsed
 * onto the canonical name the webview knows.
 */
object ServerEvents {

    /**
     * Every event name the webview understands. Anything outside this set is
     * dropped: the webview's own `parseExtensionMessage` would reject it anyway,
     * and forwarding unknown events has no consumer.
     */
    val NAMES: Set<String> = setOf(
        "server.connected", "server.heartbeat", "server.instance.disposed", "global.disposed",
        "catalog.updated", "models-dev.refreshed", "installation.updated",
        "installation.update-available", "integration.updated", "integration.connection.updated",
        "file.edited", "file.watcher.updated", "reference.updated", "plugin.added",
        "project.directories.updated", "project.updated",
        "session.created", "session.updated", "session.deleted", "session.status", "session.error",
        "session.idle", "session.compacted", "session.diff",
        "message.updated", "message.part.updated", "message.part.delta", "message.part.removed",
        "message.removed",
        "permission.updated", "permission.asked", "permission.replied",
        "permission.v2.asked", "permission.v2.replied",
        "question.asked", "question.replied", "question.rejected",
        "question.v2.asked", "question.v2.replied", "question.v2.rejected",
        "todo.updated", "command.executed",
        "lsp.client.diagnostics", "lsp.updated", "vcs.branch.updated",
        "mcp.tools.changed", "mcp.browser.open.failed",
        "pty.created", "pty.updated", "pty.exited", "pty.deleted",
        "tui.prompt.append", "tui.command.execute", "tui.toast.show", "tui.session.select",
        "workspace.ready", "workspace.failed", "workspace.status",
        "worktree.ready", "worktree.failed",
        "session.next.agent.switched", "session.next.model.switched", "session.next.moved",
        "session.next.prompted", "session.next.prompt.admitted", "session.next.context.updated",
        "session.next.synthetic", "session.next.shell.started", "session.next.shell.ended",
        "session.next.step.started", "session.next.step.ended", "session.next.step.failed",
        "session.next.text.started", "session.next.text.delta", "session.next.text.ended",
        "session.next.reasoning.started", "session.next.reasoning.delta",
        "session.next.reasoning.ended",
        "session.next.tool.input.started", "session.next.tool.input.delta",
        "session.next.tool.input.ended",
        "session.next.tool.called", "session.next.tool.progress", "session.next.tool.success",
        "session.next.tool.failed", "session.next.retried",
        "session.next.compaction.started", "session.next.compaction.delta",
        "session.next.compaction.ended",
        "session.next.revert.staged", "session.next.revert.cleared", "session.next.revert.committed",
    )

    private const val MAX_EVENT_ID_LENGTH = 512
    private val VERSION_SUFFIX = Regex("""\.\d+$""")

    fun parse(value: JsonElement?): ParsedServerEvent? {
        val record = value.asObjectOrNull() ?: return null

        // The outer envelope may carry the workspace even when the inner event does not.
        val workspaceDirectory = record.text("directory")
            ?: record.obj("location").text("directory")

        parseRecord(record)?.let { direct ->
            return if (workspaceDirectory != null && direct.workspaceDirectory == null) {
                direct.copy(workspaceDirectory = workspaceDirectory)
            } else {
                direct
            }
        }

        val nested = parseRecord(record.obj("payload")) ?: parseRecord(record.obj("data")) ?: return null
        return if (workspaceDirectory != null) nested.copy(workspaceDirectory = workspaceDirectory) else nested
    }

    private fun parseRecord(record: JsonObject?): ParsedServerEvent? {
        if (record == null) return null

        // `{ type: 'sync', syncEvent: { … } }` wraps the real event.
        val rawType = record.str("type")
        if (rawType == "sync") {
            parseSyncRecord(record.obj("syncEvent"))?.let { return it }
        }

        val eventType = when {
            rawType != null && rawType in NAMES -> rawType
            rawType == "sync" -> canonicalName(record.str("name"))
            else -> null
        } ?: return null

        // Direct envelopes put the payload under `properties`; sync wrappers under `data`.
        val properties = if (rawType in NAMES) {
            record.obj("properties") ?: record.obj("data")
        } else {
            record.obj("data")
        }

        return ParsedServerEvent(
            type = eventType,
            id = eventId(record),
            sequenceOnly = record.get("sequenceOnly")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true,
            sequenceStart = record.int("sequenceStart"),
            workspaceDirectory = record.text("workspaceDirectory"),
            seq = eventSeq(record),
            properties = properties,
        )
    }

    private fun parseSyncRecord(record: JsonObject?): ParsedServerEvent? {
        if (record == null) return null
        val eventType = canonicalName(record.str("type")) ?: return null
        return ParsedServerEvent(
            type = eventType,
            id = eventId(record),
            sequenceOnly = record.get("sequenceOnly")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true,
            sequenceStart = record.int("sequenceStart"),
            workspaceDirectory = record.text("workspaceDirectory"),
            seq = eventSeq(record),
            properties = record.obj("data"),
        )
    }

    /** `session.updated.2` and `session.updated` are the same event to the webview. */
    private fun canonicalName(value: String?): String? {
        if (value == null) return null
        val name = VERSION_SUFFIX.replace(value, "")
        return if (name in NAMES) name else null
    }

    private fun eventId(record: JsonObject): String? =
        record.str("id")?.takeIf { it.isNotEmpty() && it.length <= MAX_EVENT_ID_LENGTH }

    /**
     * Durable per-session cursor. Current payloads put it under `durable.seq`;
     * transitional sync wrappers still expose it at the top level.
     */
    private fun eventSeq(record: JsonObject): Int? =
        record.num("seq")?.takeIf { it.isFinite() }?.toInt()
            ?: record.obj("durable").num("seq")?.takeIf { it.isFinite() }?.toInt()

    /**
     * Updates the caches that let the host answer "which session is this
     * permission for?" and "which directory owns this session?" without a REST
     * round trip.
     */
    fun observe(
        event: JsonElement?,
        pendingAttentionRequests: MutableMap<String, String>,
        observedSessionDirectories: MutableMap<String, String>,
    ) {
        val parsed = parse(event) ?: return
        val props = parsed.properties
        // Several payloads nest the interesting fields under `info`.
        val requestProps = props.obj("info") ?: props
        val eventDirectory = parsed.workspaceDirectory

        fun remember(sessionId: String?, directory: String? = eventDirectory) {
            if (sessionId != null && directory != null) observedSessionDirectories[sessionId] = directory
        }

        fun requestId(): String? =
            requestProps.str("id") ?: requestProps.str("permissionID") ?: requestProps.str("requestID")

        when (parsed.type) {
            "session.created", "session.updated" -> {
                val info = props.obj("info")
                remember(info.str("id"), info.text("directory") ?: eventDirectory)
            }

            "session.status", "session.idle" -> remember(props.str("sessionID"))

            "permission.updated", "permission.asked", "permission.v2.asked",
            "question.asked", "question.v2.asked",
            -> {
                val id = requestId()
                val sessionId = requestProps.str("sessionID")
                if (id != null && sessionId != null) {
                    pendingAttentionRequests[id] = sessionId
                    remember(sessionId)
                }
            }

            "permission.replied", "permission.v2.replied",
            "question.replied", "question.rejected",
            "question.v2.replied", "question.v2.rejected",
            -> requestId()?.let(pendingAttentionRequests::remove)

            "session.deleted" -> {
                val sessionId = props.str("sessionID") ?: props.obj("info").str("id") ?: return
                pendingAttentionRequests.entries.removeIf { it.value == sessionId }
                observedSessionDirectories.remove(sessionId)
            }

            "server.instance.disposed" -> {
                val disposed = WorkspacePaths.normalizeIdentity(eventDirectory) ?: return
                observedSessionDirectories.entries.removeIf {
                    WorkspacePaths.normalizeIdentity(it.value) == disposed
                }
            }

            "global.disposed" -> observedSessionDirectories.clear()
        }
    }
}
