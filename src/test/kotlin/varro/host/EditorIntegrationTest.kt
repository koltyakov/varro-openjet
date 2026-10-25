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
}
