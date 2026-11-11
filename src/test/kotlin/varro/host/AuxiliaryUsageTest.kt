package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class AuxiliaryUsageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun tokens(input: Int, output: Int = 0) = Json.obj("input" to input, "output" to output, "reasoning" to 0, "cache" to Json.obj("read" to 0, "write" to 0))

    @Test fun `auxiliary events and cumulative remainder survive archival without model or prompt attribution`() {
        val f = HistoryFixture(temporary.root.toPath().resolve("usage.db"))
        val session = f.session("s", updated = 1000)
        f.message(session, "assistant", "assistant", f.assistant(created = now - 1000, completed = now - 500, input = 10), 1)
        f.execute("CREATE TABLE event(id TEXT PRIMARY KEY,aggregate_id TEXT,type TEXT,created INTEGER,data TEXT)")
        for (column in listOf("cost", "tokens_input", "tokens_output", "tokens_reasoning", "tokens_cache_read", "tokens_cache_write"))
            f.execute("ALTER TABLE session_v2 ADD COLUMN $column NUMERIC NOT NULL DEFAULT 0")
        // Message 15 + recorded auxiliary 3 + undated remainder 4 = native 22 tokens.
        f.execute("UPDATE session_v2 SET cost=0.75,tokens_input=17,tokens_output=5")
        val event = Json.obj("sessionID" to "s", "source" to "generate", "tokens" to tokens(3), "cost" to 0.25,
            "providerID" to "must-not-attribute", "parentID" to "not-a-prompt", "private" to "PRIVATE")
        f.execute("INSERT INTO event VALUES('event','s','session.usage.recorded.1',?,?)", now - 100, event.toString())
        val before = Files.readAllBytes(f.path)
        val report = UsageReport(f.path, readAnnotations = { Json.obj() }) { _, _ -> error("No API") }.build(true, now)
        assertArrayEquals(before, Files.readAllBytes(f.path))
        assertTrue(report, report.contains("| Unattributed | Session generation | 0 | 3 | - |"))
        assertTrue(report, report.contains("| Unattributed | Other session usage | 0 | 4 | - |"))
        val today = report.substringAfter("## Today").substringBefore("## Last 7")
        assertFalse(today.contains("Other session usage"))
        val csv = f.csv()
        assertEquals(3, csv.rows)
        assertTrue(csv.csv, csv.csv.contains(",generate,1,1"))
        assertTrue(csv.csv, csv.csv.contains("unknown,unknown,unknown,unknown,0,4,0,0,0,0,4,0.25,0,unrecorded,,"))
        assertFalse(csv.csv.contains("must-not-attribute"))
        f.archive.archive(listOf(session))
        f.execute("DELETE FROM event"); f.execute("DELETE FROM session_message"); f.execute("DELETE FROM session_v2")
        assertEquals(csv, f.csv())
        HistoryDatabase.open(f.archive.archivePath).use { archive ->
            val stored = HistoryDatabase.query(archive, "SELECT data FROM usage_auxiliary") { it.getString(1) }
            assertEquals(2, stored.size); assertTrue(stored.none { it.contains("PRIVATE") })
        }
        Files.delete(f.path)
        assertEquals(csv, f.csv())
    }

    @Test fun `finite logs validate ownership sync and sources and deduplicate retained events`() {
        val event = Json.obj("id" to "evt", "type" to "session.usage.recorded", "created" to now,
            "durable" to Json.obj("aggregateID" to "s"), "data" to Json.obj("sessionID" to "s", "source" to "generate", "tokens" to tokens(7)))
        val synced = Json.obj("type" to "log.synced", "aggregateID" to "s")
        fun read(vararg events: com.google.gson.JsonObject) = AuxiliaryUsage.log(Json.toElement(events.joinToString("") { "data: $it\n\n" }), "s")
        assertEquals(1, read(event, event, synced).size)
        assertThrows(IllegalArgumentException::class.java) { read(event) }
        assertThrows(IllegalArgumentException::class.java) { read(event.deepCopy().apply { obj("durable")!!.addProperty("aggregateID", "foreign") }, synced) }
        assertThrows(IllegalArgumentException::class.java) { read(event.deepCopy().apply { obj("data")!!.addProperty("source", "step") }, synced) }
        assertThrows(IllegalArgumentException::class.java) { read(synced, event) }
    }

    @Test fun `malformed auxiliary events fail closed before archival`() {
        val f = HistoryFixture(temporary.root.toPath().resolve("invalid.db"))
        val session = f.session("s")
        f.execute("CREATE TABLE event(id TEXT PRIMARY KEY,aggregate_id TEXT,type TEXT,created INTEGER,data TEXT)")
        f.execute("INSERT INTO event VALUES('event','s','session.usage.recorded.1',100,?)",
            Json.obj("sessionID" to "foreign", "source" to "generate", "tokens" to tokens(7)).toString())
        assertThrows(IllegalArgumentException::class.java) { f.archive.archive(listOf(session)) }
        assertFalse(Files.exists(f.archive.archivePath))
    }
}
