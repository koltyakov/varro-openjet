package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions

class SessionLocationsTest {
    private class Fixture {
        var roots = listOf("/origin")
        var connection = 1
        var version = 2
        var clock = 0L
        var project = Json.obj("id" to "project", "worktree" to "/origin", "canonical" to "/origin", "vcs" to "git")
        var projects: JsonElement = Json.array(listOf(project))
        var inventory: JsonElement = Json.array(listOf(Json.obj("directory" to "/destination", "strategy" to "git")))
        var session = Json.obj("id" to "ses_one", "projectID" to "project", "directory" to "/destination",
            "title" to "Fixture", "version" to "2", "time" to Json.obj("created" to 1, "updated" to 2))
        val calls = mutableListOf<String>()
        var duringRead: (String) -> Unit = {}
        val locations = SessionLocations(::request, { version }, { connection }, { roots }, { clock })
        fun request(method: String, path: String, body: JsonElement?, options: RequestOptions): OpenCodeResponse {
            assertEquals("GET", method)
            assertNull(body)
            assertTrue(options.directory in roots)
            assertEquals(5_000L, options.timeoutMs)
            assertTrue(options.cancellableBody)
            calls += path
            duringRead(path)
            return OpenCodeResponse(when (path) {
                "/project/current" -> project.deepCopy()
                "/project" -> projects.deepCopy()
                "/experimental/worktree?projectID=project" -> inventory.deepCopy()
                "/session/ses_one" -> session.deepCopy()
                else -> error("Unexpected request $path")
            })
        }
    }

    @Test fun `known moved worktree is writable after canonical verification without loading its config`() {
        val f = Fixture()
        assertTrue(f.locations.authorizeTransferred(f.session))
        assertEquals(listOf("/project/current", "/project", "/experimental/worktree?projectID=project", "/session/ses_one"), f.calls)
        val transfers = SessionTransfers(JsonArray(), {}, { "fixture" }, { f.roots }, { f.session }, {},
            authorizeWorktree = f.locations::authorizeTransferred, knownWorktree = f.locations::knownTransferred)
        transfers.observe(f.session.deepCopy().apply { addProperty("directory", "/origin") })
        transfers.observe(f.session)
        assertEquals(true, transfers.get("ses_one").obj("transfer").bool("available"))
        val reads = f.calls.size
        assertEquals(true, transfers.list().single().obj("transfer").bool("available"))
        assertEquals(reads, f.calls.size)
        transfers.checkWritable("ses_one")
        assertEquals("/destination", transfers.authorizeRequest("POST", "/session/ses_one/prompt_async?directory=/origin"))
        assertThrows(IllegalArgumentException::class.java) {
            transfers.authorizeRequest("POST", "/session/ses_one/prompt_async?directory=/unrelated")
        }
        f.inventory = JsonArray()
        assertEquals(false, transfers.get("ses_one").obj("transfer").bool("available"))
        assertThrows(IllegalStateException::class.java) { transfers.checkWritable("ses_one") }
        assertEquals("/destination", transfers.authorizeRequest("GET", "/varro/session/ses_one/location"))
    }

    @Test fun `canonical reads can observe the same move without recursively publishing it`() {
        val f = Fixture()
        val events = mutableListOf<JsonElement>()
        val transfers = SessionTransfers(JsonArray(), {}, { "fixture" }, { f.roots }, { f.session }, { events += it },
            authorizeWorktree = f.locations::authorizeTransferred)
        f.duringRead = { path -> if (path == "/session/ses_one") transfers.observe(f.session) }
        transfers.observe(f.session.deepCopy().apply { addProperty("directory", "/origin") })
        transfers.observe(f.session)
        assertEquals(1, events.size)
        assertEquals(true, transfers.get("ses_one").obj("transfer").bool("available"))
    }

