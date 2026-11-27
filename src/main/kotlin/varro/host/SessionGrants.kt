package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.obj
import varro.protocol.str
import varro.protocol.text
import varro.server.WorkspacePaths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Per-session event and request access for sessions outside the project's exact open roots. */
interface EventAccess {
    fun routeEvent(type: String, ids: Collection<String>, directory: String): SessionGrants.EventRoute
    fun isGranted(id: String, directory: String?): Boolean
    fun resolveAsync(ids: Collection<String>, directory: String): CompletableFuture<SessionGrants.Resolution>
    fun isHiddenLineage(ids: Collection<String>, hidden: (String) -> Boolean): Boolean

    /** Blocking form for request threads. A timeout is inconclusive. */
    fun resolve(ids: Collection<String>, directory: String, timeoutMs: Long = 20_000): SessionGrants.Resolution =
        try { resolveAsync(ids, directory).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (_: Exception) { SessionGrants.Resolution.UNKNOWN }
}

/**
 * Per-session access for conversations this project retains outside its open roots, and for the
 * subagents they run. Port of upstream `rest-proxy.ts` `getAuthorizedSession` and
 * `learnSessionAncestry` for the JetBrains host's single-project routing.
 *
 * - A visible retained transfer whose destination is verified grants its own session that
 *   destination.
 * - A descendant inherits the grant of its nearest linked ancestor only while its own known
 *   location is that exact directory. An ancestor in an open root ends the walk: the project
 *   owns that conversation directly, so a child elsewhere inherits nothing.
 *
 * Grants are derived on every check, so removing or hiding a link, a failed verification, a
 * reconnect or a closed origin root revokes every child's grant too. Unknown ancestry is learned
 * from canonical session reads, but only for directories a visible link names, so unrelated
 * server traffic never triggers reads. A walk is bounded and inconclusive results never read as
 * "unrelated".
 */
class SessionGrants(
    private val roots: () -> List<String>,
    /** Visible retained links: projected summaries with `id`, `directory` and `transfer.available`. */
    private val links: () -> List<JsonObject>,
    /** Fresh verification of one retained link; may read the server. */
    private val verify: (String) -> Boolean,
    /** The session's last observed location, if any. */
    private val observedDirectory: (String) -> String?,
    /** A canonical, unscoped session record. */
    private val readSession: (String) -> JsonObject?,
    /** Changes whenever the server connection, URL or open roots change. */
    private val connection: () -> Any,
    private val executor: Executor,
    private val nanoTime: () -> Long = System::nanoTime,
) : EventAccess {
    data class Grant(val owner: String, val directory: String)

    enum class Resolution {
        /** Every session is granted the directory. */
        GRANTED,
        /** Confirmed outside any grant, or the directory belongs to no retained link. */
        UNRELATED,
        /** A read failed, the walk hit its bound, or the connection changed. */
        UNKNOWN,
    }

    /** `""` marks a confirmed root. Bounded, oldest first. */
    private val parents = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_SESSIONS
    }
    private val directories = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_SESSIONS
    }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Resolution>>()
    private val misses = ConcurrentHashMap<String, Long>()

    /** Learns a session's parent and location from a complete record (one that names its directory). */
    fun observe(session: JsonObject?) {
        val id = session.text("id")?.takeIf(::isSessionId) ?: return
        val directory = session.text("directory")?.takeIf(::isDirectory) ?: return
        val parent = session.text("parentID")?.takeIf(::isSessionId).orEmpty()
        synchronized(this) {
            parents[id] = parent
            directories[id] = directory
        }
    }

    @Synchronized fun forget(id: String) {
        parents.remove(id)
        directories.remove(id)
    }

    /** The session and its known ancestors, nearest first. */
    @Synchronized fun lineage(id: String): List<String> {
        val chain = mutableListOf(id)
        var current = id
        while (chain.size <= MAX_DEPTH) {
            val parent = parents[current]?.takeIf { it.isNotEmpty() } ?: break
            if (parent in chain) break
            chain += parent
            current = parent
        }
        return chain
    }

    private fun directoryOf(id: String): String? = observedDirectory(id) ?: synchronized(this) { directories[id] }

    private fun visibleLinks(): Map<String, JsonObject> =
        links().mapNotNull { link -> link.str("id")?.let { it to link } }.toMap()

    private fun verifiedLinks(visible: Map<String, JsonObject> = visibleLinks()): Map<String, String> = visible.mapNotNull { (id, link) ->
        link.str("directory")?.takeIf { link.obj("transfer").bool("available") == true }?.let { id to it }
    }.toMap()

    private fun isOpenRoot(directory: String?) = directory != null && roots().any { WorkspacePaths.isSame(it, directory) }

    /** The session's own or inherited grant, without any I/O. */
    fun grant(id: String): Grant? = grant(id, visibleLinks())

    private fun grant(id: String, visible: Map<String, JsonObject>): Grant? {
        val verified = verifiedLinks(visible)
        verified[id]?.let { return Grant(id, it) }
        // An unverified link owns its chain; it grants nothing until verified.
        if (id in visible) return null
        val own = directoryOf(id) ?: return null
        for (ancestor in lineage(id).drop(1)) {
            verified[ancestor]?.let { directory ->
                return if (WorkspacePaths.isSame(own, directory)) Grant(ancestor, directory) else null
            }
            if (ancestor in visible) return null
            if (isOpenRoot(directoryOf(ancestor))) return null
        }
        return null
    }

    override fun isGranted(id: String, directory: String?): Boolean = isGranted(id, directory, visibleLinks())

    private fun isGranted(id: String, directory: String?, visible: Map<String, JsonObject>): Boolean =
        directory != null && grant(id, visible)?.let { WorkspacePaths.isSame(it.directory, directory) } == true

    /**
     * The nearest retained conversation in this session's known lineage, verified or not. Writes to
     * its subagents follow that conversation's own writability.
     */
    fun linkedOwner(id: String): String? {
        val visible = visibleLinks()
        return lineage(id).firstOrNull { it in visible }
    }

    enum class EventRoute {
        /** Every session is granted the event's directory. */
        FORWARD,
        /** Resolve ancestry, then deliver this event in order if access was granted. */
        REDELIVER,
        /** Resolve ancestry for later events, but drop this one: a late transcript delta would reorder history. */
        RESOLVE,
        /** No retained link names the directory; another window owns it. */
        DROP,
    }

    /**
     * Routes an event from outside the open roots by its sessions' grants. Every grant's directory
     * is a visible link's destination, so other folders' traffic is dropped after one link read.
     */
    override fun routeEvent(type: String, ids: Collection<String>, directory: String): EventRoute {
        val visible = visibleLinks()
        return when {
            ids.isEmpty() || visible.values.none { WorkspacePaths.isSame(it.str("directory"), directory) } -> EventRoute.DROP
            ids.all { isGranted(it, directory, visible) } -> EventRoute.FORWARD
            type in REDELIVERED_EVENT_TYPES -> EventRoute.REDELIVER
            else -> EventRoute.RESOLVE
        }
    }

    /** Whether a visible retained link names this exact destination. */
    fun isGrantedDirectory(directory: String?): Boolean =
        directory != null && links().any { WorkspacePaths.isSame(it.str("directory"), directory) }

    /** Distinct destinations of visible links, after verifying any that are not yet verified. */
    fun verifiedDestinations(): List<String> {
        visibleLinks().forEach { (id, link) ->
            if (link.obj("transfer").bool("available") != true) runCatching { verify(id) }
        }
        return verifiedLinks().values.distinctBy(WorkspacePaths::normalizeIdentity)
    }

    /**
     * Resolves whether every session is granted `directory`, learning missing ancestry first.
     * Concurrent callers share one walk per session and directory, and an unrelated result is
     * remembered briefly so a stream of events does not repeat the reads.
     */
    override fun resolveAsync(ids: Collection<String>, directory: String): CompletableFuture<Resolution> {
        if (ids.isEmpty() || !isGrantedDirectory(directory)) return CompletableFuture.completedFuture(Resolution.UNRELATED)
        val walks = ids.distinct().map { resolveOne(it, directory) }
        return CompletableFuture.allOf(*walks.toTypedArray()).thenApply {
            val results = walks.map { it.join() }
            when {
                results.any { it == Resolution.UNKNOWN } -> Resolution.UNKNOWN
                results.all { it == Resolution.GRANTED } -> Resolution.GRANTED
                else -> Resolution.UNRELATED
            }
        }
    }

    private fun resolveOne(id: String, directory: String): CompletableFuture<Resolution> {
        if (isGranted(id, directory)) return CompletableFuture.completedFuture(Resolution.GRANTED)
        val key = "$id\u0000${WorkspacePaths.normalizeIdentity(directory)}"
        misses[key]?.let { expires ->
            if (nanoTime() - expires < 0) return CompletableFuture.completedFuture(Resolution.UNRELATED)
            misses.remove(key, expires)
        }
        val created = CompletableFuture<Resolution>()
        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) return existing
        try {
            executor.execute {
                val result = try { walk(id, directory) } catch (_: Exception) { Resolution.UNKNOWN }
                if (result == Resolution.UNRELATED) {
                    if (misses.size >= MAX_SESSIONS) misses.clear()
                    misses[key] = nanoTime() + TimeUnit.MILLISECONDS.toNanos(MISS_TTL_MS)
                }
                inFlight.remove(key, created)
                created.complete(result)
            }
        } catch (failure: Exception) {
            inFlight.remove(key, created)
            created.complete(Resolution.UNKNOWN)
        }
        return created
    }

    private fun walk(id: String, directory: String): Resolution {
        val generation = connection()
        var reads = 0
        fun read(current: String): Boolean {
            if (reads >= MAX_READS) return false
            reads++
            val record = readSession(current) ?: return false
            // Another session's record says nothing about this one.
            if (record.str("id") != current || record.text("directory")?.let(::isDirectory) != true) return false
            if (generation != connection()) return false
            observe(record)
            return true
        }
        if (directoryOf(id) == null && !read(id)) return Resolution.UNKNOWN
        val visited = mutableSetOf<String>()
        var current = id
        val visible = visibleLinks()
        while (true) {
            // A visible link ends the walk; its own verification decides the grant.
            if (current in visible) break
            if (current != id && isOpenRoot(directoryOf(current))) break
            val parent = synchronized(this) { parents[current] }
            if (parent == null) {
                if (!read(current)) return Resolution.UNKNOWN
                continue
            }
            if (parent.isEmpty()) break
            if (!visited.add(current) || visited.size > MAX_DEPTH || parent in visited) return Resolution.UNKNOWN
            current = parent
        }
        // After a reload or reconnect the conversation's verification is gone; re-verify the
        // nearest linked session in this chain, which need not be the top-level conversation.
        linkedOwner(id)?.let { owner ->
            if (owner !in verifiedLinks()) runCatching { verify(owner) }.getOrElse { return Resolution.UNKNOWN }
        }
        if (generation != connection()) return Resolution.UNKNOWN
        return if (isGranted(id, directory)) Resolution.GRANTED else Resolution.UNRELATED
    }

    /** Whether this session or a known ancestor is hidden or recycled. */
    override fun isHiddenLineage(ids: Collection<String>, hidden: (String) -> Boolean): Boolean =
        ids.any { id -> lineage(id).any(hidden) }

    companion object {
        const val MAX_READS = 8
        private const val MAX_DEPTH = 32
        private const val MAX_SESSIONS = 4_096
        private const val MISS_TTL_MS = 60_000L

        /** Catalog and attention events, which are safe to deliver once access is resolved. */
        val REDELIVERED_EVENT_TYPES = setOf(
            "session.created", "session.updated", "session.deleted", "session.status", "session.idle",
            "session.error", "session.archived", "session.unarchived", "session.goal.updated",
            "permission.updated", "permission.asked", "permission.replied", "permission.v2.asked", "permission.v2.replied",
            "question.asked", "question.replied", "question.rejected",
            "question.v2.asked", "question.v2.replied", "question.v2.rejected",
        )

        /** Asks that must still be pending when a deferred delivery runs. */
        val ASK_EVENT_TYPES = setOf("permission.updated", "permission.asked", "permission.v2.asked", "question.asked", "question.v2.asked")

        private fun isSessionId(id: String) = id.length in 1..512 && id.matches(Regex("[A-Za-z0-9_-]+"))
        private fun isDirectory(value: String) = value.isNotBlank() && value.length <= 4096 &&
            value.none { Character.isISOControl(it) } && WorkspacePaths.isAbsolute(value)
    }
}

