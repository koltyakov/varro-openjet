package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CancellationException

class LegacySessionCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = HistoryFixture(temporary.root.toPath().resolve("history.db"))

    @Test fun `offline deletion preserves timing and usage without rewriting retained V2 rows`() {
        val f = fixture()
        val original = f.session("original", 1)
        val copy = f.session("copy", 2, identity = "original")
        f.message(original, "same", "assistant", f.assistant(input = 3), 1)
        f.message(copy, "same", "assistant", f.assistant(input = 20, completed = 86_400_200), 1)
        val before = f.csv()
        val native = f.database { HistoryDatabase.query(it, "SELECT data FROM session_message") { row -> row.getString(1) } }
        var dispatch = 0
        LegacySessionCleanup(f.archive).delete(listOf(original), 2000, false, {}, { dispatch++ })
        assertEquals(1, dispatch)
        assertEquals(0L, f.count("session")); assertEquals(0L, f.count("message"))
        assertEquals(1L, f.count("session_v2"))
        assertEquals(native, f.database { HistoryDatabase.query(it, "SELECT data FROM session_message") { row -> row.getString(1) } })
        assertEquals(before, f.csv())
    }

    @Test fun `newer descendants trigger rejection before preservation or removal`() {
        val f = fixture(); val parent = f.session("parent", 1); f.session("child", 1, updated = 5000, parent = parent.id)
        assertThrows(IllegalArgumentException::class.java) { LegacySessionCleanup(f.archive).delete(listOf(parent), 2000, false, {}, { error("Must not dispatch") }) }
        assertEquals(2L, f.count("session")); assertFalse(Files.exists(f.archive.archivePath))
    }

    @Test fun `unrecognized triggers and cross-version dependencies fail closed`() {
        val f = fixture(); val session = f.session("session", 1)
        f.execute("CREATE TRIGGER unexpected AFTER DELETE ON session BEGIN DELETE FROM session_v2; END")
        assertThrows(IllegalArgumentException::class.java) { LegacySessionCleanup(f.archive).delete(listOf(session), 2000, false, {}, { error("Must not dispatch") }) }
        assertEquals(1L, f.count("session"))
        f.execute("DROP TRIGGER unexpected")
        f.execute("CREATE TABLE cross_version(id TEXT PRIMARY KEY,source TEXT REFERENCES session(id))")
        assertThrows(IllegalArgumentException::class.java) { LegacySessionCleanup(f.archive).delete(listOf(session), 2000, false, {}, { error("Must not dispatch") }) }
        assertEquals(1L, f.count("session"))
    }

    @Test fun `cancellation before dispatch rolls back live transaction while complete archives remain safe`() {
        val f = fixture(); val session = f.session("session", 1)
        f.message(session, "assistant", "assistant", f.assistant(), 1)
        var archiveSeen = false
        assertThrows(CancellationException::class.java) {
            LegacySessionCleanup(f.archive).delete(listOf(session), 2000, false, {
                if (Files.exists(f.archive.archivePath) && Files.size(f.archive.archivePath) > 0) {
                    archiveSeen = true; throw CancellationException("Fixture cancelled")
                }
            }, { error("Must not dispatch") })
        }
        assertTrue(archiveSeen)
        assertEquals(1L, f.count("session")); assertEquals(1L, f.count("message"))
    }

    @Test fun `children delete before parents and another writer cannot enter the final transaction`() {
        val f = fixture(); val parent = f.session("parent", 1); val child = f.session("child", 1, parent = parent.id)
        f.message(child, "assistant", "assistant", f.assistant(), 1)
        var locked = false
        LegacySessionCleanup(f.archive).delete(listOf(parent, child), 2000, false, {}, {
            f.database { other ->
                HistoryDatabase.execute(other, "PRAGMA busy_timeout=1")
                assertThrows(java.sql.SQLException::class.java) { HistoryDatabase.execute(other, "UPDATE session SET time_updated=9000") }
                locked = true
            }
        })
        assertTrue(locked); assertEquals(0L, f.count("session"))
    }
}