    @Test fun `missing unrelated non-Git and malformed inventories never grant execution`() {
        val variations: List<(Fixture) -> Unit> = listOf(
            { it.projects = Json.obj("data" to it.projects) },
            { it.projects = Json.array(listOf(Json.obj("id" to "other", "vcs" to "git"))) },
            { it.projects = Json.array(listOf(Json.obj("id" to "project", "vcs" to "hg"))) },
            { it.inventory = Json.obj("data" to it.inventory) },
            { it.inventory = JsonArray() },
            { it.inventory = Json.array(listOf(Json.obj("directory" to "/unrelated", "strategy" to "git"))) },
            { it.inventory = Json.array(listOf(Json.obj("directory" to "/destination", "strategy" to "copy"))) },
            { it.inventory = Json.array(listOf(Json.obj("directory" to "/destination", "strategy" to 123))) },
            { it.inventory = Json.array(listOf(Json.obj("directory" to "/destination", "strategy" to null))) },
            { it.project.addProperty("id", "other") },
            { it.session.addProperty("projectID", "other") },
            { it.session.addProperty("directory", "/moved-again") },
            { it.session.addProperty("id", "ses_other") },
        )
        variations.forEach { change ->
            val f = Fixture()
            val retained = f.session.deepCopy()
            change(f)
            assertFalse(f.calls.toString(), f.locations.authorizeTransferred(retained))
        }
        val f = Fixture()
        assertFalse(f.locations.authorizeTransferred(f.session.deepCopy().apply { addProperty("id", "../escape") }))
        assertFalse(f.locations.authorizeTransferred(f.session.deepCopy().apply { addProperty("projectID", "global") }))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `connection root changes and deadlines revoke in-flight authorization`() {
        for (change in listOf<(Fixture) -> Unit>({ it.connection++ }, { it.roots = emptyList() }, { it.clock = 6_000_000_000 })) {
            val f = Fixture()
            f.duringRead = { path -> if (path == "/session/ses_one") change(f) }
            assertFalse(f.locations.authorizeTransferred(f.session.deepCopy()))
        }
    }

    @Test fun `presentation inventory is shared per project expires and never grants authorization`() {
        val f = Fixture()
        val rows = Json.array((1..100).map { f.session.deepCopy().apply { addProperty("id", "ses_$it") } } +
            f.session.deepCopy().apply { addProperty("directory", "/origin") })
        val annotated = f.locations.annotate(rows) { false }
        assertEquals(100, annotated.count { it.asJsonObject.bool("isGitWorktree") == true })
        assertEquals(false, annotated.last().asJsonObject.bool("isGitWorktree"))
        assertEquals(1, f.calls.size)
        f.locations.annotate(rows) { false }
        assertEquals(1, f.calls.size)
        f.inventory = JsonArray()
        assertFalse(f.locations.authorizeTransferred(f.session))
        f.clock = 6_000_000_000
        assertTrue(f.locations.annotate(rows) { false }.all { it.asJsonObject.bool("isGitWorktree") == false })
        f.connection++
        f.locations.annotate(rows) { false }
        assertEquals(4, f.calls.count { it.startsWith("/experimental/worktree") })
        assertFalse(rows.first().asJsonObject.has("isGitWorktree"))
    }

    @Test fun `canonical location supplies merge target and rejects stale hidden unknown and foreign sessions`() {
        val f = Fixture()
        assertEquals(Json.obj("directory" to "/destination", "isGitWorktree" to true, "projectDirectory" to "/origin"),
            f.locations.location("ses_one", "/destination", f.session, "directory"))
        assertThrows(IllegalArgumentException::class.java) { f.locations.location("ses_one", "/old", f.session, "directory") }
        assertThrows(IllegalArgumentException::class.java) { f.locations.location("ses_one", null, null, "directory") }
        assertThrows(IllegalArgumentException::class.java) { f.locations.location("ses_one", null, f.session, "directory", hidden = true) }
        assertThrows(IllegalArgumentException::class.java) { f.locations.location("../escape", null, f.session, "directory") }
        f.roots = listOf("/destination")
        assertEquals("/origin", f.locations.location("ses_one", "/destination", null, "directory").str("projectDirectory"))
    }

    @Test fun `V1 saved string inventory authorizes only server-confirmed Git project and marks its worktree`() {
        val f = Fixture()
        f.version = 1
        f.inventory = Json.array(listOf("/destination"))
        assertTrue(f.locations.authorizeTransferred(f.session))
        assertEquals(true, f.locations.location("ses_one", "/destination", f.session, "directory").bool("isGitWorktree"))
        val rows = Json.array(listOf(f.session))
        assertSame(rows, f.locations.annotate(rows) { false })
    }

    @Test fun `metadata failure is nonfatal but cancellation propagates and retries are not cached`() {
        val f = Fixture()
        f.inventory = Json.obj("data" to JsonArray())
        assertFalse(f.locations.location("ses_one", null, f.session, "directory").bool("isGitWorktree")!!)
        assertFalse(f.locations.annotate(Json.array(listOf(f.session))) { false }.first().asJsonObject.has("isGitWorktree"))
        f.inventory = Json.array(listOf(Json.obj("directory" to "/destination", "strategy" to "git")))
        assertEquals(true, f.locations.annotate(Json.array(listOf(f.session))) { false }.first().asJsonObject.bool("isGitWorktree"))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            f.locations.location("ses_one", null, f.session, "directory", cancelled = { true })
        }
    }
}