/**
 * Serializes delivery of server events and holds back those whose session access is still being
 * resolved. Deferred events for a session keep their order, and later catalog or attention events
 * for it wait behind them, so a resolved `session.status` never lands after a newer one.
 */
class GrantedEventRouter<E>(
    private val grants: EventAccess,
    private val isHidden: (String) -> Boolean,
    /** Whether a deferred ask is still awaiting a reply. */
    private val isStillPending: (E) -> Boolean,
    private val deliver: (E) -> Unit,
) {
    private class Deferred<E>(val event: E, val type: String)

    private val lock = Any()
    private val deferred = HashMap<String, ArrayDeque<Deferred<E>>>()

    /** Delivers an event from an open root, or one without a directory. */
    fun deliverOwned(event: E): Unit = synchronized(lock) { deliver(event) }

    /** Routes an event from a directory outside the open roots. */
    fun routeExternal(event: E, type: String, ids: List<String>, directory: String): Unit = synchronized(lock) {
        val key = ids.sorted().joinToString(",") + "\u0000" + WorkspacePaths.normalizeIdentity(directory)
        val waiting = deferred[key]
        if (waiting != null) {
            if (type in SessionGrants.REDELIVERED_EVENT_TYPES) waiting.addLast(Deferred(event, type))
            return
        }
        when (grants.routeEvent(type, ids, directory)) {
            SessionGrants.EventRoute.FORWARD -> if (!grants.isHiddenLineage(ids, isHidden)) deliver(event)
            SessionGrants.EventRoute.REDELIVER -> {
                val queue = ArrayDeque(listOf(Deferred(event, type)))
                deferred[key] = queue
                grants.resolveAsync(ids, directory).whenComplete { result, _ ->
                    synchronized(lock) {
                        if (deferred[key] === queue) deferred.remove(key)
                        if (result != SessionGrants.Resolution.GRANTED) return@synchronized
                        for (item in queue) {
                            // Verification can identify a helper or recycled session the stream
                            // could not suppress, and a reply can resolve an ask meanwhile.
                            if (grants.isHiddenLineage(ids, isHidden)) break
                            if (ids.any { !grants.isGranted(it, directory) }) break
                            if (item.type in SessionGrants.ASK_EVENT_TYPES && !isStillPending(item.event)) continue
                            runCatching { deliver(item.event) }
                        }
                    }
                }
            }
            SessionGrants.EventRoute.RESOLVE -> { grants.resolveAsync(ids, directory) }
            SessionGrants.EventRoute.DROP -> Unit
        }
    }
}

