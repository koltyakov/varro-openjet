package varro.server

import com.google.gson.JsonObject
import varro.protocol.*

/** Keeps sessions busy through background shell completion and the follow-up turn. */
internal class OpenCodeV2BackgroundWork(private val now: () -> Long = System::currentTimeMillis) {
    private data class Shell(val sessionID: String, val directory: String?, val startedAt: Double?, val command: String?, var service: Boolean = false)
    private data class Waiting(val directory: String?, var endedAt: Long?, var command: String?)
    private val shells = mutableMapOf<String, Shell>()
    private val mutations = mutableMapOf<String, Long>()
    private val sessionMutations = mutableMapOf<String, Long>()
    private val waiting = mutableMapOf<String, Waiting>()
    private val stopped = mutableSetOf<String>()
    private var revision = 0L

    @Synchronized fun observe(type: String, data: JsonObject, directory: String?) {
        if (type == "shell.created") {
            val info = data.obj("info") ?: return
            val id = info.str("id")
            val sessionID = info.obj("metadata").str("sessionID")
            if (id != null && sessionID != null && info.str("status") == "running") {
                shells[id] = shell(info, sessionID, directory, shells[id]?.service == true)
                mutations[id] = ++revision
                sessionMutations[sessionID] = revision
            }
        }
        if (type in setOf("shell.exited", "shell.deleted")) data.str("id")?.let { id ->
            val shell = shells.remove(id)
            val sessionID = shell?.sessionID
            mutations[id] = ++revision
            if (sessionID != null) {
                sessionMutations[sessionID] = revision
                waiting[sessionID]?.takeIf { shell.service != true && shellIDs(sessionID).isEmpty() }?.let {
                    it.endedAt = now(); it.command = shell.command ?: it.command
                }
            }
        }
        val sessionID = data.str("sessionID") ?: return
        if (type.startsWith("session.execution.") || type.startsWith("session.step.")) sessionMutations[sessionID] = ++revision
        if (type == "session.execution.succeeded" || (type == "session.step.ended" && data.str("finish") == "stop")) {
            shells.values.firstOrNull { it.sessionID == sessionID && !it.service }?.takeIf { sessionID !in stopped }?.let {
                waiting[sessionID] = Waiting(it.directory, null, it.command)
            }
        }
        if (type in setOf("session.step.started", "session.execution.failed", "session.execution.interrupted", "session.deleted")) waiting.remove(sessionID)
        if (type in setOf("session.execution.started", "session.step.started")) stopped.remove(sessionID)
        if (type in setOf("session.execution.failed", "session.execution.interrupted", "session.deleted")) stopped.add(sessionID)
    }

    @Synchronized fun isWaiting(sessionID: String) = waiting.containsKey(sessionID)
    @Synchronized fun hasShell(id: String) = shells.containsKey(id)
    @Synchronized fun shellIDs(sessionID: String) = shells.filterValues { it.sessionID == sessionID && !it.service }.keys.toList()
    @Synchronized fun startedAt(sessionID: String) = shells.values.filter { it.sessionID == sessionID && !it.service }.mapNotNull { it.startedAt }.minOrNull()
    @Synchronized fun command(sessionID: String) = shells.values.firstOrNull { it.sessionID == sessionID && !it.service && it.command != null }?.command ?: waiting[sessionID]?.command
    @Synchronized fun serviceCount(sessionID: String) = shells.values.count { it.sessionID == sessionID && it.service }
    @Synchronized fun serviceSessionIDs() = shells.values.filter { it.service }.map { it.sessionID }.distinct()
    @Synchronized fun setService(id: String, service: Boolean) {
        val shell = shells[id] ?: return
        shell.service = service
        mutations[id] = ++revision
        sessionMutations[shell.sessionID] = revision
        if (service && shellIDs(shell.sessionID).isEmpty()) waiting.remove(shell.sessionID)
    }
    @Synchronized fun snapshotVersion() = revision

    @Synchronized fun reconcile(snapshot: List<JsonObject>, active: Set<String>, directory: String?, version: Long, serviceIDs: Set<String> = emptySet()): List<String> {
        val runningShells = snapshot.mapNotNull { info ->
            val id = info.str("id")
            val sessionID = info.obj("metadata").str("sessionID")
            if (id == null || sessionID == null || info.str("status") != "running") null
            else id to shell(info, sessionID, directory, id in serviceIDs)
        }.toMap()
        for (id in shells.keys + runningShells.keys) {
            if ((mutations[id] ?: 0) > version || (shells.containsKey(id) && shells[id]?.directory != directory)) continue
            runningShells[id]?.let { shells[id] = it } ?: shells.remove(id)
        }
        for (shell in shells.values) {
            val id = shell.sessionID
            if (shell.service || shell.directory != directory) continue
            if ((sessionMutations[id] ?: 0) > version) continue
            if (id !in active && id !in stopped) waiting[id] = Waiting(directory, null, command(id))
        }
        for ((id, pending) in waiting.toMap()) {
            if (pending.directory != directory) continue
            if ((sessionMutations[id] ?: 0) > version) continue
            if (id in active) waiting.remove(id)
            else if (shellIDs(id).isEmpty()) {
                if (pending.endedAt == null) pending.endedAt = now()
                else if (now() - pending.endedAt!! >= 2_000) waiting.remove(id)
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

    private fun shell(info: JsonObject, sessionID: String, directory: String?, service: Boolean) = Shell(
        sessionID, directory, info.obj("time").num("started")?.takeIf { it.isFinite() },
        info.str("command")?.take(512)?.replace(Regex("\\s+"), " ")?.trim(), service,
    )
}
