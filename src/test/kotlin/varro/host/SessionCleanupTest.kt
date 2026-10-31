package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.nio.file.Files
import java.util.concurrent.CancellationException

class SessionCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = HistoryFixture(temporary.root.toPath().resolve("history.db"))
    private fun json(session: CleanupSession): JsonObject = Json.obj("id" to session.id, "directory" to session.directory, "title" to session.title,
        "time" to Json.obj("created" to session.created, "updated" to session.updated), "metadata" to Json.obj("varroLegacyImport" to Json.obj("sourceSessionID" to session.identity))).apply {
        session.parentID?.let { addProperty("parentID", it) }
    }

    private class Server(val f: HistoryFixture, val sessions: MutableMap<String, CleanupSession>) {
        val calls = mutableListOf<String>()
        val active = Json.obj()
        var queued = false
        var afterArchive: (() -> Unit)? = null
        var afterDelete: (() -> Unit)? = null
        var deletionFailure: String? = null
        var confirmations = 0
        fun request(method: String, path: String, options: RequestOptions): OpenCodeResponse {
            assertTrue("Cleanup must retain exact database paths", options.nativePaths)
            options.checkCancelled(); calls.add("$method $path")
            val id = path.substringBefore('?').split('/').getOrNull(2)
            if (method == "DELETE") {
                assertTrue("Durable usage must precede deletion", Files.exists(f.archive.archivePath))
                val root = sessions[id] ?: error("Missing root")
                val tree = SessionCleanup.tree(root, sessions)
                for (entry in tree) {
                    sessions.remove(entry.id)
                    f.execute("DELETE FROM session_message WHERE session_id=?", entry.id)
                    f.execute("DELETE FROM session_v2 WHERE id=?", entry.id)
                }
                afterDelete?.invoke()
                return OpenCodeResponse(Json.toElement(true))
            }
            return OpenCodeResponse(when {
                path.startsWith("/experimental/session") -> Json.array(sessions.values.map { jsonValue(it) })
                path == "/api/session/active" -> {
                    if (Files.exists(f.archive.archivePath)) afterArchive?.invoke()
                    Json.obj("data" to active)
                }
                path == "/api/debug/location" -> Json.array(emptyList<Any>())
                path.endsWith("/inbox") -> Json.obj("data" to if (queued) listOf(Json.obj("id" to "queued")) else emptyList<Any>())
                path.endsWith("/children") -> Json.array(sessions.values.filter { it.parentID == id }.map { jsonValue(it) })
                path.startsWith("/session/") -> sessions[id]?.let(::jsonValue) ?: run {
                    confirmations++; error(deletionFailure ?: "404 Session not found")
                }
                else -> error("Unexpected request $path")
            })
        }
        private fun jsonValue(session: CleanupSession) = Json.obj("id" to session.id, "directory" to session.directory, "title" to session.title,
            "time" to Json.obj("created" to session.created, "updated" to session.updated), "metadata" to Json.obj("varroLegacyImport" to Json.obj("sourceSessionID" to session.identity))).apply {
            session.parentID?.let { addProperty("parentID", it) }
        }
    }

    private fun service(f: HistoryFixture, server: Server) = SessionCleanup(f.archive, 2, { true }, server::request, { it() })

    @Test fun `old children can be deleted without a retained newer parent and stats survive`() {
        val f = fixture()
        val parent = f.session("parent", updated = 5000)
        val child = f.session("child", parent = parent.id)
        f.message(child, "assistant", "assistant", f.assistant(), 1)
        val server = Server(f, linkedMapOf(parent.id to parent, child.id to child))
        val result = service(f, server).run(SessionCleanup.Inventory(server.sessions.values.toList(), emptyList()), 2000, false)
        assertEquals(SessionCleanup.Result(1, false), result)
        assertEquals(setOf("parent"), server.sessions.keys)
        assertTrue(f.csv().csv.contains("provider,model,high,1,10,5"))
    }

    @Test fun `active or newer descendants keep their entire recursive deletion unit`() {
        for (activeChild in listOf(false, true)) {
            val f = HistoryFixture(temporary.newFolder().toPath().resolve("history.db"))
            val parent = f.session("parent")
            val child = f.session("child", updated = if (activeChild) 1000 else 5000, parent = parent.id)
            val server = Server(f, linkedMapOf(parent.id to parent, child.id to child))
            if (activeChild) server.active.add("child", Json.obj())
            assertEquals(0, service(f, server).run(SessionCleanup.Inventory(server.sessions.values.toList(), emptyList()), 2000, false).deleted)
            assertFalse(server.calls.any { it.startsWith("DELETE") })
            assertFalse(Files.exists(f.archive.archivePath))
        }
    }

    @Test fun `changed metadata after archiving prevents deletion and keeps durable retry data`() {
        val f = fixture(); val session = f.session("session")
        f.message(session, "assistant", "assistant", f.assistant(), 1)
        val server = Server(f, linkedMapOf(session.id to session))
        server.afterArchive = { server.sessions[session.id] = session.copy(updated = 3000) }
        val result = service(f, server).run(SessionCleanup.Inventory(listOf(session), emptyList()), 2000, false)
        assertEquals(0, result.deleted)
        assertEquals(1L, f.count("session_v2"))
        assertEquals(1, f.archive.pending().size)
        assertFalse(server.calls.any { it.startsWith("DELETE") })
    }

    @Test fun `cancellation during deletion waits for every member confirmation and retains file journal`() {
        val f = fixture(); val parent = f.session("parent", updated = 900); val child = f.session("child", parent = parent.id)
        val server = Server(f, linkedMapOf(parent.id to parent, child.id to child))
        var cancelled = false
        server.afterDelete = { cancelled = true }
        val result = service(f, server).run(SessionCleanup.Inventory(listOf(parent, child), emptyList()), 2000, false) {
            if (cancelled) throw CancellationException("Fixture cancel")
        }
        assertEquals(SessionCleanup.Result(2, true), result)
        assertEquals(2, server.confirmations)
        assertEquals(2, f.archive.pending().size)
    }

    @Test fun `missing location never counts as confirmed deletion`() {
        val f = fixture(); val session = f.session("session")
        val server = Server(f, linkedMapOf(session.id to session)).apply { deletionFailure = "404 Location not found" }
        assertThrows(IllegalStateException::class.java) {
            service(f, server).run(SessionCleanup.Inventory(listOf(session), emptyList()), 2000, false)
        }
        assertEquals(1, f.archive.pending().size)
    }

    @Test fun `queued inbox work is protected when transcripts were requested`() {
        val f = fixture(); val session = f.session("session")
        val server = Server(f, linkedMapOf(session.id to session)).apply { queued = true }
        assertEquals(0, service(f, server).run(SessionCleanup.Inventory(listOf(session), emptyList()), 2000, true).deleted)
        assertFalse(Files.exists(f.archive.archivePath))
        assertFalse(Files.exists(f.archive.transcriptPath))
    }

    @Test fun `legacy inventory rejects a different local installation before confirmation`() {
        val f = fixture(); val session = f.session("session")
        assertThrows(IllegalArgumentException::class.java) { f.archive.legacyInventory(listOf(session.copy(updated = 2000))) }
        assertEquals(emptyList<CleanupSession>(), f.archive.legacyInventory(listOf(session)))
        assertThrows(IllegalArgumentException::class.java) {
            SessionCleanup(f.archive, 2, { false }, { _, _, _ -> error("Must not contact server") }, { error("Must not stop server") }).locked { error("Must not run") }
        }
    }

    @Test fun `calendar retention clamps month ends and exact-cutoff sessions stay`() {
        val now = java.time.Instant.parse("2026-03-31T12:34:56Z").toEpochMilli()
        assertEquals(java.time.Instant.parse("2026-02-28T12:34:56Z").toEpochMilli(), SessionCleanup.retentionCutoff(1, now))
        assertThrows(IllegalArgumentException::class.java) { SessionCleanup.retentionCutoff(0, now) }
        val f = fixture(); val session = f.session("session", updated = 2000)
        val server = Server(f, linkedMapOf(session.id to session))
        assertEquals(0, service(f, server).run(SessionCleanup.Inventory(listOf(session), emptyList()), 2000, false).deleted)
    }
}
