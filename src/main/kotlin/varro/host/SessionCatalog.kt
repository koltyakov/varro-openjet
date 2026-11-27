package varro.host

import com.google.gson.JsonObject
import varro.host.SessionGrants.EventRoute
import varro.host.SessionGrants.Resolution
import varro.protocol.str
import varro.protocol.text
import varro.server.WorkspacePaths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The sessions this project's history catalog owns outside its exact open roots. Port of upstream
 * `rest-proxy.ts` `resolveSessionCatalogScope` and `isSessionInCatalogScope`, with catalog-root
 * routing for events, pending requests and replies.
 *
 * - `directory`: exact roots only, as before.
 * - `descendants`: sessions in folders nested inside an open root.
 * - `project`: for a Git root, every session of its OpenCode project, including project
 *   worktrees outside the root, plus nested folders. A non-Git root falls back to `descendants`.
 *
 * Several IDE windows can admit the same session, so [CatalogOwners] gives each session to one
 * window: the most specific claim wins, an exact root above a containing root above a project
 * match, and ties go to the window with the lowest root identity. Claims never perform I/O. A
 * root's project identity is loaded off the event thread, and an unknown session project is read
 * once from its canonical record.
 */
class SessionCatalog(
    /** This window's identity for deterministic ties, its normalized base path. */
    val key: String,
    private val roots: () -> List<String>,
    private val scope: () -> String,
    /** `/project/current` for a root; may read the server. */
    private val readProject: (String) -> JsonObject?,
    /** A canonical, unscoped session record. */
    private val readSession: (String) -> JsonObject?,
    private val connection: () -> Any,
    private val executor: Executor,
    private val lineage: (String) -> List<String> = { listOf(it) },
    private val owners: CatalogOwners = CatalogOwners.shared,
    private val nanoTime: () -> Long = System::nanoTime,
) : EventAccess {
    sealed interface RootScope { val root: String }
    data class Exact(override val root: String) : RootScope
    data class Descendants(override val root: String) : RootScope
    data class Project(override val root: String, val projectID: String) : RootScope

    private class CachedProject(val connection: Any, val projectID: String?, val expires: Long)

    private val projects = ConcurrentHashMap<String, CachedProject>()
    private val projectLoads = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    /** Failed project loads back off briefly; their roots stay undecided rather than non-Git. */
    private val projectFailures = ConcurrentHashMap<String, Long>()
    /** Known sessions: id to (directory, projectID). Bounded, oldest first. */
    private val sessions = object : LinkedHashMap<String, Pair<String, String?>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String?>>?) = size > MAX_SESSIONS
    }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Resolution>>()
    private val misses = ConcurrentHashMap<String, Long>()

    /** Learns a session's location and project from a record that names its directory. */
    fun observe(session: JsonObject?) {
        val id = session.text("id")?.takeIf(::isSessionId) ?: return
        val directory = session.text("directory")?.takeIf(::isDirectory) ?: return
        val projectID = session.text("projectID")
        synchronized(this) {
            val previous = sessions[id]
            sessions[id] = directory to (projectID ?: previous?.second?.takeIf { WorkspacePaths.isSame(previous.first, directory) })
        }
    }

    @Synchronized fun forget(id: String) { sessions.remove(id) }

    /** Drops remembered misses, as after a history-scope change. */
    fun invalidate() {
        misses.clear()
        projects.clear()
        projectFailures.clear()
    }

    private fun projectOf(id: String): String? = synchronized(this) { sessions[id]?.second }

    /** Each open root's scope without I/O; a Git project not yet loaded counts as `descendants` meanwhile. */
    fun scopes(): List<RootScope> {
        val mode = scope()
        return roots().map { root ->
            when (mode) {
                "descendants" -> Descendants(root)
                "project" -> cachedProject(root)?.let { Project(root, it) } ?: Descendants(root)
                else -> Exact(root)
            }
        }
    }

    private fun cachedProject(root: String): String? {
        val cached = projects[WorkspacePaths.normalizeIdentity(root) ?: return null] ?: return null
        return cached.projectID?.takeIf { cached.connection == connection() && nanoTime() - cached.expires < 0 }
    }

    private fun needsProject(root: String): Boolean {
        if (scope() != "project") return false
        val cached = projects[WorkspacePaths.normalizeIdentity(root) ?: return false] ?: return true
        return cached.connection != connection() || nanoTime() - cached.expires >= 0
    }

    /** Loads every root's project identity the `project` scope still needs. Blocking. */
    fun loadProjects() {
        roots().filter(::needsProject).forEach { root ->
            val identity = WorkspacePaths.normalizeIdentity(root) ?: return@forEach
            projectFailures[identity]?.let { until -> if (nanoTime() - until < 0) return@forEach else projectFailures.remove(identity, until) }
            val created = CompletableFuture<Unit>()
            val running = projectLoads.putIfAbsent(identity, created)
            if (running != null) { runCatching { running.get(10, TimeUnit.SECONDS) }; return@forEach }
            try {
                val generation = connection()
                // A missing or malformed record is a failed load, not a non-Git project.
                val project = readProject(root) ?: error("Missing current project")
                val projectID = project.text("id") ?: error("Malformed current project")
                val id = projectID.takeIf { project.str("vcs") == "git" && it != "global" }
                if (generation == connection()) {
                    projects[identity] = CachedProject(generation, id, nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROJECT_TTL_MS))
                }
            } catch (_: Exception) {
                // An unknown project keeps the provisional `descendants` scope; a later load retries.
                projectFailures[identity] = nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROJECT_FAILURE_BACKOFF_MS)
            } finally {
                projectLoads.remove(identity, created)
                created.complete(Unit)
            }
        }
    }

    private fun scheduleProjectLoad() {
        if (roots().any(::needsProject)) runCatching { executor.execute { loadProjects() } }
    }

    /**
     * How strongly this window's catalog claims a session in `directory`: an exact root, a root
     * containing it (deeper roots are more specific), or only a project match. `null` when the
     * catalog does not list it.
     */
    fun claim(directory: String, projectID: String?): Int? = scopes().mapNotNull { scope ->
        val within = WorkspacePaths.relativeWithin(directory, scope.root)
        when {
            WorkspacePaths.isSame(directory, scope.root) -> EXACT_CLAIM
            scope is Exact -> null
            within != null -> depth(scope.root)
            scope is Project && projectID != null && projectID == scope.projectID -> PROJECT_CLAIM
            else -> null
        }
    }.maxOrNull()

    /** Whether this window owns a session in `directory` among every open window's catalog. */
    fun owns(directory: String, projectID: String?): Boolean {
        val mine = claim(directory, projectID) ?: return false
        return owners.isOwner(this, mine, directory, projectID)
    }

    /** Whether a known session, by its own record, belongs to this window's catalog. */
    fun ownsSession(id: String): Boolean {
        val (directory, projectID) = synchronized(this) { sessions[id] } ?: return false
        return owns(directory, projectID)
    }

    /** A known session's recorded directory. */
    fun knownDirectory(id: String): String? = synchronized(this) { sessions[id]?.first }

    /** A known, owned session's directory when it is not an open root itself. */
    fun ownedDirectory(id: String): String? {
        val (directory, projectID) = synchronized(this) { sessions[id] } ?: return null
        if (roots().any { WorkspacePaths.isSame(it, directory) }) return null
        return directory.takeIf { owns(it, projectID) }
    }

    /** Distinct directories of known owned sessions outside the open roots, newest first, bounded. */
    fun ownedDirectories(limit: Int = MAX_PENDING_DIRECTORIES): List<String> {
        val known = synchronized(this) { sessions.values.toList() }.asReversed()
        val roots = roots()
        return known.asSequence()
            .filter { (directory, _) -> roots.none { WorkspacePaths.isSame(it, directory) } }
            .filter { (directory, projectID) -> owns(directory, projectID) }
            .map { it.first }
            .distinctBy(WorkspacePaths::normalizeIdentity)
            .take(limit)
            .toList()
    }

    /** Whether a listed session record belongs to this window's catalog, beyond its exact roots. */
    fun admitsListed(session: JsonObject): Boolean {
        val directory = session.text("directory") ?: return false
        return owns(directory, session.text("projectID"))
    }

    /** Whether any root's scope can list sessions beyond the exact roots. */
    fun isScoped(): Boolean = scope() in setOf("descendants", "project") && roots().isNotEmpty()

    private fun couldClaim(directory: String): Boolean = scopes().any { scope ->
        scope !is Exact && (WorkspacePaths.relativeWithin(directory, scope.root) != null || scope is Project)
    } || roots().any(::needsProject)

    override fun isGranted(id: String, directory: String?): Boolean =
        directory != null && owns(directory, projectOf(id))

    override fun routeEvent(type: String, ids: Collection<String>, directory: String): EventRoute {
        if (ids.isEmpty() || !isScoped()) return EventRoute.DROP
        scheduleProjectLoad()
        if (ids.all { isGranted(it, directory) }) return EventRoute.FORWARD
        if (!couldClaim(directory)) return EventRoute.DROP
        // Containment is decided by the directory alone; only a project match needs the session's record.
        val decidable = ids.all { id -> projectOf(id) != null || claim(directory, null) != null } && roots().none(::needsProject)
        if (decidable) return EventRoute.DROP
        return if (type in SessionGrants.REDELIVERED_EVENT_TYPES) EventRoute.REDELIVER else EventRoute.RESOLVE
    }

    override fun resolveAsync(ids: Collection<String>, directory: String): CompletableFuture<Resolution> {
        if (ids.isEmpty() || !isScoped()) return CompletableFuture.completedFuture(Resolution.UNRELATED)
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
        inFlight.putIfAbsent(key, created)?.let { return it }
        try {
            executor.execute {
                val result = try { resolveNow(id, directory) } catch (_: Exception) { Resolution.UNKNOWN }
                if (result == Resolution.UNRELATED) {
                    if (misses.size >= MAX_SESSIONS) misses.clear()
                    misses[key] = nanoTime() + TimeUnit.MILLISECONDS.toNanos(MISS_TTL_MS)
                }
                inFlight.remove(key, created)
                created.complete(result)
            }
        } catch (_: Exception) {
            inFlight.remove(key, created)
            created.complete(Resolution.UNKNOWN)
        }
        return created
    }

    private fun resolveNow(id: String, directory: String): Resolution {
        val generation = connection()
        loadProjects()
        if (roots().any(::needsProject)) return Resolution.UNKNOWN
        if (isGranted(id, directory)) return Resolution.GRANTED
        if (projectOf(id) == null && claim(directory, null) == null) {
            val record = readSession(id) ?: return Resolution.UNKNOWN
            // Another session's record says nothing about this one.
            if (record.str("id") != id || record.text("directory")?.let(::isDirectory) != true) return Resolution.UNKNOWN
            if (generation != connection()) return Resolution.UNKNOWN
            observe(record)
            // The record is canonical: a session now elsewhere is judged by the event's directory and its project.
            if (projectOf(id) == null) return Resolution.UNRELATED
        }
        if (generation != connection()) return Resolution.UNKNOWN
        return if (isGranted(id, directory)) Resolution.GRANTED else Resolution.UNRELATED
    }

    override fun isHiddenLineage(ids: Collection<String>, hidden: (String) -> Boolean): Boolean =
        ids.any { id -> lineage(id).any(hidden) }

    companion object {
        /**
         * `/project/current` for a root. V2 records omit `vcs`, which only the project list carries,
         * so it is completed from there; without it a Git project would read as a plain folder.
         */
        fun currentProject(read: (path: String, directory: String) -> com.google.gson.JsonElement?, root: String): JsonObject? {
            val current = read("/project/current", root)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (current.has("vcs")) return current
            val id = current.text("id") ?: return current
            val listed = read("/project", root)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull { it.isJsonObject && it.asJsonObject.str("id") == id }?.asJsonObject
            return current.deepCopy().apply { listed?.str("vcs")?.let { addProperty("vcs", it) } }
        }

        const val EXACT_CLAIM = Int.MAX_VALUE
        const val PROJECT_CLAIM = 0
        private const val MAX_SESSIONS = 4_096
        private const val MAX_PENDING_DIRECTORIES = 64
        private const val MISS_TTL_MS = 60_000L
        private const val PROJECT_TTL_MS = 5 * 60_000L
        private const val PROJECT_FAILURE_BACKOFF_MS = 10_000L

        /** Path segments of a root: a deeper root is a more specific claim than one containing it. */
        private fun depth(root: String): Int =
            (WorkspacePaths.normalizeIdentity(root) ?: root).split('/', '\\').count { it.isNotEmpty() } + 1

        private fun isSessionId(id: String) = id.length in 1..512 && id.matches(Regex("[A-Za-z0-9_-]+"))
        private fun isDirectory(value: String) = value.isNotBlank() && value.length <= 4096 &&
            value.none { Character.isISOControl(it) } && WorkspacePaths.isAbsolute(value)
    }
}

