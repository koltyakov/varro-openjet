package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import java.nio.file.Files
import java.nio.file.Path

class FileReferencesTest {
    private fun fixture(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("file-references-")
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }

    private fun file(root: Path, relative: String): Path = root.resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "first\nsecond\nlast")
    }

    @Test fun `exact session file wins over workspace namesake`() = fixture { root ->
        file(root, "src/app.ts")
        val session = root.resolve("packages/app")
        val target = file(session, "src/app.ts")
        assertEquals(listOf(target), FileReferences.resolve("./src/app.ts", session.toString(), listOf(root.toString())))
    }

    @Test fun `host paths are already decoded and preserve literal URI characters`() = fixture { root ->
        val target = file(root, "src/a file+%20#name.ts")
        assertEquals(listOf(target), FileReferences.resolve("src/a file+%20#name.ts", root.toString(), emptyList()))
        assertEquals(listOf(target), FileReferences.resolve(target.toString(), null, emptyList()))
    }

    @Test fun `suffix matches stay inside the owning session and retain complete subpaths`() = fixture { root ->
        file(root, "models/file.py")
        val session = root.resolve("packages/app")
        val target = file(session, "src/models/file.py")
        file(session, "unrelated/file.py")
        for (reference in listOf("models/file.py", "./models/file.py")) {
            assertEquals(listOf(target), FileReferences.resolve(reference, session.toString(), listOf(root.toString())))
        }
    }

    @Test fun `nested sessions can fall back only to their owning workspace including its name prefix`() = fixture { root ->
        val session = Files.createDirectories(root.resolve("packages/app"))
        val target = file(session, "src/file.py")
        val other = Files.createDirectories(root.resolve("other"))
        for (reference in listOf("packages/app/src/file.py", "./packages/app/src/file.py", "${root.fileName}/packages/app/src/file.py")) {
            assertEquals(listOf(target), FileReferences.resolve(reference, session.toString(), listOf(other.toString(), root.toString())))
        }
    }

    @Test fun `external sessions never open a namesake in an unrelated project`() = fixture { root ->
        val unrelated = Files.createDirectories(root.resolve("other"))
        file(unrelated, "src/file.py")
        val session = Files.createDirectories(root.resolve("worktrees/feature"))
        assertTrue(FileReferences.resolve("src/file.py", session.toString(), listOf(unrelated.toString())).isEmpty())
        val target = file(session, "src/file.py")
        assertEquals(listOf(target), FileReferences.resolve("src/file.py", session.toString(), listOf(unrelated.toString())))
    }

    @Test fun `ambiguous suffixes return choices rather than guessing and exclude generated trees`() = fixture { root ->
        val first = file(root, "first/models/shared.ts")
        val second = file(root, "second/models/shared.ts")
        file(root, "unrelated/shared.ts")
        file(root, "node_modules/models/shared.ts")
        file(root, "build/models/shared.ts")
        assertEquals(listOf(first, second), FileReferences.resolve("models/shared.ts", root.toString(), emptyList()))
    }

    @Test fun `missing absolute references search their parent only and root references never scan the filesystem`() = fixture { root ->
        val target = file(root, "project/src/Shared.ts")
        file(root, "other/Shared.ts")
        assertEquals(listOf(target), FileReferences.resolve(root.resolve("project/Shared.ts").toString(), null, emptyList()))
        assertTrue(FileReferences.resolve("/varro-missing-root-file.ts", null, listOf(root.toString())).isEmpty())
    }

    @Test fun `suffix traversal is bounded and cancellable`() = fixture { root ->
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            FileReferences.resolve("missing.ts", root.toString(), emptyList()) { true }
        }
        repeat(105) { file(root, "dir-$it/shared.ts") }
        assertEquals(100, FileReferences.resolve("shared.ts", root.toString(), emptyList()).size)
    }

    @Test fun `authorized session and complete range reach the editor and cancellation survives`() {
        var authorized = false
        val status = FileReferences.open(Json.obj("path" to "models/file.py", "sessionID" to "session-1", "directory" to "/repo/packages/app",
            "line" to 710, "endLine" to 744), authorizeDirectory = { id, directory ->
            assertEquals("session-1", id); assertEquals("/repo/packages/app", directory); authorized = true; true
        }, open = { path, line, endLine, kind, directory ->
            assertTrue(authorized)
            assertEquals("models/file.py", path); assertEquals(710, line); assertEquals(744, endLine)
            assertNull(kind); assertEquals("/repo/packages/app", directory)
            "cancelled"
        })
        assertEquals("cancelled", status)
    }

    @Test fun `denied session authorization never resolves or opens a file`() {
        assertThrows(IllegalArgumentException::class.java) {
            FileReferences.open(Json.obj("path" to "file.py", "sessionID" to "session-1", "directory" to "/closed"),
                { _, _ -> false }, { _, _, _, _, _ -> error("Must not open") })
        }
    }

    @Test fun `invalid line ranges are rejected before authorization or opening`() {
        val payloads = listOf(
            Json.obj("endLine" to 10), Json.obj("line" to 10, "endLine" to 5), Json.obj("line" to 0, "endLine" to 5),
            Json.obj("line" to 1, "endLine" to 1.5), Json.obj("line" to "1"), Json.obj("line" to 2147483648L),
        )
        payloads.forEach { payload ->
            payload.addProperty("path", "file.py")
            assertThrows(IllegalArgumentException::class.java) {
                FileReferences.open(payload, { _, _ -> error("Must not authorize") }, { _, _, _, _, _ -> error("Must not open") })
            }
        }
    }

    @Test fun `line selections clamp to the document and include the final cited line`() {
        assertEquals(709..743, FileReferences.lineRange(710, 744, 800))
        assertEquals(2..2, FileReferences.lineRange(710, 744, 3))
        assertEquals(0..2, FileReferences.lineRange(1, 744, 3))
        assertEquals(1..1, FileReferences.lineRange(2, null, 3))
        assertEquals(0..0, FileReferences.lineRange(1, 3, 1))
    }

    @Test fun `session authorization follows exact descendant and git project history scope`() {
        val roots = listOf("/repo")
        var current = Json.obj("id" to "session-1", "directory" to "/repo/packages/app", "projectID" to "project-1")
        var project = Json.obj("id" to "project-1", "vcs" to "git")
        fun authorized(directory: String, scope: String) = FileReferences.authorizeDirectory("session-1", directory, roots, scope,
            { current }, { project })
        assertTrue(authorized("/repo", "directory"))
        assertFalse(authorized("/repo/packages/app", "directory"))
        assertTrue(authorized("/repo/packages/app", "descendants"))
        assertFalse(authorized("/repo-other", "descendants"))
        current.addProperty("directory", "/worktrees/feature")
        assertFalse(authorized("/worktrees/feature", "descendants"))
        assertTrue(authorized("/worktrees/feature", "project"))
        project.addProperty("id", "other-project")
        assertFalse(authorized("/worktrees/feature", "project"))
        project = Json.obj("id" to "global")
        current.addProperty("projectID", "global")
        assertFalse(authorized("/worktrees/feature", "project"))
        current.addProperty("directory", "/repo/scratch/no-project")
        assertTrue(authorized("/repo/scratch/no-project", "project"))
        current.add("time", Json.obj("archived" to null))
        assertTrue(authorized("/repo/scratch/no-project", "descendants"))
        current.addProperty("id", "other-session")
        assertFalse(authorized("/repo/scratch/no-project", "descendants"))
        current.addProperty("id", "session-1")
        current.add("time", Json.obj("archived" to 123))
        assertFalse(authorized("/repo/scratch/no-project", "descendants"))
    }
}
