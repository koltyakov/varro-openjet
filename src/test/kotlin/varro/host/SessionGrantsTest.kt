package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.host.SessionGrants.EventRoute
import varro.host.SessionGrants.Grant
import varro.host.SessionGrants.Resolution
import varro.protocol.Json
import varro.protocol.str
import java.util.concurrent.Executor

class SessionGrantsTest {
    private class Fixture {
        var roots = listOf("/origin")
        /** id to (destination, verified). */
        val links = linkedMapOf<String, Pair<String, Boolean>>()
        val observed = mutableMapOf<String, String>()
        val records = mutableMapOf<String, JsonObject>()
        val reads = mutableListOf<String>()
        val verifications = mutableListOf<String>()
        var verifies = true
        var readFailure = false
        var connection: Any = 1
        var onRead: (String) -> Unit = {}
        var clock = 0L
        var manual = false
        val tasks = ArrayDeque<Runnable>()
        var linkReads = 0

        val grants = SessionGrants(
            roots = { roots },
            links = { linkReads++; links.map { (id, link) -> Json.obj("id" to id, "directory" to link.first, "transfer" to Json.obj("available" to link.second)) } },
            verify = { id ->
                verifications += id
                links[id]?.let { links[id] = it.first to verifies }
                verifies
            },
            observedDirectory = { observed[it] },
            readSession = { id ->
                reads += id
                onRead(id)
                if (readFailure) error("Session read failed")
                records[id]?.deepCopy()
            },
            connection = { connection },
            executor = Executor { if (manual) tasks.addLast(it) else it.run() },
            nanoTime = { clock },
        )

        fun runTasks() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private fun record(id: String, directory: String, parent: String? = null) =
        Json.obj("id" to id, "directory" to directory).apply { parent?.let { addProperty("parentID", it) } }

    @Test fun `only a verified visible link grants its own destination`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to true
        assertEquals(Grant("ses_conv", "/dest"), fixture.grants.grant("ses_conv"))
        assertTrue(fixture.grants.isGranted("ses_conv", "/dest"))
        assertFalse(fixture.grants.isGranted("ses_conv", "/other"))
        assertFalse(fixture.grants.isGranted("ses_conv", null))
        fixture.links["ses_conv"] = "/dest" to false
        assertNull(fixture.grants.grant("ses_conv"))
        // Hidden links are absent from the visible list and grant nothing.
        fixture.links.clear()
        assertNull(fixture.grants.grant("ses_conv"))
        assertFalse(fixture.grants.isGrantedDirectory("/dest"))
    }

    @Test fun `subagents inherit a grant only in the conversation's own directory, and lose it with the conversation`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to true
        fixture.grants.observe(record("ses_conv", "/dest"))
        fixture.grants.observe(record("ses_child", "/dest", "ses_conv"))
        fixture.grants.observe(record("ses_grandchild", "/dest", "ses_child"))
        fixture.grants.observe(record("ses_elsewhere", "/other", "ses_conv"))
        fixture.grants.observe(Json.obj("id" to "ses_unknown", "parentID" to "ses_conv"))
        assertEquals(Grant("ses_conv", "/dest"), fixture.grants.grant("ses_child"))
        assertEquals(Grant("ses_conv", "/dest"), fixture.grants.grant("ses_grandchild"))
        assertEquals(listOf("ses_grandchild", "ses_child", "ses_conv"), fixture.grants.lineage("ses_grandchild"))
        assertNull(fixture.grants.grant("ses_elsewhere"))
        // A record without a directory teaches nothing, so the session stays ungranted.
        assertNull(fixture.grants.grant("ses_unknown"))
        // The observed location is current: a child seen in another folder inherits nothing.
        fixture.observed["ses_child"] = "/other"
        assertNull(fixture.grants.grant("ses_child"))
        fixture.observed.remove("ses_child")