/** Every open window's session catalog, so each catalog session is delivered to exactly one window. */
class CatalogOwners {
    private val catalogs = CopyOnWriteArrayList<SessionCatalog>()

    fun register(catalog: SessionCatalog): () -> Unit {
        catalogs.addIfAbsent(catalog)
        return { catalogs.remove(catalog) }
    }

    fun isOwner(catalog: SessionCatalog, claim: Int, directory: String, projectID: String?): Boolean =
        catalogs.none { other ->
            if (other === catalog) return@none false
            val theirs = runCatching { other.claim(directory, projectID) }.getOrNull() ?: return@none false
            theirs > claim || theirs == claim && other.key < catalog.key
        }

    companion object {
        /** One registry per IDE process, shared by every project window. */
        val shared = CatalogOwners()
    }
}

/**
 * Transfer grants and catalog ownership together: a session is routed when either grants it.
 * A moved conversation's link belongs to its origin window only, so grants need no arbitration.
 */
class EventAccesses(private val grants: SessionGrants, private val catalog: SessionCatalog) : EventAccess {
    override fun routeEvent(type: String, ids: Collection<String>, directory: String): EventRoute {
        val routes = listOf(grants.routeEvent(type, ids, directory), catalog.routeEvent(type, ids, directory))
        return when {
            ids.isNotEmpty() && ids.all { isGranted(it, directory) } -> EventRoute.FORWARD
            EventRoute.REDELIVER in routes -> EventRoute.REDELIVER
            EventRoute.RESOLVE in routes -> EventRoute.RESOLVE
            else -> EventRoute.DROP
        }
    }

