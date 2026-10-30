package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class UsageArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = HistoryFixture(temporary.root.toPath().resolve("history.sqlite"))

    @Test fun `durable scalar archive merges with live copies and survives missing source database`() {
        val f = fixture()
        val v1 = f.session("original", 1)
        val v2 = f.session("copy", 2, identity = "original")
        f.message(v1, "original-message", "assistant", f.assistant(input = 3), 1)
        f.message(v2, "copied-message", "assistant", f.assistant(input = 20), 1)
        val before = f.csv()
        assertEquals(1, before.rows)
        assertTrue(before.csv, before.csv.contains("provider,model,high,1,20,5"))
        f.archive.archive(listOf(v1, v2))
        assertEquals(before, f.csv())
        f.execute("DELETE FROM session_message"); f.execute("DELETE FROM session_v2")
        f.execute("DELETE FROM message"); f.execute("DELETE FROM session")
        assertEquals(before, f.csv())
        HistoryDatabase.open(f.archive.archivePath).use { archive ->
            val stored = HistoryDatabase.query(archive, "SELECT data FROM usage_message") { it.getString(1) }.single()
            assertFalse(stored.contains("PRIVATE")); assertFalse(stored.contains("final answer"))
            assertEquals(1L, HistoryDatabase.scalar(archive, "PRAGMA user_version"))
        }
        Files.delete(f.path)
        assertEquals(before, f.csv())
    }

    @Test fun `live V1 timing and preserved provenance keep newer V2 token counts after original deletion`() {
        val f = fixture()
        val original = f.session("original", 1)
        val copy = f.session("copy", 2, identity = "original")
        f.message(original, "same-message", "assistant", f.assistant(created = 100, completed = 200, input = 3), 1)
        // V1 and V2 have separate primary-key namespaces.
        f.message(copy, "same-message", "assistant", f.assistant(created = 100, completed = 86_400_200, input = 20), 1)
        val expected = f.csv()
        f.database { database -> f.archive.preserveTimings(database, setOf("original")) {} }
        val archiveBytes = Files.readAllBytes(f.archive.archivePath)
        f.execute("DELETE FROM message"); f.execute("DELETE FROM session")
        assertEquals(expected, f.csv())
        assertArrayEquals(archiveBytes, Files.readAllBytes(f.archive.archivePath))
        val report = UsageReport(f.path, readAnnotations = { Json.obj() }) { _, _ -> error("No remote reads") }.build(true, 86_400_300)
        assertTrue(report, report.contains("| provider | model | 1 | 25 | <1s | 20 |"))
    }

    @Test fun `conflicting or incomplete timing sidecars fail closed without modifying them`() {
        val f = fixture()
        val original = f.session("original", 1)
        val copy = f.session("copy", 2, identity = "original")
        f.message(original, "same", "assistant", f.assistant(), 1)
        f.message(copy, "same", "assistant", f.assistant(completed = 999), 1)
        f.database { f.archive.preserveTimings(it, setOf("original")) {} }
        HistoryDatabase.open(f.archive.archivePath, writable = true).use { HistoryDatabase.execute(it, "UPDATE usage_original_timing SET time_completed=300") }
        val bytes = Files.readAllBytes(f.archive.archivePath)
        assertThrows(IllegalStateException::class.java) { f.csv() }
        assertArrayEquals(bytes, Files.readAllBytes(f.archive.archivePath))
        HistoryDatabase.open(f.archive.archivePath, writable = true).use { HistoryDatabase.execute(it, "PRAGMA user_version=0") }
        assertThrows(IllegalArgumentException::class.java) { f.csv() }
    }

    @Test fun `changed or malformed source cannot create an archive or authorize deletion`() {
        val f = fixture()
        val session = f.session("session")
        f.message(session, "assistant", "assistant", f.assistant(), 1)
        assertThrows(IllegalArgumentException::class.java) { f.archive.archive(listOf(session.copy(updated = 2))) }
        assertFalse(Files.exists(f.archive.archivePath))
        f.execute("UPDATE session_message SET data='invalid JSON'")
        assertThrows(IllegalArgumentException::class.java) { f.archive.archive(listOf(session)) }
        assertFalse(Files.exists(f.archive.archivePath))
        assertEquals(1L, f.count("session_v2"))
    }

    @Test fun `physical session IDs retain both verified attachment roots across history versions`() {
        val f = fixture()
        val v1 = f.session("shared", 1)
        f.message(v1, "old", "assistant", f.assistant(), 1)
        val v2 = f.session("shared", 2).copy(directory = temporary.root.toPath().resolve("moved").toString())
        f.execute("UPDATE session_v2 SET directory=?", v2.directory)
        f.message(v2, "new", "assistant", f.assistant(), 1)
        f.archive.archive(listOf(v2)); f.archive.archive(listOf(v1))
        val pending = f.archive.pending().single()
        assertEquals(setOf(v1.directory, v2.directory), pending.directories.toSet())
        assertTrue(pending.paths.any { it.startsWith(java.nio.file.Path.of(v1.directory)) })
        assertTrue(pending.paths.any { it.startsWith(java.nio.file.Path.of(v2.directory)) })
    }

    @Test fun `CSV quotes identifiers blocks formulas and refuses database aliases`() {
        val f = fixture()
        val session = f.session("session")
        f.message(session, "assistant", "assistant", f.assistant(variant = null).apply {
            addProperty("providerID", "=bad"); addProperty("modelID", "a,\"b")
        }, 1)
        val csv = f.csv().csv
        assertTrue(csv, csv.contains("'=bad,\"a,\"\"b\",unknown"))
        val symlink = temporary.root.toPath().resolve("history.csv")
        Files.createSymbolicLink(symlink, f.path)
        assertThrows(IllegalArgumentException::class.java) { UsageCsv.validateDestination(symlink, f.path) }
        Files.delete(symlink)
        Files.createLink(symlink, f.path)
        assertThrows(IllegalArgumentException::class.java) { UsageCsv.validateDestination(symlink, f.path) }
        assertEquals(1L, f.count("session_v2"))
    }

    @Test fun `cancelled CSV staging preserves the existing destination and removes its temporary file`() {
        val f = fixture(); val destination = temporary.root.toPath().resolve("usage.csv")
        Files.writeString(destination, "prior export")
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            UsageCsv.write(destination, f.path, UsageCsv.Export("new export", 1)) {
                if (++checks == 3) throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals("prior export", Files.readString(destination))
        assertFalse(Files.list(temporary.root.toPath()).use { entries -> entries.anyMatch { it.fileName.toString().startsWith(".opencode-usage-") } })
        UsageCsv.write(destination, f.path, UsageCsv.Export("new export", 1))
        assertEquals("new export", Files.readString(destination))
    }
}
