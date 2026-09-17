package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class OpenCodeV2Test {
    @JvmField @Rule val temporary = TemporaryFolder()
    private fun adapter(wire: (String, String, JsonElement?) -> JsonElement?) = OpenCodeV2Adapter(
        { method, path, body, options -> assertTrue(options.unscoped); OpenCodeResponse(wire(method, path, body)) },
        OpenCodeV2SessionState(temporary.newFolder().toPath()),
    )

    @Test fun `startup credentials are redacted across every chunk boundary`() {
        val text = "server listening on http://127.0.0.1:1234\nserver password secret-value\nready\n"
        for (split in 0..text.length) {
            val passwords = mutableListOf<String>()
            val output = OpenCodeStartupOutput(passwords::add)
            val visible = output.write(text.take(split)) + output.write(text.drop(split))
            assertEquals(listOf("secret-value"), passwords)
            assertFalse(visible.contains("secret-value"))
            assertTrue(visible.contains("ready"))
        }
    }

    @Test fun `native history skips control records and keeps inbox and parent identities`() {
        val native = adapter { _, path, _ -> when {
            path.endsWith("/inbox") -> Json.obj("data" to listOf(Json.obj("id" to "msg_pending", "type" to "user", "time" to Json.obj("created" to 4), "payload" to Json.obj("text" to "queued"))))
            path.contains("cursor=older") -> Json.obj("data" to listOf(Json.obj("id" to "msg_user", "type" to "user", "time" to Json.obj("created" to 1), "text" to "hello")), "cursor" to Json.obj())
            else -> Json.obj("data" to listOf(
                Json.obj("id" to "msg_assistant", "type" to "assistant", "agent" to "build", "model" to Json.obj("providerID" to "test", "id" to "model"), "time" to Json.obj("created" to 3),
                    "content" to listOf(Json.obj("type" to "reasoning", "text" to "thinking"), Json.obj("type" to "text", "text" to "answer"))),
                Json.obj("id" to "msg_control", "type" to "model-switched", "model" to Json.obj("providerID" to "test", "id" to "model"), "time" to Json.obj("created" to 2))),
                "cursor" to Json.obj("next" to "older"))
        } }
        val response = native.request("GET", "/session/ses_test/message?limit=1", null, RequestOptions(captureNextCursor = true))
        val messages = response.data!!.asJsonArray
        assertEquals("older", response.nextCursor)
        assertEquals(2, messages.size())
        val assistant = messages[0].asJsonObject
        assertEquals("msg_user", assistant.obj("info").str("parentID"))
        assertEquals("msg_assistant:content:0", assistant.arr("parts")!![0].asJsonObject.str("id"))
        assertEquals("msg_pending", messages[1].asJsonObject.obj("info").str("id"))
        val event = native.events(Json.obj("id" to "evt_delta", "type" to "session.reasoning.delta", "data" to Json.obj("sessionID" to "ses_test", "assistantMessageID" to "msg_assistant", "ordinal" to 0, "delta" to "thinking"))).single()
        assertEquals("msg_assistant:content:0", event.obj("properties").str("reasoningID"))
    }

    @Test fun `permission failures retain owning child until acknowledgement`() {
        var fail = true
        val paths = mutableListOf<String>()
        val native = adapter { _, path, body ->
            paths.add(path)
            assertEquals("/api/session/ses_child/permission/perm_test/reply", path)
            assertEquals("once", body.asObjectOrNull().str("decision"))
            if (fail) error("503 unavailable")
            null
        }
        native.events(Json.obj("type" to "permission.asked", "data" to Json.obj("id" to "perm_test", "sessionID" to "ses_child", "action" to "shell", "resources" to listOf("pwd"))))
        assertThrows(IllegalStateException::class.java) { native.request("POST", "/permission/perm_test/reply", Json.obj("reply" to "once"), RequestOptions()) }
        fail = false
        assertTrue(native.request("POST", "/permission/perm_test/reply", Json.obj("reply" to "once"), RequestOptions()).data!!.asBoolean)
        assertEquals(2, paths.size)
    }

    @Test fun `prompt admission commits staged edits and preserves system context`() {
        val calls = mutableListOf<Pair<String, JsonElement?>>()
        val native = adapter { _, path, body ->
            calls.add(path to body)
            if (path == "/api/session/ses_test") Json.obj("data" to Json.obj("revert" to Json.obj("messageID" to "old"))) else null
        }
        native.request("POST", "/session/ses_test/prompt_async", Json.obj("messageID" to "msg_new", "agent" to "build", "model" to Json.obj("providerID" to "test", "modelID" to "model"),
            "variant" to "high", "system" to "IDE context", "parts" to listOf(Json.obj("type" to "text", "text" to "hello"), Json.obj("type" to "file", "url" to "data:image/png;base64,eA==", "filename" to "x.png"))), RequestOptions())
        assertEquals(listOf("/api/session/ses_test/agent", "/api/session/ses_test/model", "/api/session/ses_test", "/api/session/ses_test/revert/commit",
            "/api/experimental/session/ses_test/instructions/entries/varro.system", "/api/session/ses_test/prompt"), calls.map { it.first })
        assertEquals("high", calls[1].second.asObjectOrNull().obj("model").str("variant"))
        val payload = calls.last().second.asObjectOrNull()
        assertEquals("steer", payload.str("delivery"))
        assertEquals("msg_new", payload.str("id"))
        assertEquals("hello", payload.str("text"))
        assertEquals("data:image/png;base64,eA==", payload.arr("files")!![0].asJsonObject.str("uri"))
    }

    @Test fun `metadata and archive times survive adapter recreation without server patches`() {
        val root = temporary.newFolder().toPath()
        val value = Json.obj("id" to "ses_test", "title" to "test", "location" to Json.obj("directory" to "/project"), "time" to Json.obj("created" to 1, "updated" to 2))
        fun create() = OpenCodeV2Adapter({ method, _, _, _ -> assertEquals("GET", method); OpenCodeResponse(Json.obj("data" to value)) }, OpenCodeV2SessionState(root))
        create().request("PATCH", "/session/ses_test", Json.obj("metadata" to Json.obj("varro" to Json.obj("permissionMode" to "auto")), "time" to Json.obj("archived" to 3)), RequestOptions())
        val restored = create().request("GET", "/session/ses_test", null, RequestOptions()).data.asObjectOrNull()
        assertEquals("auto", restored.obj("metadata").obj("varro").str("permissionMode"))
        assertEquals(1L, restored.obj("time").long("created"))
        assertEquals(3L, restored.obj("time").long("archived"))
        assertEquals("2", restored.str("version"))
        assertEquals(false, restored.bool("sharingSupported"))
    }

    @Test fun `generated skill and shell records remain completed transcript activity`() {
        val skill = OpenCodeV2Projection.message(Json.obj("id" to "msg_skill", "type" to "skill", "skill" to "review", "text" to "instructions", "time" to Json.obj("created" to 1)), "ses_test")
        assertEquals("assistant", skill.obj("info").str("role"))
        val tool = skill.arr("parts")!![0].asJsonObject
        assertEquals("skill", tool.str("tool"))
        assertEquals("completed", tool.obj("state").str("status"))
        assertEquals("instructions", tool.obj("state").str("output"))
        val shell = OpenCodeV2Projection.message(Json.obj("id" to "msg_shell", "shellID" to "shell_test", "type" to "shell", "command" to "false", "status" to "exited", "exit" to 1, "time" to Json.obj("created" to 1)), "ses_test")
        assertEquals("error", shell.arr("parts")!![0].asJsonObject.obj("state").str("status"))
    }

    @Test fun `pre-turn errors precede idle and retain a stable history identity`() {
        val native = adapter { _, _, _ -> error("No requests expected") }
        val events = native.events(Json.obj("id" to "evt_failure", "type" to "session.execution.failed", "created" to 10,
            "data" to Json.obj("sessionID" to "ses_test", "error" to Json.obj("message" to "Provider refused", "status" to 401))))
        assertEquals(listOf("message.updated", "session.error", "session.status"), events.map { it.str("type") })
        assertEquals("msg_failure", events[0].obj("properties").obj("info").str("id"))
        assertEquals("Provider refused", events[0].obj("properties").obj("info").obj("error").obj("data").str("message"))
    }

    @Test fun `message pagination enforces aggregate byte budget and repeated cursor detection`() {
        val native = adapter { _, path, _ -> if (path.endsWith("inbox")) Json.obj("data" to emptyList<Any>()) else Json.obj("data" to listOf(Json.obj("id" to "control", "type" to "model-switched")), "cursor" to Json.obj("next" to "same")) }
        assertThrows(IllegalArgumentException::class.java) { native.request("GET", "/session/ses_test/message", null, RequestOptions()) }
        assertThrows(OpenCodeResponseTooLargeException::class.java) { native.request("GET", "/session/ses_test/message", null, RequestOptions(maxResponseBytes = 8)) }
    }
}