    override fun isGranted(id: String, directory: String?): Boolean = grants.isGranted(id, directory) || catalog.isGranted(id, directory)

    override fun resolveAsync(ids: Collection<String>, directory: String): CompletableFuture<Resolution> {
        val walks = ids.distinct().map { id ->
            val first = grants.resolveAsync(listOf(id), directory)
            val second = catalog.resolveAsync(listOf(id), directory)
            first.thenCombine(second) { a, b ->
                when {
                    a == Resolution.GRANTED || b == Resolution.GRANTED -> Resolution.GRANTED
                    a == Resolution.UNKNOWN || b == Resolution.UNKNOWN -> Resolution.UNKNOWN
                    else -> Resolution.UNRELATED
                }
            }
        }
        if (walks.isEmpty()) return CompletableFuture.completedFuture(Resolution.UNRELATED)
        return CompletableFuture.allOf(*walks.toTypedArray()).thenApply {
            val results = walks.map { it.join() }
            when {
                results.all { it == Resolution.GRANTED } -> Resolution.GRANTED
                results.any { it == Resolution.UNKNOWN } -> Resolution.UNKNOWN
                else -> Resolution.UNRELATED
            }
        }
    }

    override fun isHiddenLineage(ids: Collection<String>, hidden: (String) -> Boolean): Boolean = grants.isHiddenLineage(ids, hidden)
}

/**
 * The host's routing of one parsed server event: session records teach ancestry and projects
 * first, so a subagent's first event already inherits; events from an open root are forwarded;
 * others go through [GrantedEventRouter] with retained-transfer and catalog access.
 */
class HostEventRouting<E>(
    private val roots: () -> List<String>,
    private val grants: SessionGrants,
    private val catalog: SessionCatalog,
    private val router: GrantedEventRouter<E>,
) {
    fun route(event: E, type: String, properties: JsonObject?, directory: String?, sessionId: String?) {
        if (type == "session.created" || type == "session.updated") {
            val info = properties?.get("info")?.takeIf { it.isJsonObject }?.asJsonObject
            grants.observe(info)
            catalog.observe(info)
        }
        if (directory != null && roots().none { WorkspacePaths.isSame(directory, it) }) {
            router.routeExternal(event, type, listOfNotNull(sessionId), directory)
        } else {
            router.deliverOwned(event)
        }
    }
}
