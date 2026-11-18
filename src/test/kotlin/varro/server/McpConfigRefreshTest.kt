package varro.server

import org.junit.Assert.*
import org.junit.Test

class McpConfigRefreshTest {
    @Test fun `defaults save during busy retry permission and question states while only reload waits`() {
        for (state in listOf("busy", "retry", "permission", "question", "other-project")) {
            var idle = false
            val calls = mutableListOf<Boolean>()
            val refresh = McpConfigRefresh({ idle }) { calls.add(it); true }
            refresh.request(false)
            refresh.flush()
            assertTrue(state, calls.isEmpty())
            refresh.request(true)
            idle = true
            refresh.flush(); refresh.flush()
            assertEquals(state, listOf(true), calls)
        }
    }

    @Test fun `failed reload and newer saves remain pending without replaying a successful settled reload`() {
        var succeeds = false
        var calls = 0
        lateinit var refresh: McpConfigRefresh
        refresh = McpConfigRefresh({ true }) {
            calls++
            if (calls == 2) refresh.request(true)
            succeeds
        }
        refresh.request(false)
        refresh.flush()
        succeeds = true
        refresh.flush(); refresh.flush(); refresh.flush()
        assertEquals(3, calls)
    }
}
