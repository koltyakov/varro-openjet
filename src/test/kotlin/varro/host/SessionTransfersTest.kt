package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.store.VarroStore

class SessionTransfersTest {
    private class Fixture {
        var server = "http://fixture.local:4096"
        var roots = listOf("/origin")
        var session: JsonObject = summary("/origin")
        var saved = JsonArray()
        var writes = 0
        var failPersistence = false
        val events = mutableListOf<JsonObject>()
        var reads = 0
        var invalidations = 0
        fun service(initial: JsonArray = saved) = SessionTransfers(initial, {
            if (failPersistence) error("Persistence failed")
            saved = it.deepCopy(); writes++
        }, { server }, { roots }, { id -> assertEquals("ses_one", id); reads++; session.deepCopy() }, { events.add(it) }, { invalidations++ })
    }

    @Test fun `verified move retains a read-only link and blocks execution after reload`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        assertTrue(transfers.moved("ses_one", "/destination", "/origin"))
        assertEquals("/origin", transfers.get("ses_one").obj("transfer").str("originDirectory"))
        assertEquals(false, transfers.get("ses_one").obj("transfer").bool("available"))
        assertEquals(Json.obj("sessionId" to "ses_one", "directory" to "/destination", "available" to false), fixture.events.single())
        assertEquals("Conversation moved to /destination. Open its folder to continue.",
            assertThrows(IllegalStateException::class.java) { transfers.checkWritable("ses_one") }.message)
        val restored = fixture.service()
        assertEquals(transfers.list(), restored.list())
        assertThrows(IllegalStateException::class.java) { restored.checkWritable("ses_one") }
        assertEquals("/destination", restored.openDirectory("ses_one"))
        assertFalse(fixture.saved.toString().contains("metadata"))
    }

    @Test fun `missed move event is detected by a session read before any write`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        assertThrows(IllegalStateException::class.java) { transfers.checkWritable("ses_one") }
        assertEquals("/destination", transfers.get("ses_one").str("directory"))
        assertEquals(1, fixture.reads)
        assertEquals(1, fixture.events.size)
    }

    @Test fun `only history routes can read transferred sessions and directories cannot widen scope`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        for (path in listOf("/session/ses_one", "/session/ses_one/message?limit=200&directory=/origin",
            "/session/ses_one/message/msg_one/part/part_one?directory=/destination", "/session/ses_one/todo",
            "/session/ses_one/diff", "/varro/session/ses_one/diff-summary"))
            assertEquals("/destination", transfers.authorizeRequest("GET", path))
        assertThrows(IllegalArgumentException::class.java) { transfers.authorizeRequest("GET", "/session/ses_one/message?directory=/foreign") }
        for ((method, path) in listOf("POST" to "/session/ses_one/prompt_async", "PATCH" to "/session/ses_one",
            "POST" to "/session/ses_one/fork", "DELETE" to "/session/ses_one", "POST" to "/varro/session/ses_one/permission-mode",
            "POST" to "/varro/session/ses_one/pin", "GET" to "/session/ses_one/background-process"))
            assertThrows(IllegalStateException::class.java) { transfers.authorizeRequest(method, path) }
        assertThrows(IllegalStateException::class.java) { transfers.authorizeRequest("POST", ApiRoutes.Endpoints.PERMISSION_PROJECT_ALLOW, Json.obj("sessionId" to "ses_one")) }
        assertNull(transfers.authorizeRequest("POST", "/session/ses_other/prompt_async"))
    }

    @Test fun `moves to open roots are available and Windows identities compare correctly`() {
        val fixture = Fixture()
        fixture.roots = listOf("C:\\Origin", "D:\\Destination")
        fixture.session = summary("c:/origin/")
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("d:/destination")
        assertTrue(transfers.moved("ses_one", "D:\\Destination\\", "C:\\Origin"))
        assertEquals(true, transfers.get("ses_one").obj("transfer").bool("available"))
        transfers.checkWritable("ses_one")
        fixture.roots = listOf("C:\\Origin")
        assertEquals(false, transfers.list().single().obj("transfer").bool("available"))
        assertThrows(IllegalStateException::class.java) { transfers.checkWritable("ses_one") }
    }

    @Test fun `unknown foreign sessions and malformed moves cannot create links`() {
        val fixture = Fixture()
        fixture.session = summary("/destination")
        val transfers = fixture.service()
        assertFalse(transfers.moved("ses_one", "/destination", "/foreign"))
        for (directory in listOf("relative/path", "/bad\npath", "/" + "x".repeat(4096)))
            assertFalse(transfers.moved("ses_one", directory, "/origin"))
        assertFalse(transfers.moved("../session", "/destination", "/origin"))
        assertTrue(transfers.list().isEmpty())
        assertEquals(0, fixture.reads)
        assertEquals(0, fixture.writes)
    }

    @Test fun `stale replay cannot follow obsolete destinations or open a moved-again folder`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/current")
        assertFalse(transfers.moved("ses_one", "/obsolete", "/origin"))
        assertTrue(transfers.list().isEmpty())
        assertTrue(transfers.moved("ses_one", "/current", "/origin"))
        assertTrue(transfers.moved("ses_one", "/current", "/origin"))
        assertEquals(1, fixture.events.size)
        fixture.session = summary("/new-location")
        assertThrows(IllegalStateException::class.java) { transfers.openDirectory("ses_one") }
    }

    @Test fun `saved links are bound to the server and originating project`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        fixture.server = "http://other.local"
        assertNull(transfers.get("ses_one"))
        assertTrue(transfers.list().isEmpty())
        fixture.server = "http://fixture.local:4096"
        fixture.roots = listOf("/foreign")
        assertNull(transfers.openDirectory("ses_one"))
        fixture.roots = listOf("/origin")
        assertEquals(1, transfers.list().size)
        transfers.remove("ses_one")
        assertTrue(fixture.service().list().isEmpty())
    }

    @Test fun `persistence failure does not publish or lose the retryable origin`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        fixture.failPersistence = true
        assertThrows(IllegalStateException::class.java) { transfers.observe(fixture.session) }
        assertTrue(fixture.events.isEmpty())
        assertTrue(transfers.list().isEmpty())
        fixture.failPersistence = false
        transfers.observe(fixture.session)
        assertEquals("/destination", transfers.get("ses_one").str("directory"))
        assertEquals(1, fixture.events.size)
    }

    @Test fun `store roundtrips summaries and rejects malformed persisted entries`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        val store = VarroStore()
        store.sessionTransfers = fixture.saved
        val restored = VarroStore().apply { loadState(store.state) }
        assertEquals(fixture.saved, restored.sessionTransfers)
        val malformed = restored.sessionTransfers.deepCopy().apply {
            add(Json.obj("server" to fixture.server, "origins" to listOf("/origin"), "session" to summary("relative/path")))
            add(Json.obj("server" to fixture.server, "origins" to listOf("/origin"), "session" to summary("/destination").apply { addProperty("id", "../escape") }))
        }
        assertEquals(1, fixture.service(malformed).list().size)
    }

    @Test fun `older catalog snapshots cannot undo a confirmed transfer`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination").apply { obj("time")!!.addProperty("updated", 3000) }
        transfers.observe(fixture.session)
        transfers.observe(summary("/origin"))
        assertEquals("/destination", transfers.get("ses_one").str("directory"))
        assertEquals(1, fixture.events.size)
        assertTrue(transfers.owns("ses_one", null))
        assertFalse(transfers.owns("ses_foreign", "/foreign"))
    }

    @Test fun `hiding persists only the selected origin without mutating conversation or history access`() {
        val fixture = Fixture()
        fixture.roots = listOf("/origin", "/other")
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/other")
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        val session = fixture.session.deepCopy()
        val events = fixture.events.toList()
        assertTrue(transfers.hide("ses_one", "/origin/"))
        assertEquals(1, fixture.invalidations)
        assertEquals(0, fixture.reads)
        assertEquals(events, fixture.events)
        assertEquals(session, fixture.session)

        val store = VarroStore().apply { sessionTransfers = fixture.saved }
        val restoredStore = VarroStore().apply { loadState(store.state) }
        val restored = fixture.service(restoredStore.sessionTransfers)
        assertTrue(restored.list(directory = "/origin").isEmpty())
        assertEquals(Json.obj("originDirectory" to "/origin", "available" to false, "hidden" to true,
            "hiddenOrigins" to listOf("/origin")), restored.list(includeHidden = true, directory = "/origin").single().obj("transfer"))
        assertNull(restored.list(directory = "/other").single().obj("transfer").bool("hidden"))
        assertNull(restored.list().single().obj("transfer").bool("hidden"))
        assertEquals("/destination", restored.authorizeRequest("GET", "/session/ses_one/message"))
        assertEquals("/destination", restored.openDirectory("ses_one"))
        assertThrows(IllegalStateException::class.java) { restored.checkWritable("ses_one") }

        fixture.session = summary("/final")
        restored.observe(fixture.session)
        assertTrue(fixture.service().list(directory = "/origin").isEmpty())
        assertEquals("/final", restored.list(directory = "/other").single().str("directory"))
    }

    @Test fun `unscoped hiding covers all open origins but never hides a destination`() {
        val fixture = Fixture()
        fixture.roots = listOf("C:\\Origin", "D:\\Other")
        fixture.session = summary("c:/origin/")
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("d:/other")
        transfers.observe(fixture.session)
        fixture.session = summary("E:/Destination")
        transfers.observe(fixture.session)
        assertTrue(transfers.hide("ses_one"))
        assertTrue(transfers.list().isEmpty())
        assertEquals(true, transfers.get("ses_one").obj("transfer").bool("hidden"))
        assertEquals(listOf("c:/origin/", "d:/other"), transfers.get("ses_one").obj("transfer").arr("hiddenOrigins")!!.strings())
        fixture.roots = fixture.roots + "e:\\destination\\"
        assertEquals(true, transfers.get("ses_one").obj("transfer").bool("available"))
        assertNull(transfers.get("ses_one").obj("transfer").bool("hidden"))
        val writes = fixture.writes
        assertFalse(transfers.hide("ses_one", "E:/Destination"))
        assertEquals(writes, fixture.writes)
    }

    @Test fun `foreign unknown malformed and closed-folder hide requests cannot write preferences`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        val saved = fixture.saved.deepCopy()
        val writes = fixture.writes
        for (id in listOf("", "../escape", "x".repeat(513))) {
            assertEquals("Invalid session ID", assertThrows(IllegalArgumentException::class.java) { transfers.hide(id) }.message)
        }
        assertThrows(IllegalArgumentException::class.java) { transfers.hide("ses_one", "/closed") }
        assertFalse(transfers.hide("unknown"))
        fixture.roots = listOf("/unrelated")
        assertFalse(transfers.hide("ses_one"))
        fixture.roots = listOf("/origin")
        fixture.server = "http://foreign.local"
        assertFalse(transfers.hide("ses_one"))
        assertEquals(writes, fixture.writes)
        assertEquals(saved, fixture.saved)
        assertEquals(0, fixture.invalidations)
        assertEquals(0, fixture.reads)
    }

    @Test fun `failed hide persistence keeps the link visible and permits retry`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        val saved = fixture.saved.deepCopy()
        fixture.failPersistence = true
        assertThrows(IllegalStateException::class.java) { transfers.hide("ses_one") }
        assertEquals(saved, fixture.saved)
        assertEquals(1, transfers.list().size)
        assertEquals(1, fixture.service().list().size)
        assertEquals(0, fixture.invalidations)
        fixture.failPersistence = false
        assertTrue(transfers.hide("ses_one"))
        assertTrue(fixture.service().list().isEmpty())
        assertEquals(1, fixture.invalidations)
    }

    @Test fun `catalog pages retain hidden metadata without overwriting newer live session fields`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        assertTrue(transfers.hide("ses_one"))
        val live = summary("/destination").apply {
            addProperty("title", "Newer title")
            obj("time")!!.addProperty("updated", 9000)
        }
        for (path in listOf("/session", "/session?limit=20&directory=%2Forigin",
            "/session?limit=20&roots=true&search=Fixture&directory=%2Forigin")) {
            val request = ApiRoutes.parse("GET", path)!!
            val snapshot = transfers.merge(Json.array(listOf(live.deepCopy())), request).single().asJsonObject
            assertEquals(true, snapshot.obj("transfer").bool("hidden"))
            assertEquals(listOf("/origin"), snapshot.obj("transfer").arr("hiddenOrigins")!!.strings())
            assertEquals("Newer title", snapshot.str("title"))
            assertEquals(9000, snapshot.obj("time").int("updated"))
            assertEquals(true, transfers.merge(JsonArray(), request).single().asJsonObject.obj("transfer").bool("hidden"))
        }
        assertEquals(true, transfers.get("ses_one").obj("transfer").bool("hidden"))
        assertEquals("/destination", transfers.authorizeRequest("GET", "/session/ses_one"))
        assertTrue(transfers.merge(JsonArray(), ApiRoutes.parse("GET", "/session?limit=20&directory=%2Fother")!!).isEmpty)
        assertTrue(transfers.merge(JsonArray(), ApiRoutes.parse("GET", "/session?limit=20&search=unmatched&roots=true")!!).isEmpty)
    }

    @Test fun `restored hide preferences reject malformed paths and bound the stored list`() {
        val fixture = Fixture()
        val transfers = fixture.service()
        transfers.observe(fixture.session)
        fixture.session = summary("/destination")
        transfers.observe(fixture.session)
        val malformed = fixture.saved.deepCopy().apply {
            single().asJsonObject.add("hiddenOrigins", Json.array(listOf("relative", "/bad\npath", "x".repeat(4097), 123) +
                (1..40).map { "/valid-$it" }))
            single().asJsonObject.obj("session")!!.obj("transfer")!!.addProperty("hidden", true)
        }
        val restored = fixture.service(malformed)
        assertNull(restored.list().single().obj("transfer").bool("hidden"))
        assertEquals((1..32).map { "/valid-$it" }, restored.get("ses_one").obj("transfer").arr("hiddenOrigins")!!.strings())
        assertTrue(restored.hide("ses_one"))
        assertEquals(32, fixture.saved.single().asJsonObject.arr("hiddenOrigins")!!.size())
    }

    companion object {
        private fun summary(directory: String) = Json.obj("id" to "ses_one", "projectID" to "project", "version" to "2", "title" to "Fixture conversation",
            "directory" to directory, "metadata" to Json.obj("private" to "not persisted"), "time" to Json.obj("created" to 1000, "updated" to 2000))
    }
}
