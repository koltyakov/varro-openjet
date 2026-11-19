package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.ApiRoutes
import varro.protocol.*
import java.util.concurrent.CancellationException

class OpenCodeV2GoalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun goal() = Json.obj("id" to "goal-1", "objective" to "Wait for 1 minute", "status" to "active",
        "rounds" to 0, "maxRounds" to 50, "updatedAt" to 1)
    private fun kickoff() = Json.obj("id" to "msg_goal", "type" to "synthetic", "description" to "Goal: Wait for 1 minute",
        "text" to "A goal is now active.\n<untrusted_objective>\nWait for 1 minute\n</untrusted_objective>\nStart now.",
        "metadata" to Json.obj("source" to "goal", "goalID" to "goal-1"), "time" to Json.obj("created" to 1))
    private fun native(wire: (String, String, JsonElement?, RequestOptions) -> JsonElement?) =
        OpenCodeV2Adapter({ method, path, body, options -> OpenCodeResponse(wire(method, path, body, options)) },
            OpenCodeV2SessionState(temporary.root.toPath()))
    private fun assistant() = Json.obj("id" to "msg_reply", "type" to "assistant", "agent" to "build",
        "model" to Json.obj("providerID" to "fixture", "id" to "fixture"), "time" to Json.obj("created" to 2),
        "content" to listOf(Json.obj("type" to "text", "text" to "Done")))

    @Test fun `goal inspection is scoped read-only RPC with strict public route allowlisting`() {
        val calls = mutableListOf<String>()
        val adapter = native { method, path, body, options ->
            calls += "$method $path"
            assertTrue(options.unscoped)
            assertEquals(Json.obj("input" to Json.obj("sessionID" to "ses_one")), body)
            Json.obj("output" to goal())
        }
        assertEquals(goal(), adapter.request("GET", "/session/ses_one/goal?directory=%2Frepo", null, RequestOptions()).data)
        assertEquals(listOf("POST /api/rpc/opencode.goal/get?location%5Bdirectory%5D=%2Frepo"), calls)
        for (path in listOf("/session/ses_one/goal", "/session/ses_one/goal?directory=%2Frepo")) assertTrue(ApiRoutes.isAllowed("GET", path))
        assertFalse(ApiRoutes.isAllowed("POST", "/session/ses_one/goal"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session/ses_one/goal?sessionID=ses_other"))
        assertFalse(ApiRoutes.isAllowed("POST", "/api/rpc/opencode.goal/get"))
        assertFalse(ApiRoutes.isAllowed("GET", "/api/rpc/opencode.goal/get"))
    }

    @Test fun `absent malformed unsupported and cancelled goal reads never trigger a fallback or mutation`() {
        assertTrue(native { _, _, _, _ -> Json.obj("output" to null) }
            .request("GET", "/session/ses_one/goal", null, RequestOptions()).data!!.isJsonNull)
        for (response in listOf(Json.obj(), Json.obj("data" to goal()), Json.obj("output" to Json.obj()))) {
            assertThrows(IllegalStateException::class.java) {
                native { _, _, _, _ -> response }.request("GET", "/session/ses_one/goal", null, RequestOptions())
            }
        }
        var calls = 0
        val unsupported = native { _, _, _, _ -> calls++; error("404 RPC not found") }
        assertThrows(IllegalStateException::class.java) { unsupported.request("GET", "/session/ses_one/goal", null, RequestOptions()) }
        assertEquals(1, calls)
        var cancelled = false
        val adapter = native { _, _, _, _ -> cancelled = true; Json.obj("output" to goal()) }
        assertThrows(CancellationException::class.java) {
            adapter.request("GET", "/session/ses_one/goal", null, RequestOptions(isCancelled = { cancelled }))
        }
    }

    @Test fun `goal state parser and event projection preserve known fields and reject invalid updates without clearing`() {
        for (status in listOf("active", "paused", "complete")) {
            val state = goal().apply { addProperty("status", status); addProperty("reason", "Fixture reason") }
            val event = OpenCodeV2Events.project(Json.obj("id" to "evt_goal", "type" to "rpc.opencode.goal.changed",
                "location" to Json.obj("directory" to "/repo"), "data" to Json.obj("sessionID" to "ses_one", "goal" to state)), Json.obj()).single()
            assertEquals("session.goal.updated", event.str("type"))
            assertEquals("/repo", event.str("workspaceDirectory"))
            assertEquals(state, event.obj("properties")?.get("goal"))
            assertNotNull(ServerEvents.parse(event))
        }
        val cleared = OpenCodeV2Events.project(Json.obj("type" to "rpc.opencode.goal.changed",
            "data" to Json.obj("sessionID" to "ses_one", "goal" to null)), Json.obj()).single()
        assertTrue(cleared.obj("properties")!!.get("goal").isJsonNull)
        val invalid = listOf(goal().apply { addProperty("status", "unknown") }, goal().apply { addProperty("reason", 1) },
            goal().apply { add("reason", Json.toElement(null)) }, goal().apply { addProperty("rounds", Double.NaN) },
            goal().apply { addProperty("maxRounds", Double.POSITIVE_INFINITY) }, goal().apply { addProperty("updatedAt", "1") },
            goal().apply { remove("objective") }, goal().apply { addProperty("id", 42) })
        for (state in invalid) {
            assertNull(SessionGoal.parse(state))
            assertTrue(OpenCodeV2Events.project(Json.obj("type" to "rpc.opencode.goal.changed",
                "data" to Json.obj("sessionID" to "ses_one", "goal" to state)), Json.obj()).isEmpty())
        }
        assertTrue(OpenCodeV2Events.project(Json.obj("type" to "rpc.opencode.goal.changed",
            "data" to Json.obj("sessionID" to "ses_one")), Json.obj()).isEmpty())
        assertTrue(OpenCodeV2Events.project(Json.obj("type" to "rpc.opencode.goal.changed",
            "data" to Json.obj("goal" to goal())), Json.obj()).isEmpty())
        assertEquals(goal(), SessionGoal.parse(goal().apply { addProperty("private", "discard") }))
    }

    @Test fun `goal prompts keep native identities multiline objectives one-pass decoding and user attachments`() {
        val objective = "Check <tag> & keep &lt; literal\nThen verify"
        val escaped = objective.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val source = kickoff().apply {
            addProperty("text", "<untrusted_objective>\n$escaped\n</untrusted_objective>")
            addProperty("description", "Goal: truncated...")
        }
        val projected = OpenCodeV2Projection.message(source, "ses_one")
        assertEquals("user", projected.obj("info").str("role"))
        assertEquals("msg_goal", projected.obj("info").str("id"))
        val part = projected.arr("parts")!!.single().asJsonObject
        assertEquals("msg_goal:content:0", part.str("id"))
        assertEquals("/goal $objective", part.str("text"))
        assertFalse(part.has("synthetic"))
        val user = source.deepCopy().apply { addProperty("type", "user"); add("files", Json.array(listOf(
            Json.obj("mime" to "text/plain", "name" to "notes.txt", "source" to Json.obj("type" to "uri", "uri" to "file:///repo/notes.txt")),
        ))) }
        val parts = OpenCodeV2Projection.message(user, "ses_one").arr("parts")!!
        assertEquals("/goal $objective", parts[0].asJsonObject.str("text"))
        assertEquals("notes.txt", parts[1].asJsonObject.str("filename"))
        assertEquals("msg_goal:content:1", parts[1].asJsonObject.str("id"))
        source.remove("description")
        assertTrue(OpenCodeV2Projection.message(source, "ses_one").arr("parts")!!.isEmpty)
        source.addProperty("description", "Goal: fallback")
        source.addProperty("text", "No objective block")
        assertEquals("Goal: fallback", OpenCodeV2Projection.message(source, "ses_one").arr("parts")!![0].asJsonObject.str("text"))
        source.obj("metadata")!!.addProperty("source", "other")
        assertEquals(true, OpenCodeV2Projection.message(source, "ses_one").arr("parts")!![0].asJsonObject.bool("synthetic"))
    }

    @Test fun `delivered goal kickoff owns streamed replies but continuation delivery does not replace the parent`() {
        val adapter = native { _, _, _, _ -> error("Events must not send requests") }
        fun delivered(id: String, payload: JsonObject) {
            adapter.events(Json.obj("type" to "session.inbox.enqueued", "data" to Json.obj("sessionID" to "ses_one",
                "inboxID" to id, "item" to Json.obj("type" to "synthetic", "payload" to payload))))
            adapter.events(Json.obj("type" to "session.inbox.delivered", "data" to Json.obj("sessionID" to "ses_one", "inboxID" to id)))
        }
        delivered("msg_goal", kickoff())
        delivered("msg_continue", kickoff().apply { remove("description") })
        val failure = adapter.events(Json.obj("id" to "evt_failure", "type" to "session.execution.failed",
            "data" to Json.obj("sessionID" to "ses_one", "error" to Json.obj("message" to "fixture"))))
        assertEquals("msg_goal", failure.first().obj("properties").obj("info").str("parentID"))
    }

    @Test fun `inbox kickoff deduplicates on delivery and history context pagination finds it as the parent`() {
        var delivered = false
        val source = kickoff()
        val adapter = native { _, path, _, _ -> when {
            path.endsWith("/inbox") -> Json.obj("data" to if (delivered) emptyList() else listOf(
                Json.obj("id" to "msg_goal", "type" to "synthetic", "payload" to source, "time" to source.get("time"))))
            path.endsWith("/msg_reply") -> Json.obj("data" to assistant())
            else -> Json.obj("data" to if (delivered) listOf(assistant(), source) else emptyList())
        } }
        val pending = adapter.request("GET", "/session/ses_one/message?limit=10", null, RequestOptions()).data!!.asJsonArray
        assertEquals(1, pending.size())
        assertEquals("/goal Wait for 1 minute", pending[0].asJsonObject.arr("parts")!![0].asJsonObject.str("text"))
        delivered = true
        val history = adapter.request("GET", "/session/ses_one/message?limit=10", null, RequestOptions()).data!!.asJsonArray
        assertEquals(2, history.size())
        assertEquals("msg_goal", history[1].asJsonObject.obj("info").str("parentID"))
        assertEquals("msg_goal", adapter.request("GET", "/session/ses_one/message/msg_reply", null, RequestOptions())
            .data.asObjectOrNull().obj("info").str("parentID"))
        val paged = native { _, path, _, _ -> when {
            path.contains("cursor=older") -> Json.obj("data" to listOf(source))
            else -> Json.obj("data" to listOf(assistant()), "cursor" to Json.obj("next" to "older"))
        } }
        val page = paged.request("GET", "/session/ses_one/message?limit=1&before=current", null, RequestOptions()).data!!.asJsonArray
        assertEquals("msg_goal", page.single().asJsonObject.obj("info").str("parentID"))
    }

    @Test fun `goal controls use only the existing native command route without picker routing or a second prompt`() {
        for (action in listOf("pause", "resume", "clear")) {
            val calls = mutableListOf<String>()
            val adapter = native { method, path, body, _ ->
                calls += "$method $path"
                if (method == "GET") Json.obj("data" to Json.obj("id" to "ses_one")) else {
                    assertEquals("/api/session/ses_one/command", path)
                    assertEquals("goal", body.asObjectOrNull().str("name"))
                    assertEquals(action, body.asObjectOrNull().str("text"))
                    Json.obj("accepted" to true)
                }
            }
            adapter.request("POST", "/session/ses_one/command?directory=%2Frepo",
                Json.obj("command" to "goal", "arguments" to action), RequestOptions())
            assertEquals(listOf("GET /api/session/ses_one", "POST /api/session/ses_one/command"), calls)
        }
    }
}
