package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant

class DatabaseMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = HistoryFixture(temporary.root.toPath().resolve("history.db"))

    @Test fun `explicit offline compaction reclaims reusable pages without changing history`() {
        val f = fixture(); f.session("session")
        f.execute("CREATE TABLE padding(data BLOB)"); f.execute("INSERT INTO padding VALUES(zeroblob(2000000))")
        f.execute("DELETE FROM padding")
        val before = DatabaseMaintenance.inspect(f.path)
        assertTrue(before.reusable > 1_000_000)
        assertTrue(before.required >= before.bytes * 2 + 64L * 1024 * 1024)
        val after = DatabaseMaintenance.compact(f.path)
        assertTrue(after.bytes < before.bytes)
        assertEquals(0L, after.reusable)
        assertEquals(1L, f.count("session_v2"))
        assertFalse(Files.exists(f.archive.archivePath))
        assertFalse(Files.exists(f.archive.transcriptPath))
    }

    @Test fun `compaction rejects linked databases and cancellation leaves data available`() {
        val f = fixture(); f.session("session")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            DatabaseMaintenance.compact(f.path, { throw java.util.concurrent.CancellationException() })
        }
        assertEquals(1L, f.count("session_v2"))
        val link = temporary.root.toPath().resolve("link.db")
        Files.createSymbolicLink(link, f.path)
        assertThrows(IllegalArgumentException::class.java) { DatabaseMaintenance.compact(link) }
        Files.delete(link); Files.createLink(link, f.path)
        assertThrows(IllegalArgumentException::class.java) { DatabaseMaintenance.compact(link) }
    }

    @Test fun `About counts physical sessions and oldest stored history without message reads`() {
        val f = fixture()
        f.session("old", 1); f.session("new", 2)
        f.execute("DROP TABLE message"); f.execute("DROP TABLE session_message")
        val now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        f.execute("UPDATE session SET time_created=?", Instant.parse("2026-01-30T12:00:00Z").toEpochMilli())
        f.execute("UPDATE session_v2 SET time_created=?", Instant.parse("2026-09-30T12:00:00Z").toEpochMilli())
        val report = AboutDatabase.markdown(f.path, false, now)
        assertTrue(report, report.contains("History age: 9 months")); assertTrue(report, report.contains("Sessions: 2"))
        assertFalse(AboutDatabase.markdown(f.path, true, now).contains(f.path.toString()))
        f.execute("DELETE FROM session")
        assertTrue(AboutDatabase.markdown(f.path, false, now).contains("History age: 1 month"))
        assertEquals("Unknown", AboutDatabase.age(now + 1, now))
        assertEquals("<1 day", AboutDatabase.age(now - 1000, now))
    }
}
