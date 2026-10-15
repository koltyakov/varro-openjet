package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class WorkspaceDirectoryErrorsTest {
    @Test fun `only directory-specific 404 errors trigger recovery`() {
        for (message in listOf("404 Location not found", "404\nLocation not found", "404 {\"message\":\"Location not found: /old/repo\"}")) {
            assertTrue(message, WorkspaceDirectoryErrors.isMissing(message))
        }
        for (message in listOf("404 Session not found", "404 route not found", "401 Location not found", "500 Location not found", "Location not found")) {
            assertFalse(message, WorkspaceDirectoryErrors.isMissing(message))
        }
    }

    @Test fun `missing directory pages are incomplete rather than authoritative empty histories`() {
        val page = WorkspaceDirectoryErrors.recover("GET", "/session?limit=100", "/old/repo", IllegalStateException("404 Location not found")).asJsonObject
        assertTrue(page.bool("incomplete")!!)
        assertFalse(page.bool("hasMore")!!)
        assertEquals(0, page.arr("items")!!.size())
        assertEquals(listOf("/old/repo"), page.arr("unavailableDirectories")!!.strings())
        for (path in listOf("/session", "/session/status", "/session/ses_one")) {
            val failure = assertThrows(IllegalStateException::class.java) {
                WorkspaceDirectoryErrors.recover("GET", path, "/old/repo", IllegalStateException("404 Location not found"))
            }
            assertTrue(failure.message!!.contains("Restore the folder"))
            assertTrue(failure.message!!.contains("not been deleted or relocated"))
        }
        val original = IllegalArgumentException("404 Session not found")
        assertSame(original, assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDirectoryErrors.recover("GET", "/session?limit=1", "/repo", original)
        })
    }
}
