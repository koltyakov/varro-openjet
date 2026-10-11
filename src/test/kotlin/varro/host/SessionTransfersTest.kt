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
        fun service(initial: JsonArray = saved) = SessionTransfers(initial, {
            if (failPersistence) error("Persistence failed")
            saved = it.deepCopy(); writes++
        }, { server }, { roots }, { id -> assertEquals("ses_one", id); reads++; session.deepCopy() }, { events.add(it) })
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

    companion object {
        private fun summary(directory: String) = Json.obj("id" to "ses_one", "projectID" to "project", "version" to "2", "title" to "Fixture conversation",
            "directory" to directory, "metadata" to Json.obj("private" to "not persisted"), "time" to Json.obj("created" to 1000, "updated" to 2000))
    }
}
