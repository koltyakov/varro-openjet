package varro.host

import com.google.gson.JsonElement
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import varro.store.NativeFileLock
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/** Every delete is preceded by durable preservation and a fresh whole-tree validation. */
internal class SessionCleanup(
    private val archive: UsageArchive,
    private val version: Int,
    private val localManaged: () -> Boolean,
    private val request: (String, String, RequestOptions) -> OpenCodeResponse,
    private val withStoppedServer: ((() -> Unit) -> Unit),
    private val progress: (String) -> Unit = {},
) {
    data class Inventory(val sessions: List<CleanupSession>, val legacy: List<CleanupSession>)
    data class Result(val deleted: Int, val cancelled: Boolean)

    fun <T> locked(check: () -> Unit = {}, operation: () -> T): T {
        require(localManaged()) { "Session cleanup requires a verified local managed OpenCode server" }
        val release = NativeFileLock.acquire(Path.of("${archive.archivePath}.cleanup.lock"), check)
        try { return operation() } finally { release() }
    }

    fun inventory(check: () -> Unit = {}): Inventory {
        require(localManaged()) { "Session cleanup must run on the local managed server host" }
        val sessions = linkedMapOf<String, CleanupSession>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            check()
            val response = request("GET", "/experimental/session?archived=true&limit=1000${cursor?.let { "&cursor=${encode(it)}" }.orEmpty()}", options(check).copy(captureNextCursor = true))
            val page = response.data.asArrayOrNull() ?: response.data.asObjectOrNull().arr("data") ?: error("Malformed global session inventory")
            page.forEach { value ->
                val session = CleanupSession.decode(value.asObjectOrNull() ?: error("Malformed session"), version)
                require(session.id !in sessions) { "Repeated global session identity" }
                sessions[session.id] = session
            }
            cursor = response.nextCursor
            check(cursor == null || cursor.isNotBlank() && cursors.add(cursor)) { "OpenCode repeated a session pagination cursor" }
        } while (cursor != null)
        val legacy = if (version == 2) archive.legacyInventory(sessions.values.toList(), check) else emptyList()
        return Inventory(sessions.values.toList(), legacy)
    }

    fun run(inventory: Inventory, cutoff: Long, preserveTranscripts: Boolean, check: () -> Unit = {}): Result {
        var deleted = 0
        var deleting = false
        val retained = inventory.sessions.associateBy { it.id }.toMutableMap()
        val legacy = inventory.legacy.associateBy { it.id }.toMutableMap()
        try {
            progress("Processing sessions")
            for (session in inventory.sessions.sortedWith(compareBy<CleanupSession> { it.updated }.thenBy { it.id })) {
                check()
                if (session.updated >= cutoff || session.id !in retained) continue
                val tree = tree(session, retained)
                if (tree.any { it.updated >= cutoff } || !unchangedAndIdle(tree, preserveTranscripts, check)) continue
                archive.archive(tree, check)
                check()
                if (preserveTranscripts) TranscriptArchive(archive).archive(tree, version, request, check)
                check()
                if (!unchangedAndIdle(tree, preserveTranscripts, check)) continue
                check()
                deleting = true
                // Once dispatched, cancellation cannot abandon a recursive deletion or its confirmation.
                val result = request("DELETE", sessionPath(session), options({})).data
                require(result != Json.toElement(false)) { "OpenCode refused session deletion" }
                for (entry in tree) confirmDeleted(entry)
                tree.forEach { retained.remove(it.id); deleted++ }
                deleting = false
            }
            val eligible = inventory.legacy.filter { it.updated < cutoff && tree(it, legacy).all { child -> child.updated < cutoff } }
            if (eligible.isNotEmpty()) {
                check()
                progress("Preparing legacy cleanup")
                withStoppedServer {
                    LegacySessionCleanup(archive).delete(eligible, cutoff, preserveTranscripts, check, { deleting = true })
                    deleted += eligible.size
                    eligible.forEach { legacy.remove(it.id) }
                    deleting = false
                }
            }
            check()
            val pending = archive.pending(check).filter { it.session.id !in retained && it.session.id !in legacy }
            ArtifactCleanup.clean(archive, pending, check, progress)
            return Result(deleted, false)
        } catch (failure: Exception) {
            if (!deleting && (failure is CancellationException || failure is com.intellij.openapi.progress.ProcessCanceledException)) return Result(deleted, true)
            throw IllegalStateException("Cleanup stopped. $deleted sessions deleted; saved archives are safe. Unfinished work can be resumed. ${failure.message}", failure)
        }
    }

    private fun unchangedAndIdle(tree: List<CleanupSession>, transcript: Boolean, check: () -> Unit): Boolean {
        val directories = if (version == 2) {
            val active = read("/api/session/active", check).asObjectOrNull().obj("data") ?: error("Malformed global session activity")
            if (tree.any { active.has(it.id) }) return false
            val loaded = read("/api/debug/location", check).asArrayOrNull() ?: error("Malformed loaded locations")
            loaded.map { value -> value.asObjectOrNull().text("directory")?.takeIf { Path.of(it).isAbsolute } ?: error("Malformed loaded location") }.distinct()
        } else tree.map { it.directory }.distinct()
        for (directory in directories) {
            val statuses = read("/session/status?directory=${encode(directory)}", check).asObjectOrNull() ?: error("Malformed session activity")
            if (tree.any { session -> statuses.has(session.id) && statuses.obj(session.id).let { it.str("type") != "idle" || (it.num("backgroundServices") ?: 0.0) > 0 } }) return false
        }
        for (session in tree) {
            if (transcript && version == 2) {
                val inbox = read("/api/session/${encode(session.id)}/inbox", check).asObjectOrNull().arr("data") ?: error("Malformed transcript inbox")
                if (!inbox.isEmpty) return false
            }
            val current = CleanupSession.decode(read(sessionPath(session), check).asObjectOrNull() ?: error("Malformed session metadata"), version)
            if (current.updated != session.updated || current.directory != session.directory || current.parentID != session.parentID) return false
            val children = read(sessionPath(session, "/children"), check).asArrayOrNull() ?: error("Malformed child sessions")
            require(children.size() < 1000) { "Cannot safely verify every child session" }
            val expected = tree.filter { it.parentID == session.id }.associateBy { it.id }
            if (children.size() != expected.size || children.any { value ->
                val child = CleanupSession.decode(value.asObjectOrNull() ?: error("Malformed child metadata"), version)
                expected[child.id]?.updated != child.updated
            }) return false
        }
        return true
    }

    private fun confirmDeleted(session: CleanupSession) {
        try { read(sessionPath(session), {}) }
        catch (failure: Exception) {
            if (Regex("^404\\b").containsMatchIn(failure.message.orEmpty()) && !WorkspaceDirectoryErrors.isMissing(failure.message)) return
            throw failure
        }
        error("OpenCode did not confirm deletion of ${session.id}")
    }

    private fun read(path: String, check: () -> Unit): JsonElement? { check(); return request("GET", path, options(check)).data.also { check() } }
    private fun options(check: () -> Unit) = RequestOptions(unscoped = true, nativePaths = true, timeoutMs = 30_000, cancellableBody = true,
        maxResponseBytes = 64L * 1024 * 1024, isCancelled = { check(); false })
    private fun sessionPath(session: CleanupSession, suffix: String = "") = "/session/${encode(session.id)}$suffix" +
        if (version == 1) "?directory=${encode(session.directory)}" else ""

    companion object {
        fun retentionCutoff(months: Int, now: Long = System.currentTimeMillis()): Long {
            require(months in 1..1200) { "Enter a whole number of months from 1 to 1200" }
            return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).minusMonths(months.toLong()).toInstant().toEpochMilli()
        }

        fun tree(root: CleanupSession, sessions: Map<String, CleanupSession>): List<CleanupSession> {
            val children = sessions.values.groupBy { it.parentID }
            val tree = mutableListOf(root)
            val seen = mutableSetOf(root.id)
            var index = 0
            while (index < tree.size) for (child in children[tree[index++].id].orEmpty()) {
                require(seen.add(child.id)) { "Cyclic session tree" }
                tree.add(child)
            }
            return tree
        }
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
