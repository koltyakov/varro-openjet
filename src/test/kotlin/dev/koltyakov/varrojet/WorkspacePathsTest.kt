package dev.koltyakov.varrojet

import dev.koltyakov.varrojet.server.OpenCodeRequestScope
import dev.koltyakov.varrojet.server.WorkspacePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path identity decides which project owns a session and which events reach
 * which window, so the equivalences it claims have to be exact.
 */
class WorkspacePathsTest {

    @Test
    fun `treats separator and trailing-slash variants as the same path`() {
        assertTrue(WorkspacePaths.isSame("/home/dev/project", "/home/dev/project/"))
        assertTrue(WorkspacePaths.isSame("/home/dev//project", "/home/dev/project"))
        assertFalse(WorkspacePaths.isSame("/home/dev/project", "/home/dev/other"))
    }

    @Test
    fun `null and blank paths never match`() {
        assertFalse(WorkspacePaths.isSame(null, null))
        assertFalse(WorkspacePaths.isSame("", ""))
        assertFalse(WorkspacePaths.isSame("/home/dev", null))
    }

    @Test
    fun `windows drive paths compare case-insensitively across separators`() {
        assertTrue(WorkspacePaths.isSame("""C:\Users\dev\project""", "c:/users/dev/project"))
        assertTrue(WorkspacePaths.isSame("""C:\Users\dev\project\""", """C:\Users\DEV\Project"""))
        assertFalse(WorkspacePaths.isSame("""C:\Users\dev""", """D:\Users\dev"""))
    }

    @Test
    fun `windows extended-length and UNC forms normalize`() {
        assertTrue(WorkspacePaths.isSame("""\\?\C:\work\app""", """C:\work\app"""))
        assertTrue(WorkspacePaths.isSame("""\\server\share\app""", "//SERVER/Share/app"))
        // A device path is not a workspace.
        assertFalse(WorkspacePaths.isSame("""\\.\PIPE\x""", """\\.\PIPE\x"""))
    }

    @Test
    fun `relative paths preserve display casing`() {
        assertEquals(
            "src/Main.kt",
            WorkspacePaths.relativeWithin("/home/dev/project/src/Main.kt", "/home/dev/project"),
        )
        assertEquals(".", WorkspacePaths.relativeWithin("/home/dev/project", "/home/dev/project/"))
        assertNull(WorkspacePaths.relativeWithin("/home/dev/other/file", "/home/dev/project"))
    }

    @Test
    fun `windows relative paths match case-insensitively but display as written`() {
        assertEquals(
            "src/Main.kt",
            WorkspacePaths.relativeWithin("""C:\Work\App\src\Main.kt""", """c:\work\app"""),
        )
    }
}

/**
 * Request scoping decides which workspace OpenCode resolves a session against;
 * an incorrect rewrite here shows up as sessions that cannot be found.
 */
class OpenCodeRequestScopeTest {

    private val base = "http://127.0.0.1:4096"

    @Test
    fun `adds the directory to non-global paths`() {
        val scoped = OpenCodeRequestScope.scope(base, "/session", "/home/dev/project")
        assertTrue(scoped.url.contains("directory=%2Fhome%2Fdev%2Fproject"))
        assertEquals("/home/dev/project", scoped.directory)
    }

    @Test
    fun `leaves global paths unscoped`() {
        val scoped = OpenCodeRequestScope.scope(base, "/global/event", "/home/dev/project")
        assertFalse(scoped.url.contains("directory="))
    }

    @Test
    fun `api paths also carry the bracketed location form`() {
        val scoped = OpenCodeRequestScope.scope(base, "/api/session", "/home/dev/project")
        assertTrue(scoped.url.contains("location%5Bdirectory%5D="))
    }

    @Test
    fun `an explicit directory in the path wins over the ambient one`() {
        val scoped = OpenCodeRequestScope.scope(base, "/session?directory=/explicit", "/ambient")
        assertEquals("/explicit", scoped.directory)
        assertFalse(scoped.url.contains("ambient"))
    }

    @Test
    fun `rejects paths that would leave the server origin`() {
        listOf("//evil.example.com/x", "http://evil.example.com/x", "session").forEach { path ->
            runCatching { OpenCodeRequestScope.scope(base, path, null) }
                .onSuccess { error("Expected $path to be rejected") }
        }
    }

    @Test
    fun `normalizes trailing separators without rewriting path identity`() {
        assertEquals("/home/dev/project", OpenCodeRequestScope.normalizeDirectory("/home/dev/project/"))
        // Drive and share roots have no redundant separator to drop.
        assertEquals("""C:\""", OpenCodeRequestScope.normalizeDirectory("""C:\"""))
        // Casing is preserved: OpenCode lookups on Windows regress when it is not.
        assertEquals("""C:\Work\App""", OpenCodeRequestScope.normalizeDirectory("""C:\Work\App\"""))
        assertNull(OpenCodeRequestScope.normalizeDirectory("   "))
        assertNull(OpenCodeRequestScope.normalizeDirectory(null))
    }
}
