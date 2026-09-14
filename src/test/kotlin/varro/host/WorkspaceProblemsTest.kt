package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.int
import varro.protocol.str

class WorkspaceProblemsTest {
    @Test fun `workspace picker includes only errors and warnings`() {
        assertTrue(WorkspaceProblems.isIssue(Json.obj("severity" to "error")))
        assertTrue(WorkspaceProblems.isIssue(Json.obj("severity" to "warning")))
        assertFalse(WorkspaceProblems.isIssue(Json.obj("severity" to "info")))
    }

    @Test fun `selection intersections are half open and include point diagnostics`() {
        assertTrue(WorkspaceProblems.intersects(12, 16, 10, 20))
        assertFalse(WorkspaceProblems.intersects(12, 16, 0, 12))
        assertFalse(WorkspaceProblems.intersects(12, 16, 16, 20))
        assertTrue(WorkspaceProblems.intersects(10, 10, 10, 20))
        assertFalse(WorkspaceProblems.intersects(20, 20, 10, 20))
        assertFalse(WorkspaceProblems.intersects(12, 16, 14, 14))
    }

    @Test fun `selection warning survives truncation and counts cover all diagnostics`() {
        val errors = (1..25).map { Json.obj("severity" to "error", "line" to it, "message" to "Error $it") }
        val warning = Json.obj("severity" to "warning", "line" to 41, "message" to "Selected\nwarning", "intersectsSelection" to true)
        val info = Json.obj("severity" to "info", "line" to 41, "intersectsSelection" to true)
        val entries = errors + warning + info
        val ranked = WorkspaceProblems.ranked(entries, 41)
        assertEquals(20, ranked.size)
        assertEquals("Selected\nwarning", ranked.first().str("message"))
        assertEquals(25, WorkspaceProblems.counts(entries).int("errors"))
        assertEquals(1, WorkspaceProblems.counts(entries).int("warnings"))
        assertEquals(25, ranked[1].int("line"))
        warning.addProperty("intersectsSelection", false)
        assertEquals("error", WorkspaceProblems.ranked(entries, 41).first().str("severity"))
    }

    @Test fun `workspace problems endpoint only accepts a queryless GET`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/varro/workspace-problems"))
        assertFalse(ApiRoutes.isAllowed("POST", "/varro/workspace-problems"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/workspace-problems?path=/outside"))
    }
}
