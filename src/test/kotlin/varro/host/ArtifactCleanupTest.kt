package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class ArtifactCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = HistoryFixture(temporary.root.toPath().resolve("history.db"))

    @Test fun `shared names in dormant V1 and pending inbox survive one batched reference scan`() {
        val f = fixture(); val deleted = f.session("deleted"); val remaining = f.session("remaining", 1)
        val tools = Files.createDirectories(f.path.parent.resolve("tool-output"))
        val unshared = Files.writeString(tools.resolve("tool_unshared"), "unshared body")
        val shared = Files.writeString(tools.resolve("tool_shared"), "shared body")
        val queued = Files.writeString(tools.resolve("tool_queued"), "queued body")
        f.message(deleted, "assistant", "assistant", f.assistant().apply { add("files", Json.array(listOf(unshared, shared, queued).map { Json.obj("url" to it.toUri().toString()) })) }, 1)
        f.message(remaining, "v1-reference", "user", Json.obj("text" to "keep tool_shared here"), 1)
        f.execute("INSERT INTO session_inbox VALUES('queued','remaining',?)", Json.obj("text" to "tool_queued").toString())
        val attachment = Files.createDirectories(java.nio.file.Path.of(deleted.directory, ".opencode", "attachments", deleted.id))
        Files.writeString(attachment.resolve("copy.txt"), "owned copy")
        f.archive.archive(listOf(deleted))
        f.execute("DELETE FROM session_message"); f.execute("DELETE FROM session_v2")
        ArtifactCleanup.clean(f.archive, f.archive.pending())
        assertFalse(Files.exists(unshared)); assertFalse(Files.exists(attachment))
        assertTrue(Files.exists(shared)); assertTrue(Files.exists(queued))
        assertEquals(setOf(shared, queued), f.archive.pending().single().paths.toSet())
        f.execute("DELETE FROM message"); f.execute("DELETE FROM session_inbox")
        ArtifactCleanup.clean(f.archive, f.archive.pending())
        assertFalse(Files.exists(shared)); assertFalse(Files.exists(queued)); assertTrue(f.archive.pending().isEmpty())
    }

    @Test fun `cancellation or malformed remaining history removes no files and retains journal`() {
        val f = fixture(); val session = f.session("deleted")
        val attachment = Files.createDirectories(java.nio.file.Path.of(session.directory, ".opencode", "attachments", session.id))
        val owned = Files.writeString(attachment.resolve("copy.txt"), "owned")
        f.archive.archive(listOf(session))
        val pending = f.archive.pending()
        assertThrows(java.util.concurrent.CancellationException::class.java) { ArtifactCleanup.clean(f.archive, pending, { throw java.util.concurrent.CancellationException() }) }
        assertTrue(Files.exists(owned)); assertFalse(f.archive.pending().isEmpty())
    }

    @Test fun `symlinked attachment directories and forged journal paths cannot remove external content`() {
        val f = fixture(); val session = f.session("deleted")
        val external = temporary.newFolder("external").toPath()
        val original = Files.writeString(external.resolve("original.txt"), "Keep original")
        val attachments = Files.createDirectories(java.nio.file.Path.of(session.directory, ".opencode", "attachments"))
        Files.createSymbolicLink(attachments.resolve(session.id), external)
        f.archive.archive(listOf(session))
        f.execute("DELETE FROM session_v2")
        assertThrows(IllegalArgumentException::class.java) { ArtifactCleanup.clean(f.archive, f.archive.pending()) }
        assertEquals("Keep original", Files.readString(original))
        Files.delete(attachments.resolve(session.id))
        HistoryDatabase.open(f.archive.archivePath, writable = true).use { HistoryDatabase.execute(it, "INSERT INTO usage_artifact VALUES(?,?)", session.id, original.toString()) }
        assertThrows(IllegalStateException::class.java) { ArtifactCleanup.clean(f.archive, f.archive.pending()) }
        assertEquals("Keep original", Files.readString(original))
    }
}