        fixture.links["ses_conv"] = "/dest" to false
        assertNull(fixture.grants.grant("ses_child"))
        assertEquals("ses_conv", fixture.grants.linkedOwner("ses_grandchild"))
        fixture.links.clear()
        assertNull(fixture.grants.grant("ses_grandchild"))
        assertNull(fixture.grants.linkedOwner("ses_grandchild"))
        fixture.links["ses_conv"] = "/dest" to true
        fixture.grants.forget("ses_child")
        assertNull(fixture.grants.grant("ses_grandchild"))
    }

    @Test fun `an ancestor owned by an open root ends inheritance`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to true
        fixture.grants.observe(record("ses_conv", "/dest"))
        fixture.grants.observe(record("ses_local", "/origin", "ses_conv"))
        fixture.grants.observe(record("ses_child", "/dest", "ses_local"))
        assertNull(fixture.grants.grant("ses_child"))
        // A cycle cannot loop the walk.
        fixture.grants.observe(record("ses_a", "/dest", "ses_b"))
        fixture.grants.observe(record("ses_b", "/dest", "ses_a"))
        assertEquals(listOf("ses_a", "ses_b"), fixture.grants.lineage("ses_a"))
        assertNull(fixture.grants.grant("ses_a"))
    }

    @Test fun `missing ancestry is learned through canonical reads, and the conversation is verified again`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to false
        fixture.records["ses_child"] = record("ses_child", "/dest", "ses_mid")
        fixture.records["ses_mid"] = record("ses_mid", "/dest", "ses_conv")
        fixture.records["ses_conv"] = record("ses_conv", "/dest")
        assertEquals(EventRoute.REDELIVER, fixture.grants.routeEvent("permission.asked", listOf("ses_child"), "/dest"))
        assertEquals(Resolution.GRANTED, fixture.grants.resolve(listOf("ses_child"), "/dest"))
        // The linked conversation ends the walk; its own record is not needed.
        assertEquals(listOf("ses_child", "ses_mid"), fixture.reads)
        assertEquals(listOf("ses_conv"), fixture.verifications)
        assertEquals(Grant("ses_conv", "/dest"), fixture.grants.grant("ses_child"))
        assertEquals(EventRoute.FORWARD, fixture.grants.routeEvent("session.next.text.delta", listOf("ses_child"), "/dest"))
        // A known chain needs no further reads.
        assertEquals(Resolution.GRANTED, fixture.grants.resolve(listOf("ses_mid", "ses_child"), "/dest"))
        assertEquals(2, fixture.reads.size)
    }

    @Test fun `unrelated traffic causes no reads, and unrelated sessions are remembered briefly`() {
        val fixture = Fixture()
        assertEquals(Resolution.UNRELATED, fixture.grants.resolve(listOf("ses_foreign"), "/dest"))
        assertEquals(EventRoute.DROP, fixture.grants.routeEvent("permission.asked", listOf("ses_foreign"), "/dest"))
        assertTrue(fixture.reads.isEmpty())

        fixture.links["ses_conv"] = "/dest" to true
        fixture.records["ses_foreign"] = record("ses_foreign", "/dest")
        assertEquals(Resolution.UNRELATED, fixture.grants.resolve(listOf("ses_foreign"), "/dest"))
        assertEquals(Resolution.UNRELATED, fixture.grants.resolve(listOf("ses_foreign"), "/dest"))
        assertEquals(listOf("ses_foreign"), fixture.reads)
        // After the miss expires the walk runs again, but a confirmed root needs no second read.
        fixture.clock += 61_000_000_000L
        fixture.records.remove("ses_foreign")
        assertEquals(Resolution.UNRELATED, fixture.grants.resolve(listOf("ses_foreign"), "/dest"))
        assertEquals(1, fixture.reads.size)
        // A mixed batch is not wholly granted.
        fixture.grants.observe(record("ses_child", "/dest", "ses_conv"))
        assertEquals(Resolution.UNRELATED, fixture.grants.resolve(listOf("ses_child", "ses_foreign"), "/dest"))
        assertEquals(EventRoute.DROP, fixture.grants.routeEvent("session.status", emptyList(), "/dest"))
    }

    @Test fun `other folders' streaming traffic costs one link read and no session reads`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to true
        fixture.grants.observe(record("ses_child", "/dest", "ses_conv"))
        fixture.linkReads = 0
        repeat(100) { assertEquals(EventRoute.DROP, fixture.grants.routeEvent("session.next.text.delta", listOf("ses_foreign"), "/other")) }
        assertEquals(100, fixture.linkReads)
        assertTrue(fixture.reads.isEmpty())
        fixture.linkReads = 0
        assertEquals(EventRoute.FORWARD, fixture.grants.routeEvent("session.next.text.delta", listOf("ses_child"), "/dest"))
        assertEquals(1, fixture.linkReads)
    }

    @Test fun `inconclusive walks never read as unrelated and are not remembered`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to true
        fixture.readFailure = true
        assertEquals(Resolution.UNKNOWN, fixture.grants.resolve(listOf("ses_child"), "/dest"))
        fixture.readFailure = false

        // Another session's record says nothing about this one.
        fixture.records["ses_child"] = record("ses_other", "/dest", "ses_conv")
        assertEquals(Resolution.UNKNOWN, fixture.grants.resolve(listOf("ses_child"), "/dest"))

        // A connection change during the walk discards it.
        fixture.records["ses_child"] = record("ses_child", "/dest", "ses_conv")
        fixture.onRead = { fixture.connection = 2 }
        assertEquals(Resolution.UNKNOWN, fixture.grants.resolve(listOf("ses_child"), "/dest"))
        fixture.onRead = {}
        assertEquals(Resolution.GRANTED, fixture.grants.resolve(listOf("ses_child"), "/dest"))

        // The read bound stops a long unknown chain.
        val deep = Fixture()
        deep.links["ses_conv"] = "/dest" to true
        val chain = (0..SessionGrants.MAX_READS).map { "ses_$it" }
        chain.forEachIndexed { index, id -> deep.records[id] = record(id, "/dest", chain.getOrNull(index + 1) ?: "ses_conv") }
        assertEquals(Resolution.UNKNOWN, deep.grants.resolve(listOf("ses_0"), "/dest"))
        assertEquals(SessionGrants.MAX_READS, deep.reads.size)

        // A failed verification leaves the conversation's subagents ungranted.
        val unverified = Fixture()
        unverified.links["ses_conv"] = "/dest" to false
        unverified.verifies = false
        unverified.grants.observe(record("ses_child", "/dest", "ses_conv"))
        assertEquals(Resolution.UNRELATED, unverified.grants.resolve(listOf("ses_child"), "/dest"))
        assertEquals(listOf("ses_conv"), unverified.verifications)
    }

    @Test fun `concurrent resolutions share one walk`() {
        val fixture = Fixture()
        fixture.manual = true
        fixture.links["ses_conv"] = "/dest" to true
        fixture.records["ses_child"] = record("ses_child", "/dest", "ses_conv")
        val first = fixture.grants.resolveAsync(listOf("ses_child"), "/dest")
        val second = fixture.grants.resolveAsync(listOf("ses_child"), "/dest")
        assertEquals(1, fixture.tasks.size)
        assertFalse(first.isDone)
        fixture.runTasks()
        assertEquals(Resolution.GRANTED, first.join())
        assertEquals(Resolution.GRANTED, second.join())
        assertEquals(listOf("ses_child"), fixture.reads)
    }

    private data class Event(val type: String, val session: String, val label: String, val pending: Boolean = true)

    private class RouterFixture {
        val base = Fixture().apply { manual = true; links["ses_conv"] = "/dest" to true }
        val hidden = mutableSetOf<String>()
        val delivered = mutableListOf<String>()
        val router = GrantedEventRouter<Event>(base.grants, { it in hidden }, { it.pending }) { delivered += it.label }
        fun route(event: Event, directory: String = "/dest") = router.routeExternal(event, event.type, listOf(event.session), directory)
    }

    @Test fun `granted events forward at once and other folders' events drop`() {
        val fixture = RouterFixture()
        fixture.base.grants.observe(record("ses_child", "/dest", "ses_conv"))
        fixture.route(Event("session.next.text.delta", "ses_child", "delta"))
        fixture.route(Event("permission.asked", "ses_conv", "ask"))
        fixture.route(Event("permission.asked", "ses_foreign", "foreign", ), directory = "/other")
        fixture.router.deliverOwned(Event("session.status", "ses_local", "local"))
        assertEquals(listOf("delta", "ask", "local"), fixture.delivered)
        assertTrue(fixture.base.tasks.isEmpty())
    }

    @Test fun `deferred events keep their order, later lifecycle events wait, and transcript deltas are not replayed`() {
        val fixture = RouterFixture()
        fixture.base.records["ses_child"] = record("ses_child", "/dest", "ses_conv")
        fixture.route(Event("session.status", "ses_child", "busy"))
        fixture.route(Event("session.next.text.delta", "ses_child", "early delta"))
        fixture.route(Event("question.asked", "ses_child", "answered question", pending = false))
        fixture.route(Event("permission.asked", "ses_child", "permission"))
        fixture.route(Event("session.status", "ses_child", "idle"))
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(1, fixture.base.tasks.size)
        fixture.base.runTasks()
        assertEquals(listOf("busy", "permission", "idle"), fixture.delivered)
        fixture.route(Event("session.next.text.delta", "ses_child", "later delta"))
        assertEquals("later delta", fixture.delivered.last())
    }

    @Test fun `hidden or recycled lineages and unrelated sessions deliver nothing`() {
        val fixture = RouterFixture()
        fixture.base.records["ses_child"] = record("ses_child", "/dest", "ses_conv")
        fixture.route(Event("permission.asked", "ses_child", "hidden ask"))
        fixture.hidden += "ses_conv"
        fixture.base.runTasks()
        fixture.route(Event("session.status", "ses_child", "hidden status"))
        assertTrue(fixture.delivered.isEmpty())

        fixture.hidden.clear()
        fixture.base.records["ses_foreign"] = record("ses_foreign", "/dest")
        fixture.route(Event("permission.asked", "ses_foreign", "foreign ask"))
        fixture.base.runTasks()
        fixture.route(Event("permission.asked", "ses_foreign", "foreign again"))
        fixture.base.runTasks()
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(listOf("ses_child", "ses_foreign"), fixture.base.reads)
    }

    @Test fun `destination pending requests keep granted sessions and reject inconclusive ancestry`() {
        val fixture = Fixture()
        fixture.links["ses_conv"] = "/dest" to true
        fixture.records["ses_child"] = record("ses_child", "/dest", "ses_conv")
        fixture.records["ses_foreign"] = record("ses_foreign", "/dest")
        fixture.records["ses_hidden"] = record("ses_hidden", "/dest", "ses_conv")
        fun request(id: String, session: String) = Json.obj("id" to id, "sessionID" to session)
        val listed = Json.array(listOf(request("per_conv", "ses_conv"), request("per_child", "ses_child"),
            request("per_foreign", "ses_foreign"), request("per_hidden", "ses_hidden"), Json.obj("id" to "per_global")))
        val kept = PendingRequests.granted(listed, "/dest", fixture.grants) { it == "ses_hidden" }
        assertEquals(listOf("per_conv", "per_child"), kept.map { PendingRequests.id(it) })
        assertEquals("ses_child", PendingRequests.sessionId(Json.obj("info" to Json.obj("sessionID" to "ses_child"))))
        assertEquals("per_info", PendingRequests.id(Json.obj("properties" to Json.obj("info" to Json.obj("id" to " per_info ")))))

        fixture.records["ses_late"] = record("ses_late", "/dest", "ses_conv")
        fixture.readFailure = true
        val message = assertThrows(IllegalStateException::class.java) {
            PendingRequests.granted(Json.array(listOf(request("per_late", "ses_late"))), "/dest", fixture.grants) { false }
        }.message
        assertEquals("Could not resolve pending request session ancestry", message)
        assertTrue(PendingRequests.granted(JsonArray(), "/dest", fixture.grants) { false }.isEmpty())
    }

    @Test fun `verification against retained transfers grants and revokes through the link`() {
        var verified = false
        var roots = listOf("/origin")
        val transfers = SessionTransfers(JsonArray(), {}, { "http://fixture.local:4096" }, { roots },
            { Json.obj("id" to "ses_conv", "projectID" to "project", "version" to "2", "title" to "Conversation",
                "directory" to "/dest", "time" to Json.obj("created" to 1, "updated" to 2)) }, {},
            authorizeWorktree = { verified }, knownWorktree = { verified })
        transfers.observe(Json.obj("id" to "ses_conv", "projectID" to "project", "version" to "2", "title" to "Conversation",
            "directory" to "/origin", "time" to Json.obj("created" to 1, "updated" to 1)))
        assertTrue(transfers.moved("ses_conv", "/dest", "/origin"))
        val grants = SessionGrants({ roots }, { transfers.list() },
            { id -> transfers.get(id)?.let { it.str("id") == id && verified } == true },
            { null }, { null }, { 1 }, Executor { it.run() })
        grants.observe(Json.obj("id" to "ses_child", "directory" to "/dest", "parentID" to "ses_conv"))
        assertNull(grants.grant("ses_child"))
        verified = true
        assertEquals(Grant("ses_conv", "/dest"), grants.grant("ses_child"))
        assertTrue(transfers.hide("ses_conv", "/origin"))
        assertNull(grants.grant("ses_child"))
        assertFalse(grants.isGrantedDirectory("/dest"))
        roots = listOf("/elsewhere")
        assertTrue(transfers.list().isEmpty())
    }
}