/** Pending permission and question records from a retained conversation's destination. */
object PendingRequests {
    fun id(value: JsonElement?): String? {
        val record = value.asObjectOrNull() ?: return null
        val props = record.obj("properties").obj("info") ?: record.obj("info") ?: record
        return (props.str("id") ?: props.str("permissionID") ?: props.str("requestID"))?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun sessionId(value: JsonElement?): String? {
        val record = value.asObjectOrNull() ?: return null
        return (record.obj("info") ?: record).str("sessionID")
    }

    /**
     * The requests whose sessions hold a grant for `directory`, learning missing ancestry first.
     * Throws when any ancestry is inconclusive rather than omitting its requests.
     */
    fun granted(listed: JsonArray, directory: String, grants: EventAccess, hidden: (String) -> Boolean): List<JsonElement> {
        val sessions = listed.mapNotNull(::sessionId).distinct()
        val unresolved = sessions.filterNot { grants.isGranted(it, directory) }
        if (unresolved.isNotEmpty() && grants.resolve(unresolved, directory) == SessionGrants.Resolution.UNKNOWN) {
            error("Could not resolve pending request session ancestry")
        }
        return listed.filter { value ->
            val session = sessionId(value) ?: return@filter false
            grants.isGranted(session, directory) && !grants.isHiddenLineage(listOf(session), hidden)
        }
    }
}
