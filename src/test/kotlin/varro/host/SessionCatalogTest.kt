package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.host.SessionGrants.EventRoute
import varro.host.SessionGrants.Resolution
import varro.protocol.Json
import varro.protocol.str
import java.util.concurrent.Executor

class SessionCatalogTest {
    private class Window(
        val owners: CatalogOwners,
        root: String,
        var scope: String,
        val project: JsonObject? = Json.obj("id" to "prj_repo", "vcs" to "git"),
        val records: MutableMap<String, JsonObject> = mutableMapOf(),
    ) {
        var roots = listOf(root)
        val reads = mutableListOf<String>()
        val projectReads = mutableListOf<String>()
        var readFailure = false
        var manual = false
        val tasks = ArrayDeque<Runnable>()
        var clock = 0L
        val catalog = SessionCatalog(
            key = root,
            roots = { roots },
            scope = { scope },
            readProject = { projectReads += it; project },
            readSession = { id -> reads += id; if (readFailure) error("Session read failed"); records[id]?.deepCopy() },
            connection = { 1 },
            executor = Executor { if (manual) tasks.addLast(it) else it.run() },
            owners = owners,
            nanoTime = { clock },
        ).also { owners.register(it) }

        fun runTasks() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private fun record(id: String, directory: String, projectID: String? = "prj_repo") =
        Json.obj("id" to id, "directory" to directory).apply { projectID?.let { addProperty("projectID", it) } }

    @Test fun `each history scope admits only its own sessions`() {
        val owners = CatalogOwners()
        val window = Window(owners, "/repo", "directory")
        assertNull(window.catalog.claim("/repo/nested", "prj_repo"))
        assertEquals(SessionCatalog.EXACT_CLAIM, window.catalog.claim("/repo", null))
        assertFalse(window.catalog.isScoped())

        window.scope = "descendants"
        assertNotNull(window.catalog.claim("/repo/nested/deeper", null))
        assertNull(window.catalog.claim("/repo-sibling", "prj_repo"))
        assertNull(window.catalog.claim("/worktrees/feature", "prj_repo"))

        // The project scope needs the root's project identity; until it loads, nested folders still count.
        window.scope = "project"
        assertNull(window.catalog.claim("/worktrees/feature", "prj_repo"))
        assertNotNull(window.catalog.claim("/repo/nested", null))
        window.catalog.loadProjects()
        assertEquals(SessionCatalog.PROJECT_CLAIM, window.catalog.claim("/worktrees/feature", "prj_repo"))
        assertNull(window.catalog.claim("/worktrees/other", "prj_other"))
        assertNull(window.catalog.claim("/worktrees/feature", null))
        window.catalog.loadProjects()
        assertEquals(listOf("/repo"), window.projectReads)

        // Non-Git and global projects fall back to nested folders.
        val plain = Window(CatalogOwners(), "/plain", "project", project = Json.obj("id" to "global", "vcs" to "git"))
        plain.catalog.loadProjects()
        assertNull(plain.catalog.claim("/elsewhere", "global"))
        assertNotNull(plain.catalog.claim("/plain/nested", null))
    }

    @Test fun `V2 project records gain vcs from the project list`() {
        val reads = mutableListOf<String>()
        fun read(current: JsonObject?, list: com.google.gson.JsonElement?) = { path: String, directory: String ->
            reads += "$path@$directory"
            if (path == "/project/current") current else list
        }
        val v2 = SessionCatalog.currentProject(read(Json.obj("id" to "prj_repo", "worktree" to "/repo"),
            Json.array(listOf(Json.obj("id" to "prj_other", "vcs" to "git"), Json.obj("id" to "prj_repo", "vcs" to "git")))), "/repo")
        assertEquals("git", v2.str("vcs"))
        assertEquals(listOf("/project/current@/repo", "/project@/repo"), reads)
        reads.clear()
        // V1 records already carry it, and a project absent from the list stays a plain folder.
        assertEquals("git", SessionCatalog.currentProject(read(Json.obj("id" to "prj_repo", "vcs" to "git"), null), "/repo").str("vcs"))
        assertEquals(listOf("/project/current@/repo"), reads)
        assertNull(SessionCatalog.currentProject(read(Json.obj("id" to "prj_repo"), Json.array(emptyList<Any>())), "/repo").str("vcs"))
        assertNull(SessionCatalog.currentProject(read(null, null), "/repo"))
    }

    @Test fun `each catalog session belongs to exactly one open window`() {
        val owners = CatalogOwners()
        val repo = Window(owners, "/repo", "project").apply { catalog.loadProjects() }
        val nested = Window(owners, "/repo/packages/app", "directory")
        val pkg = Window(owners, "/repo/packages", "descendants")
        val twin = Window(owners, "/repo-twin", "project").apply { catalog.loadProjects() }

        // A window with the folder open exactly owns it, even when its own scope is `directory`.
        assertFalse(repo.catalog.owns("/repo/packages/app", "prj_repo"))
        assertFalse(pkg.catalog.owns("/repo/packages/app", "prj_repo"))
        // Below that folder, the most specific containing root wins.
        assertTrue(pkg.catalog.owns("/repo/packages/app/src", "prj_repo"))
        assertFalse(repo.catalog.owns("/repo/packages/app/src", "prj_repo"))
        assertTrue(repo.catalog.owns("/repo/docs", "prj_repo"))
        assertFalse(twin.catalog.owns("/repo/docs", "prj_repo"))
        // Two windows of the same project tie on a worktree outside both; the lowest root identity wins.
        assertTrue(repo.catalog.owns("/worktrees/feature", "prj_repo"))
        assertFalse(twin.catalog.owns("/worktrees/feature", "prj_repo"))

        // Closing a window hands its sessions to the next claim.
        val unregister = owners.register(pkg.catalog)
        unregister()
        assertTrue(repo.catalog.owns("/repo/packages/app/src", "prj_repo"))
        owners.register(pkg.catalog)
    }

    @Test fun `nested sessions route by directory alone, and other folders drop without reads`() {
        val owners = CatalogOwners()
        val window = Window(owners, "/repo", "descendants")
        assertEquals(EventRoute.FORWARD, window.catalog.routeEvent("session.next.text.delta", listOf("ses_nested"), "/repo/nested"))
        assertEquals(EventRoute.FORWARD, window.catalog.routeEvent("permission.asked", listOf("ses_nested"), "/repo/nested"))
        assertEquals(EventRoute.DROP, window.catalog.routeEvent("permission.asked", listOf("ses_other"), "/elsewhere"))
        assertEquals(EventRoute.DROP, window.catalog.routeEvent("permission.asked", emptyList(), "/repo/nested"))
        window.scope = "directory"
        assertEquals(EventRoute.DROP, window.catalog.routeEvent("permission.asked", listOf("ses_nested"), "/repo/nested"))
        assertTrue(window.reads.isEmpty())
    }

    @Test fun `worktree sessions learn their project once, and other projects are remembered briefly`() {
        val owners = CatalogOwners()
        val window = Window(owners, "/repo", "project")
        window.records["ses_tree"] = record("ses_tree", "/worktrees/feature")
        window.records["ses_foreign"] = record("ses_foreign", "/worktrees/other", "prj_other")
        // The root's project is not loaded yet: the event waits for it.
        assertEquals(EventRoute.REDELIVER, window.catalog.routeEvent("permission.asked", listOf("ses_tree"), "/worktrees/feature"))
        assertEquals(EventRoute.RESOLVE, window.catalog.routeEvent("session.next.text.delta", listOf("ses_tree"), "/worktrees/feature"))
        assertEquals(Resolution.GRANTED, window.catalog.resolve(listOf("ses_tree"), "/worktrees/feature"))
        assertEquals(listOf("ses_tree"), window.reads)
        assertEquals(EventRoute.FORWARD, window.catalog.routeEvent("session.next.text.delta", listOf("ses_tree"), "/worktrees/feature"))
        assertEquals("/worktrees/feature", window.catalog.ownedDirectory("ses_tree"))

        assertEquals(Resolution.UNRELATED, window.catalog.resolve(listOf("ses_foreign"), "/worktrees/other"))
        assertEquals(Resolution.UNRELATED, window.catalog.resolve(listOf("ses_foreign"), "/worktrees/other"))
        assertEquals(listOf("ses_tree", "ses_foreign"), window.reads)
        // A session with a known foreign project is decided without another read.
        assertEquals(EventRoute.DROP, window.catalog.routeEvent("permission.asked", listOf("ses_foreign"), "/worktrees/other"))
        assertNull(window.catalog.ownedDirectory("ses_foreign"))
    }

    @Test fun `inconclusive catalog resolutions never read as unrelated`() {
        val window = Window(CatalogOwners(), "/repo", "project")
        window.catalog.loadProjects()
        window.readFailure = true
        assertEquals(Resolution.UNKNOWN, window.catalog.resolve(listOf("ses_tree"), "/worktrees/feature"))
        window.readFailure = false
        window.records["ses_tree"] = record("ses_other", "/worktrees/feature")
        assertEquals(Resolution.UNKNOWN, window.catalog.resolve(listOf("ses_tree"), "/worktrees/feature"))
        window.records["ses_tree"] = record("ses_tree", "/worktrees/feature")
        assertEquals(Resolution.GRANTED, window.catalog.resolve(listOf("ses_tree"), "/worktrees/feature"))
        // A failed project load keeps the snapshot inconclusive rather than unrelated.
        val failing = Window(CatalogOwners(), "/repo", "project", project = null)
        failing.records["ses_tree"] = record("ses_tree", "/worktrees/feature")
        assertEquals(Resolution.UNKNOWN, failing.catalog.resolve(listOf("ses_tree"), "/worktrees/feature"))
        // Failed loads back off instead of reading the project for every event.
        assertEquals(Resolution.UNKNOWN, failing.catalog.resolve(listOf("ses_tree2"), "/worktrees/feature"))
        assertEquals(1, failing.projectReads.size)
        failing.clock += 11_000_000_000L
        failing.catalog.loadProjects()
        assertEquals(2, failing.projectReads.size)
    }

    @Test fun `owned directories feed pending snapshots and exclude roots and other windows`() {
        val owners = CatalogOwners()
        val window = Window(owners, "/repo", "project").apply { catalog.loadProjects() }
        val other = Window(owners, "/repo/apps", "directory")
        for (session in listOf(record("ses_root", "/repo"), record("ses_nested", "/repo/nested"), record("ses_tree", "/worktrees/feature"),
            record("ses_apps", "/repo/apps"), record("ses_foreign", "/elsewhere", "prj_other"), record("ses_tree2", "/worktrees/feature"))) {
            window.catalog.observe(session)
        }
        assertEquals(listOf("/worktrees/feature", "/repo/nested"), window.catalog.ownedDirectories())
        assertEquals(listOf("/worktrees/feature"), window.catalog.ownedDirectories(limit = 1))
        assertNull(window.catalog.ownedDirectory("ses_root"))
        assertNull(window.catalog.ownedDirectory("ses_apps"))
        assertTrue(other.catalog.ownedDirectories().isEmpty())
        assertTrue(window.catalog.admitsListed(record("ses_new", "/worktrees/next")))
        assertFalse(window.catalog.admitsListed(record("ses_apps2", "/repo/apps")))

        fun request(id: String, session: String) = Json.obj("id" to id, "sessionID" to session)
        val kept = PendingRequests.granted(Json.array(listOf(request("per_tree", "ses_tree"), request("per_foreign", "ses_foreign"))),
            "/worktrees/feature", window.catalog) { false }
        assertEquals(listOf("per_tree"), kept.map { PendingRequests.id(it) })
    }

    @Test fun `routed worktree prompts reach only the owning window, after their project resolves`() {
        val owners = CatalogOwners()
        val records = mutableMapOf("ses_tree" to record("ses_tree", "/worktrees/feature"))
        val first = Window(owners, "/repo", "project", records = records).apply { manual = true }
        val second = Window(owners, "/repo-twin", "project", records = records).apply { manual = true }
        val delivered = mutableMapOf<String, MutableList<String>>()
        fun router(window: Window, name: String): GrantedEventRouter<String> {
            val grants = SessionGrants({ window.roots }, { emptyList() }, { false }, { null }, { null }, { 1 }, Executor { it.run() })
            return GrantedEventRouter(EventAccesses(grants, window.catalog), { false }, { true }) { delivered.getOrPut(name) { mutableListOf() } += it }
        }
        val routers = listOf(router(first, "first"), router(second, "second"))
        routers.forEach { it.routeExternal("ask", "permission.asked", listOf("ses_tree"), "/worktrees/feature") }
        first.runTasks(); second.runTasks()
        // Both windows now know the project; ties go to the lowest root identity.
        routers.forEach { it.routeExternal("status", "session.status", listOf("ses_tree"), "/worktrees/feature") }
        first.runTasks(); second.runTasks()
        assertEquals(mapOf("first" to mutableListOf("ask", "status")), delivered)
    }
}
