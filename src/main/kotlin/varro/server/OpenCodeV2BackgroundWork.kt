package varro.server

import com.google.gson.JsonObject
import varro.protocol.*

/** Keeps sessions busy through background job completion and the follow-up turn. */
internal class OpenCodeV2BackgroundWork(private val now: () -> Long = System::currentTimeMillis) {
    private data class Shell(val sessionID: String, val directory: String?, val startedAt: Double?, val command: String?, var service: Boolean = false)
    private data class Waiting(val directory: String?, var endedAt: Long?, var command: String?)
    private val shells = mutableMapOf<String, Shell>()
    private val mutations = mutableMapOf<String, Long>()
    private val sessionMutations = mutableMapOf<String, Long>()
    private val waiting = mutableMapOf<String, Waiting>()
    private val stopped = mutableSetOf<String>()
    private data class Jobs(val directory: String?, val version: Long, val items: List<JsonObject>, var serviceIDs: Set<String>)
    private data class Pending(val directory: String?, val startedAt: Double?, val command: String?)
    private val jobs = mutableMapOf<String, Jobs>()
    private val snapshots = mutableMapOf<String?, Long>()
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
            pending(sessionID).firstOrNull()?.takeIf { sessionID !in stopped }?.let {
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
    @Synchronized fun startedAt(sessionID: String) = pending(sessionID).mapNotNull { it.startedAt }.minOrNull()
    @Synchronized fun command(sessionID: String) = pending(sessionID).firstOrNull { it.command != null }?.command ?: waiting[sessionID]?.command
    @Synchronized fun serviceCount(sessionID: String) = shells.values.count { it.sessionID == sessionID && it.service }
    @Synchronized fun serviceSessionIDs() = shells.values.filter { it.service }.map { it.sessionID }.distinct()
    @Synchronized fun setService(id: String, service: Boolean) {
        val shell = shells[id] ?: return
        shell.service = service
        mutations[id] = ++revision
        sessionMutations[shell.sessionID] = revision
        for ((sessionID, native) in jobs) {
            if (native.items.none { it.str("kind") == "shell" && it.str("shellID") == id }) continue
            native.serviceIDs = if (service) native.serviceIDs + id else native.serviceIDs - id
            if (pending(sessionID).isEmpty()) waiting.remove(sessionID)
        }
        if (service && pending(shell.sessionID).isEmpty()) waiting.remove(shell.sessionID)
    }
    @Synchronized fun snapshotVersion() = ++revision

    @Synchronized fun reconcile(snapshot: List<JsonObject>, active: Set<String>, directory: String?, version: Long, serviceIDs: Set<String> = emptySet(), nativeJobs: Map<String, List<JsonObject>>? = null): List<String> {
        if ((snapshots[directory] ?: 0) > version) return waiting.keys.toList()
        snapshots[directory] = version
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
        val sessionIDs = (shells.values.filter { it.directory == directory }.map { it.sessionID } +
            jobs.filterValues { it.directory == directory }.keys + waiting.filterValues { it.directory == directory }.keys + nativeJobs?.keys.orEmpty()).toSet()
        for (id in sessionIDs) {
            if (nativeJobs != null) jobs[id] = Jobs(directory, version, nativeJobs[id].orEmpty(), serviceIDs)
            else jobs.remove(id)
        }
        for (id in sessionIDs) {
            if (pending(id).isEmpty()) continue
            if ((sessionMutations[id] ?: 0) > version) continue
            if (id !in active && id !in stopped) waiting[id] = Waiting(directory, null, command(id))
        }
        for ((id, pending) in waiting.toMap()) {
            if (pending.directory != directory) continue
            if ((sessionMutations[id] ?: 0) > version) continue
            if (id in active) waiting.remove(id)
            else if (pending(id).isEmpty()) {
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
        shells.clear(); mutations.clear(); sessionMutations.clear(); waiting.clear(); stopped.clear(); jobs.clear(); snapshots.clear(); revision = 0
    }

    private fun pending(sessionID: String): List<Pending> {
        val native = jobs[sessionID]
        val live = shells.filter { (id, shell) -> shell.sessionID == sessionID && !shell.service &&
            (native == null || ((mutations[id] ?: 0) > native.version && native.items.none { it.str("kind") == "shell" && it.str("shellID") == id }))
        }.values.map { Pending(it.directory, it.startedAt, it.command) }
        if (native == null) return live
        return native.items.mapNotNull { job ->
            if (job.str("status") == "cancelled") return@mapNotNull null
            if (job.str("kind") == "shell") {
                val id = job.str("shellID")!!
                if (id in native.serviceIDs) return@mapNotNull null
                if ((mutations[id] ?: 0) > native.version) return@mapNotNull shells[id]?.takeIf { !it.service }?.let {
                    Pending(native.directory, it.startedAt, it.command)
                }
            }
            Pending(native.directory, job.obj("time").num("started"), summary(when (job.str("kind")) {
                "shell" -> job.str("command")
                "subagent" -> job.str("title") ?: job.str("description")
                else -> job.str("title") ?: job.str("path")
            }))
        } + live
    }

    private fun summary(value: String?) = value?.take(512)?.replace(Regex("\\s+"), " ")?.trim()

    private fun shell(info: JsonObject, sessionID: String, directory: String?, service: Boolean) = Shell(
        sessionID, directory, info.obj("time").num("started")?.takeIf { it.isFinite() },
        summary(info.str("command")), service,
    )
}
