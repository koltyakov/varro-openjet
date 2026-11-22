package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.*
import java.util.concurrent.CancellationException

class FollowupUsageTest {
    @Test fun `workspace hidden and canonical identity checks precede all history reads on both backends`() {
        for (version in listOf(1, 2)) {
            var calls = 0
            var session = Json.obj("id" to "child", "directory" to "/repo")
            val reader = FollowupUsage({ _, path, _, options ->
                calls++
                assertEquals("/repo", options.directory)
                OpenCodeResponse(if (path.endsWith("followup-usage")) Json.obj("supported" to true) else session)
            }, { version }, { "connection" }, { listOf("/repo") }, { it == "hidden" })
            assertThrows(IllegalArgumentException::class.java) { reader.read("hidden", null, "/repo") { false } }
            assertThrows(IllegalArgumentException::class.java) { reader.read("child", Json.obj(), "/repo") { false } }
            assertEquals(0, calls)
            assertEquals(version == 2, reader.read("child", null, "/repo") { false }.asObjectOrNull().bool("supported"))
            assertEquals(if (version == 2) 2 else 1, calls)
            for (foreign in listOf(Json.obj("id" to "other", "directory" to "/repo"), Json.obj("id" to "child", "directory" to "/foreign"))) {
                session = foreign
                calls = 0
                assertThrows(IllegalArgumentException::class.java) { reader.read("child", null, "/repo") { false } }
                assertEquals(1, calls)
            }
        }
    }

    @Test fun `verified transfers work but cancellation and changed connections prevent late reads or responses`() {
        var generation = 1
        var calls = 0
        var changed = false
        val reader = FollowupUsage({ _, path, _, _ ->
            calls++
            if (changed) generation++
            OpenCodeResponse(if (path.endsWith("followup-usage")) Json.obj("supported" to true)
                else Json.obj("id" to "child", "directory" to "/transferred"))
        }, { 2 }, { generation }, { listOf("/repo") }, { false }, { id, directory -> id == "child" && directory == "/transferred" })
        assertEquals(true, reader.read("child", null, "/transferred") { false }.asObjectOrNull().bool("supported"))
        assertThrows(CancellationException::class.java) { reader.read("child", null, "/transferred") { true } }
        assertEquals(2, calls)
        changed = true
        assertThrows(IllegalStateException::class.java) { reader.read("child", null, "/transferred") { false } }
        assertEquals(3, calls)
    }
}
