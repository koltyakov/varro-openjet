package varro.server

import com.google.gson.JsonObject
import varro.protocol.*

/** Keeps sessions busy through background shell completion and the follow-up turn. */
internal class OpenCodeV2BackgroundWork(private val now: () -> Long = System::currentTimeMillis) {
    private data class Shell(val sessionID: String, val directory: String?, val startedAt: Double?)
    private val shells = mutableMapOf<String, Shell>()
    private val mutations = mutableMapOf<String, Long>()
    private val sessionMutations = mutableMapOf<String, Long>()
    private val waiting = mutableMapOf<String, Long?>()
    private val stopped = mutableSetOf<String>()
    private var revision = 0L

    @Synchronized fun observe(type: String, data: JsonObject, directory: String?) {
        if (type == "shell.created") {
            val info = data.obj("info")
            val id = info.str("id")
            val sessionID = info.obj("metadata").str("sessionID")
            if (id != null && sessionID != null && info.str("status") == "running") {
                shells[id] = Shell(sessionID, directory, info.obj("time").num("started")?.takeIf { it.isFinite() })
                mutations[id] = ++revision
            }
        }
        if (type in setOf("shell.exited", "shell.deleted")) data.str("id")?.let { id ->
            val sessionID = shells.remove(id)?.sessionID
            mutations[id] = ++revision
            if (sessionID != null && waiting.containsKey(sessionID) && shellIDs(sessionID).isEmpty()) waiting[sessionID] = now()
        }
        val sessionID = data.str("sessionID") ?: return
        if (type.startsWith("session.execution.") || type.startsWith("session.step.")) sessionMutations[sessionID] = ++revision
        if (type == "session.execution.succeeded" || (type == "session.step.ended" && data.str("finish") == "stop")) {
            if (shellIDs(sessionID).isNotEmpty()) waiting[sessionID] = null else waiting.remove(sessionID)
        }
        if (type in setOf("session.step.started", "session.execution.failed", "session.execution.interrupted", "session.deleted")) waiting.remove(sessionID)
        if (type == "session.execution.started") stopped.remove(sessionID)
        if (type in setOf("session.execution.failed", "session.execution.interrupted")) stopped.add(sessionID)
    }

    @Synchronized fun isWaiting(sessionID: String) = waiting.containsKey(sessionID)
    @Synchronized fun shellIDs(sessionID: String) = shells.filterValues { it.sessionID == sessionID }.keys.toList()
    @Synchronized fun startedAt(sessionID: String) = shells.values.filter { it.sessionID == sessionID }.mapNotNull { it.startedAt }.minOrNull()
    @Synchronized fun snapshotVersion() = revision

    @Synchronized fun reconcile(snapshot: List<JsonObject>, active: Set<String>, directory: String?, version: Long): List<String> {
        val runningShells = snapshot.mapNotNull { info ->
            val id = info.str("id")
            val sessionID = info.obj("metadata").str("sessionID")
            if (id == null || sessionID == null || info.str("status") != "running") null
            else id to Shell(sessionID, directory, info.obj("time").num("started")?.takeIf { it.isFinite() })
        }.toMap()
        for (id in shells.keys + runningShells.keys) {
            if ((mutations[id] ?: 0) > version || (shells.containsKey(id) && shells[id]?.directory != directory)) continue
            runningShells[id]?.let { shells[id] = it } ?: shells.remove(id)
        }
        val running = shells.values.map { it.sessionID }.toSet()
        for (id in running) {
            if ((sessionMutations[id] ?: 0) > version) continue
            if (id !in active && id !in stopped) waiting[id] = null
        }
        for ((id, endedAt) in waiting.toMap()) {
            if ((sessionMutations[id] ?: 0) > version) continue
            if (id in active) waiting.remove(id)
            else if (id !in running) {
                if (endedAt == null) waiting[id] = now()
                else if (now() - endedAt >= 2_000) waiting.remove(id)
            }
        }
        return waiting.keys.toList()
    }

    @Synchronized fun clearSession(sessionID: String) {
        sessionMutations[sessionID] = ++revision
        waiting.remove(sessionID)
        stopped.add(sessionID)
    }

    @Synchronized fun reset() {
        shells.clear(); mutations.clear(); sessionMutations.clear(); waiting.clear(); stopped.clear(); revision = 0
    }
}
