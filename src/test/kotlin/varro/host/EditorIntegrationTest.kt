package varro.host

import org.junit.Assert.*
import org.junit.Test

class EditorIntegrationTest {
    @Test fun `response files without a line use registered editors rather than forcing text`() {
        for (line in listOf(null, 0, -1)) {
            var opened = 0
            assertEquals("opened", EditorIntegration.openResponseFile(line,
                openRegistered = { opened++; true },
                openAtLine = { error("Must not force a text editor") },
            ))
            assertEquals(1, opened)
        }
    }

    @Test fun `positive line targets preserve zero based text navigation`() {
        for (line in listOf(1, 42)) {
            assertEquals("opened", EditorIntegration.openResponseFile(line,
                openRegistered = { error("Must preserve the line target") },
                openAtLine = { assertEquals(line - 1, it); true },
            ))
        }
    }

    @Test fun `unavailable registered editors do not fall back to text or report success`() {
        assertEquals("unavailable", EditorIntegration.openResponseFile(null,
            openRegistered = { false },
            openAtLine = { error("Must not retry as text") },
        ))
        assertEquals("unavailable", EditorIntegration.openResponseFile(1,
            openRegistered = { error("Must not retry with another editor") },
            openAtLine = { false },
        ))
    }

    @Test fun `failed registered editor actions are not replayed as text`() {
        val failure = IllegalStateException("Editor failed to open")
        try {
            EditorIntegration.openResponseFile(null,
                openRegistered = { throw failure },
                openAtLine = { error("Must not replay a failed open") },
            )
            fail("Expected the editor failure")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
    }

    @Test fun `webview file reads stay inside open roots`() {
        val base = java.nio.file.Files.createTempDirectory("varro-editor-roots").toRealPath()
        try {
            val root = java.nio.file.Files.createDirectories(base.resolve("project"))
            val sibling = java.nio.file.Files.createDirectories(base.resolve("sibling"))
            val outside = java.nio.file.Files.createDirectories(base.resolve("outside"))
            val inside = java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(root.resolve("src")).resolve("a.ts"), "a")
            val siblingFile = java.nio.file.Files.writeString(sibling.resolve("b.ts"), "b")
            val secret = java.nio.file.Files.writeString(outside.resolve("secret"), "s")
            val roots = listOf(root.toString(), sibling.toString())

            assertEquals(inside, EditorIntegration.resolveWithinRoots("src/a.ts", roots))
            assertEquals(inside, EditorIntegration.resolveWithinRoots(inside.toString(), roots))
            assertEquals(siblingFile, EditorIntegration.resolveWithinRoots("b.ts", roots))
            assertNull(EditorIntegration.resolveWithinRoots(secret.toString(), roots))
            assertNull(EditorIntegration.resolveWithinRoots("../outside/secret", roots))
            assertNull(EditorIntegration.resolveWithinRoots("src/../../outside/secret", roots))
            assertNull(EditorIntegration.resolveWithinRoots("src\\..\\..\\outside\\secret", roots))
            assertNull(EditorIntegration.resolveWithinRoots("", roots))
            assertNull(EditorIntegration.resolveWithinRoots("missing.ts", roots))
            assertNull(EditorIntegration.resolveWithinRoots(inside.toString(), emptyList()))

            val link = root.resolve("escape")
            runCatching { java.nio.file.Files.createSymbolicLink(link, outside) }.onSuccess {
                assertNull(EditorIntegration.resolveWithinRoots("escape/secret", roots))
                assertNull(EditorIntegration.resolveWithinRoots(link.resolve("secret").toString(), roots))
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
