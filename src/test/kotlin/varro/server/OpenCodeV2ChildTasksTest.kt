package varro.server

import com.google.gson.JsonElement
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class OpenCodeV2ChildTasksTest {
    private class Fixture {
        val tasks = OpenCodeV2ChildTasks()
        var parent: String? = "parent"
        var location = "/repo"
        var parentLocation = "/repo"
        var available = true
        var linked = true
        var running = true
        var acknowledged = true
        var handedOff = false
        var mutation: (() -> Unit)? = null
        val writes = mutableListOf<String>()
        fun raw(method: String, path: String, body: JsonElement?): JsonElement {
            if (method == "POST") {
                assertEquals(Json.obj(), body); writes += path; mutation?.invoke()
                if (path.endsWith("/background")) { handedOff = true; return Json.obj() }
                return if (acknowledged) Json.obj("interrupted" to true) else Json.obj()
            }
            return when {
                path == "/api/session/child" -> Json.obj("data" to Json.obj("id" to "child", "parentID" to parent, "location" to Json.obj("directory" to location)))
                path == "/api/session/parent" -> Json.obj("data" to Json.obj("id" to "parent", "location" to Json.obj("directory" to parentLocation)))
                path == "/openapi.json" -> Json.obj("paths" to if (available) Json.obj(
                    "/api/session/{sessionID}/interrupt" to Json.obj("post" to Json.obj("operationId" to "session.interrupt", "parameters" to listOf(Json.obj("name" to "resume", "in" to "query", "schema" to Json.obj("enum" to listOf("false", "true")))))),
                    "/api/session/{sessionID}/background" to Json.obj("post" to Json.obj("operationId" to "session.background"))) else Json.obj())
                path.endsWith("/inbox") -> Json.obj("data" to if (handedOff && acknowledged) listOf(Json.obj("id" to "guidance", "type" to "synthetic", "payload" to Json.obj("text" to "User requested that active blocking work be moved to the background.\nKeep working."))) else emptyList<Any>())
                path.contains("/message?") -> Json.obj("data" to listOf(Json.obj("id" to "assistant", "type" to "assistant", "content" to listOf(Json.obj("id" to "tool", "type" to "tool", "name" to "subagent", "state" to Json.obj("status" to if (running) "running" else "completed", "input" to Json.obj(),
                    "metadata" to if (linked) Json.obj("sessionID" to "child", "status" to "running") else Json.obj()))))))
                else -> error(path)
            }
        }
        fun request(action: String = "child-task-controls", cancelled: () -> Boolean = { false }) =
            tasks.request("child", action, "/repo", RequestOptions(isCancelled = cancelled), ::raw)
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }

    @Test fun `only native children with advertised APIs expose controls`() {
        val f = Fixture()
        assertEquals(true, f.request().bool("stopChild")); assertEquals(true, f.request().bool("backgroundEligible"))
        f.parent = null
        assertEquals(false, f.request().bool("stopChild")); rejects { f.request("interrupt-child") }
        f.parent = "parent"; f.available = false
        assertEquals(false, f.request().bool("backgroundEligible")); rejects { f.request("background-parent") }
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `child stop never resolves root or stops shells and validates native acknowledgement`() {
        val f = Fixture()
        assertEquals(true, f.request("interrupt-child").bool("interrupted"))
        assertEquals(listOf("/api/session/child/interrupt?resume=false"), f.writes)
        f.acknowledged = false; rejects { f.request("interrupt-child") }
    }

    @Test fun `handoff needs foreground progress and durable guidance rather than HTTP success`() {
        val f = Fixture(); f.linked = false
        assertEquals(false, f.request().bool("backgroundEligible"))
        rejects { f.request("background-parent") }; assertTrue(f.writes.isEmpty())
        f.tasks.observe("session.tool.progress", Json.obj("sessionID" to "parent", "assistantMessageID" to "assistant", "id" to "tool", "metadata" to Json.obj("sessionID" to "child", "status" to "running")))
        assertEquals(true, f.request("background-parent").bool("backgrounded"))
        assertEquals(listOf("/api/session/parent/background"), f.writes)
        f.handedOff = false; f.acknowledged = false
        assertEquals(false, f.request("background-parent").bool("backgrounded"))
        f.tasks.observe("session.tool.success", Json.obj("sessionID" to "parent", "assistantMessageID" to "assistant", "id" to "tool"))
        assertEquals(false, f.request().bool("backgroundEligible"))
    }

    @Test fun `moved directories reset generations and cancellation fail without fallback`() {
        val f = Fixture(); f.location = "/foreign"
        rejects { f.request("interrupt-child") }; assertTrue(f.writes.isEmpty())
        f.location = "/repo"; f.parentLocation = "/foreign"
        rejects { f.request("background-parent") }; assertTrue(f.writes.isEmpty())
        f.parentLocation = "/repo"; f.mutation = f.tasks::reset
        rejects { f.request("interrupt-child") }
        assertEquals(1, f.writes.size)
        try { f.request("interrupt-child") { true }; fail() } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(1, f.writes.size)
    }
}
