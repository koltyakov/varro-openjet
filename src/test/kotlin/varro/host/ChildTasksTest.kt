package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeResponse

class ChildTasksTest {
    @Test fun `host rejects foreign and hidden parent locations before mutations`() {
        for (foreign in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val host = ChildTasks({ method, path, _, options ->
                options.checkCancelled(); calls += "$method $path"
                assertEquals(2L * 1024 * 1024, options.maxResponseBytes); assertTrue(options.cancellableBody)
                OpenCodeResponse(Json.obj("parentID" to "parent", "directory" to if (foreign) "/foreign" else "/repo"))
            }, { 2 }, { "connection" }, { listOf("/repo") }, { it == "parent" })
            try { host.request("POST", "/session/child/interrupt-child", null, "/repo") { false }; fail() }
            catch (expected: IllegalArgumentException) { assertEquals("404 Session not found", expected.message) }
            assertEquals(listOf("GET /session/child/child-task-controls"), calls)
        }
    }

    @Test fun `V1 controls are absent and native requests have no body or lifecycle fallback`() {
        val host = ChildTasks({ _, _, _, _ -> error("V1 must not reach native routes") }, { 1 }, { 1 }, { listOf("/repo") }, { false })
        assertEquals(false, host.request("GET", "/session/child/child-task-controls", null, "/repo") { false }.asObjectOrNull().bool("stopChild"))
        for (body in listOf(null, Json.obj())) try {
            host.request("POST", "/session/child/interrupt-child", body, "/repo") { false }; fail()
        } catch (_: IllegalArgumentException) { }
    }
}
