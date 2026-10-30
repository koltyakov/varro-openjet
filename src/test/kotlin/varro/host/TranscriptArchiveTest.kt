package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import varro.server.OpenCodeResponse
import java.nio.file.Files

class TranscriptArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = HistoryFixture(temporary.root.toPath().resolve("history.db"))
    private fun turns(f: HistoryFixture): List<com.google.gson.JsonObject> = HistoryDatabase.open(f.archive.transcriptPath).use { database ->
        HistoryDatabase.query(database, "SELECT data FROM transcript_turn ORDER BY ordinal") { Json.parse(it.getString(1)).asJsonObject }
    }

    @Test fun `separately stored assistant parts replace stale inline content and preserve idle outcome`() {
        val f = fixture(); val root = f.session("parent"); val child = f.session("child", parent = root.id)
        f.message(root, "prompt", "user", Json.obj("time" to Json.obj("created" to 100), "text" to "User prompt"), 1)
        f.message(root, "assistant", "assistant", f.assistant(text = "STALE INLINE RESPONSE"), 2)
        f.execute("INSERT INTO session_part VALUES('p1','assistant','parent',0,'text',?)", Json.obj("type" to "text", "text" to "Authoritative final response").toString())
        f.execute("INSERT INTO session_part VALUES('p2','assistant','parent',1,'reasoning',?)", Json.obj("type" to "reasoning", "text" to "PRIVATE REASONING").toString())
        f.message(root, "idle", "idle", Json.obj("outcome" to "succeeded"), 3)
        TranscriptArchive(f.archive).archive(listOf(root, child), 2, { _, _, _ -> error("No API fallback expected") }, {})
        val turn = turns(f).single()
        assertEquals("succeeded", turn.str("outcome")); assertEquals("idle", turn.str("boundary"))
        assertEquals("Authoritative final response", turn.obj("response").str("text"))
        assertEquals("User prompt", turn.arr("prompts")!![0].asJsonObject.str("text"))
        assertFalse(turn.toString().contains("PRIVATE")); assertFalse(turn.toString().contains("STALE"))
        HistoryDatabase.open(f.archive.transcriptPath).use { assertEquals(1L, HistoryDatabase.scalar(it, "SELECT count(*) FROM transcript_session")) }
    }

    @Test fun `pasted annotations load only bounded text blobs while images keep omitted metadata`() {
        val f = fixture(); val session = f.session("parent")
        val hash = "a".repeat(64)
        f.execute("INSERT INTO blob VALUES(?,?)", hash, "Chat annotation\n\nSelected text:\n> passage\n\nComment:\nnote".toByteArray())
        f.message(session, "prompt", "user", Json.obj("time" to Json.obj("created" to 100), "text" to "Check this", "files" to listOf(
            Json.obj("name" to "annotation-abcdef12.txt", "mime" to "text/plain", "uri" to "media:$hash"),
            Json.obj("name" to "private.png", "mime" to "image/png", "data" to "PRIVATE IMAGE BYTES"))), 1)
        TranscriptArchive(f.archive).archive(listOf(session), 2, { _, _, _ -> error("No API expected") }, {})
        val files = turns(f).single().arr("prompts")!![0].asJsonObject.arr("attachments")!!
        assertEquals(false, files[0].asJsonObject.bool("omitted")); assertTrue(files[0].asJsonObject.str("text")!!.contains("passage"))
        assertEquals(true, files[1].asJsonObject.bool("omitted")); assertFalse(files[1].asJsonObject.has("data"))
        assertFalse(Files.readString(f.archive.transcriptPath, Charsets.ISO_8859_1).contains("PRIVATE IMAGE"))
    }

    @Test fun `forked history uses native pagination and accepts empty terminal page without invented success`() {
        val f = fixture(); val session = f.session("fork")
        f.execute("UPDATE session_v2 SET fork_session_id='parent'")
        val prompt = Json.obj("id" to "prompt", "type" to "user", "time" to Json.obj("created" to 100), "text" to "Question")
        val answer = f.assistant().apply { addProperty("id", "answer"); addProperty("type", "assistant") }
        var calls = 0
        TranscriptArchive(f.archive).archive(listOf(session), 2, { _, path, options ->
            assertTrue(options.unscoped); assertTrue(path.startsWith("/api/session/fork/message?"))
            calls++
            OpenCodeResponse(if (calls == 1) Json.obj("data" to listOf(answer, prompt), "cursor" to Json.obj("next" to "next-page"))
                else Json.obj("data" to emptyList<Any>(), "cursor" to Json.obj("next" to null)))
        }, {})
        assertEquals(2, calls)
        assertEquals("unknown", turns(f).single().str("outcome"))
        assertEquals("inferred", turns(f).single().str("boundary"))
    }

    @Test fun `repeated API cursor and unavailable preserved text prevent transcript writes`() {
        val f = fixture(); val session = f.session("fork")
        f.execute("UPDATE session_v2 SET fork_session_id='parent'")
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptArchive(f.archive).archive(listOf(session), 2, { _, _, _ ->
                calls++
                OpenCodeResponse(Json.obj("data" to listOf(Json.obj("id" to "system-$calls", "type" to "system")), "cursor" to Json.obj("next" to "same-cursor")))
            }, {})
        }
        assertFalse(Files.exists(f.archive.transcriptPath))
        f.execute("UPDATE session_v2 SET fork_session_id=NULL")
        f.message(session, "prompt", "user", Json.obj("time" to Json.obj("created" to 100), "text" to "Check", "files" to listOf(
            Json.obj("name" to "pasted-text-abcdef12.txt", "mime" to "text/plain", "uri" to "file:/private/original.txt"))), 1)
        assertThrows(IllegalStateException::class.java) { TranscriptArchive(f.archive).archive(listOf(session), 2, { _, _, _ -> error("No API expected") }, {}) }
        assertFalse(Files.exists(f.archive.transcriptPath))
    }

    @Test fun `failed turns keep partial response and child-only trees create no transcript`() {
        val f = fixture(); val session = f.session("parent"); val child = f.session("child", parent = session.id)
        TranscriptArchive(f.archive).archive(listOf(child), 2, { _, _, _ -> error("No child transcript") }, {})
        assertFalse(Files.exists(f.archive.transcriptPath))
        f.message(session, "prompt", "user", Json.obj("time" to Json.obj("created" to 100), "text" to "Question"), 1)
        f.message(session, "answer", "assistant", f.assistant(text = "Partial reply").apply { getAsJsonObject("time").remove("completed") }, 2)
        f.message(session, "idle", "idle", Json.obj("outcome" to "interrupted"), 3)
        TranscriptArchive(f.archive).archive(listOf(session), 2, { _, _, _ -> error("No API") }, {})
        assertEquals("interrupted", turns(f).single().str("outcome"))
        assertEquals("Partial reply", turns(f).single().obj("response").str("text"))
    }

    @Test fun `legacy reverted parent stops cleanup unless covered by an equal or newer transcript`() {
        val f = fixture(); val session = f.session("parent", 1)
        f.message(session, "prompt", "user", Json.obj("time" to Json.obj("created" to 100)), 1)
        f.execute("INSERT INTO part VALUES('text','parent','prompt',1,?)", Json.obj("type" to "text", "text" to "Legacy prompt").toString())
        f.execute("INSERT INTO part VALUES('synthetic','parent','prompt',2,?)", Json.obj("type" to "text", "text" to "PRIVATE SYNTHETIC", "synthetic" to true).toString())
        f.database { TranscriptArchive(f.archive).archiveLegacy(it, listOf(session), {}) }
        assertEquals("Legacy prompt", turns(f).single().arr("prompts")!![0].asJsonObject.str("text"))
        assertFalse(turns(f).toString().contains("PRIVATE SYNTHETIC"))
        f.execute("UPDATE session SET revert='{}'")
        f.database { TranscriptArchive(f.archive).archiveLegacy(it, listOf(session), {}) }
        f.execute("UPDATE session SET time_updated=3000")
        assertThrows(IllegalArgumentException::class.java) { f.database { TranscriptArchive(f.archive).archiveLegacy(it, listOf(session.copy(updated = 3000)), {}) } }
    }
}
