package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class InternalHelperSessionsTest {
    @Test fun `metadata identifies helpers without relying on their titles`() {
        for (kind in listOf("permission-judge", "commit-message", "prompt-completion"))
            assertTrue(InternalHelperSessions.isHidden(Json.obj("metadata" to Json.obj("varroInternal" to kind))))
        assertFalse(InternalHelperSessions.isHidden(Json.obj("metadata" to Json.obj("varroInternal" to "ordinary"))))
    }

    @Test fun `legacy completion titles require the exact deny-all helper permissions`() {
        val title = "Varro prompt completion: 1"
        val rules = Json.array(listOf(Json.obj("permission" to "*", "pattern" to "*", "action" to "deny"),
            Json.obj("permission" to "StructuredOutput", "pattern" to "*", "action" to "allow")))
        assertTrue(InternalHelperSessions.isHidden(Json.obj("title" to title, "permission" to rules)))
        assertFalse(InternalHelperSessions.isHidden(Json.obj("title" to title)))
        assertFalse(InternalHelperSessions.isHidden(Json.obj("title" to "My conversation", "permission" to rules)))
        assertFalse(InternalHelperSessions.isHidden(Json.obj("title" to title, "permission" to Json.array(rules.reversed()))))
    }
}
