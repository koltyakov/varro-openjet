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

    @Test fun `synthetic transcript text remains automatic rather than an editable user prompt`() {
        val record = Json.obj("id" to "automatic", "type" to "synthetic", "text" to "Continue with the next task.",
            "time" to Json.obj("created" to 1000))
        assertTrue(OpenCodeV2Projection.transcript(record))
        val projected = OpenCodeV2Projection.message(record, "session")
        assertEquals("user", projected.obj("info").str("role"))
        val part = projected.arr("parts")!!.single().asJsonObject
        assertEquals(true, part.bool("synthetic"))
        assertEquals("automatic:content:0", part.str("id"))
        assertEquals(record.str("text"), part.str("text"))
    }

    private fun adapter(wire: (String, String, JsonElement?) -> JsonElement?) = OpenCodeV2Adapter(
        { method, path, body, options -> assertTrue(options.unscoped); OpenCodeResponse(wire(method, path, body)) },
        OpenCodeV2SessionState(temporary.newFolder().toPath()),
    )

    @Test fun `provider catalog uses integration connections and respects workspace deny policies`() {
        for (effect in listOf("deny", "allow")) {
            val native = adapter { _, path, _ -> when (path) {
                "/api/model" -> Json.obj("data" to emptyList<Any>())
                "/api/provider" -> Json.obj("data" to listOf(
                    Json.obj("id" to "lmstudio"), Json.obj("id" to "cloud", "integrationID" to "login")))
                "/api/integration" -> Json.obj("data" to listOf(
                    Json.obj("id" to "login", "connections" to listOf(Json.obj("type" to "credential"))),
                    Json.obj("id" to "environment", "connections" to listOf(Json.obj("type" to "env", "name" to "API_KEY")))))
                "/api/config" -> Json.obj("data" to listOf(Json.obj("info" to Json.obj(
                    "providers" to Json.obj("ollama" to Json.obj("models" to Json.obj("local" to Json.obj()))),
                    "experimental" to Json.obj("policies" to listOf(
                        Json.obj("action" to "provider.use", "resource" to "ollama", "effect" to "deny"),
                        Json.obj("action" to "provider.use", "resource" to "ollama", "effect" to effect)))))))
                else -> error("Unexpected request $path")
            } }
            val catalog = native.request("GET", "/provider", null, RequestOptions()).data!!.asJsonObject
            val providers = catalog.arr("all")!!.map { it.asJsonObject }.associateBy { it.str("id") }
            assertEquals("disable", providers["lmstudio"].str("disconnectMode"))
            assertEquals("api", providers["cloud"].str("source"))
            assertNull(providers["cloud"].str("disconnectMode"))
            assertEquals("env", providers["environment"].str("source"))
            assertEquals("API_KEY", providers["environment"].arr("env")!![0].asString)
            assertEquals(effect == "allow", providers.containsKey("ollama"))
            if (effect == "allow") {
                assertEquals("disable", providers["ollama"].str("disconnectMode"))
                assertTrue(providers["ollama"].obj("models")!!.has("local"))
            } else assertFalse(catalog.arr("connected")!!.any { it.asString == "ollama" })
        }
    }

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

    @Test fun `assistant history preserves automatic retry metadata for recovery notices`() {
        val response = Json.obj("id" to "msg_retry", "type" to "assistant", "finish" to "error",
            "time" to Json.obj("created" to 1000, "completed" to 2000),
            "error" to Json.obj("type" to "provider.transport", "message" to "Connection closed"),
            "retry" to Json.obj("attempt" to 2, "at" to 3000))
        val info = OpenCodeV2Projection.message(response, "ses_test", parentID = "msg_user").obj("info")
        assertEquals(response.obj("retry"), info.obj("retry"))
        assertEquals("msg_user", info.str("parentID"))
        assertEquals("error", info.str("finish"))
        assertEquals("Connection closed", info.obj("error").obj("data").str("message"))

        response.remove("retry")
        assertFalse(OpenCodeV2Projection.message(response, "ses_test").obj("info")!!.has("retry"))
    }

    @Test fun `Windows location queries normalize absolute drive letters only`() {
        for ((directory, expected) in listOf("c:\\Users\\Andrew\\Repo" to "C:\\Users\\Andrew\\Repo", "d:/Projects/Repo" to "D:/Projects/Repo",
            "C:\\Repo" to "C:\\Repo", "/workspace/repo" to "/workspace/repo", "\\\\server\\share\\Repo" to "\\\\server\\share\\Repo", "c:relative" to "c:relative")) {
            val native = adapter { _, path, _ ->
                assertEquals("/api/location?location[directory]=$expected", java.net.URLDecoder.decode(path, Charsets.UTF_8))
                Json.obj()
            }
            native.request("GET", "/path", null, RequestOptions(directory = directory))
            native.request("GET", "/path?directory=${java.net.URLEncoder.encode(directory, Charsets.UTF_8)}", null, RequestOptions(directory = "/other"))
        }
    }

    @Test fun `interleaved history identities match type-local stream ordinals including empty blocks`() {
        val history = OpenCodeV2Projection.message(Json.obj("id" to "msg_test", "type" to "assistant", "content" to listOf(
            Json.obj("type" to "reasoning", "text" to "Inspecting"),
            Json.obj("type" to "tool", "id" to "call_test", "name" to "read", "state" to Json.obj("status" to "running", "input" to Json.obj())),
            Json.obj("type" to "text", "text" to ""), Json.obj("type" to "reasoning", "text" to "Checked"), Json.obj("type" to "text", "text" to "Answer"))), "ses_test")
        val expected = listOf("msg_test:reasoning:0", "call_test", "msg_test:text:0", "msg_test:reasoning:1", "msg_test:text:1")
        assertEquals(expected, history.arr("parts")!!.map { it.asJsonObject.str("id") })
        for (phase in listOf("started", "delta", "ended")) for (kind in listOf("text", "reasoning")) for (ordinal in 0..1) {
            val event = OpenCodeV2Events.project(Json.obj("type" to "session.$kind.$phase", "data" to Json.obj("sessionID" to "ses_test", "assistantMessageID" to "msg_test", "ordinal" to ordinal)), Json.obj()).single()
            assertEquals("msg_test:$kind:$ordinal", event.obj("properties").str("${kind}ID"))
        }
        for (started in listOf(null, 0L, 1000L)) {
            val event = OpenCodeV2Events.project(Json.obj("type" to "session.step.started", "created" to 2000, "data" to Json.obj("started" to started)), Json.obj()).single()
            assertEquals(started ?: 2000L, event.obj("properties").long("timestamp"))
        }
    }

    @Test fun `hidden OAuth fields use conditional defaults and respect supplied answers`() {
        val integration = Json.obj("id" to "opencode", "methods" to listOf(Json.obj("id" to "login", "type" to "oauth", "form" to listOf(
            Json.obj("key" to "server", "type" to "string", "hidden" to true, "default" to "https://console.example"),
            Json.obj("key" to "account", "type" to "string", "title" to "Account"),
            Json.obj("key" to "inactive", "type" to "boolean", "hidden" to true, "default" to true,
                "when" to listOf(Json.obj("key" to "account", "op" to "eq", "value" to "other")))))))
        for (inputs in listOf(null, Json.obj("server" to "https://custom.example"), Json.obj("account" to "other"))) {
            var sent: JsonObject? = null
            val native = adapter { _, path, body -> when (path) {
                "/api/provider" -> Json.obj("data" to emptyList<Any>())
                "/api/integration" -> Json.obj("data" to listOf(integration))
                "/api/provider/opencode" -> Json.obj("data" to Json.obj("integrationID" to "opencode"))
                "/api/integration/opencode" -> Json.obj("data" to integration)
                "/api/integration/opencode/connect/oauth" -> { sent = body.asObjectOrNull().obj("answer"); Json.obj("data" to Json.obj("attemptID" to "attempt_test")) }
                else -> error("Unexpected route $path")
            } }
            val methods = native.request("GET", "/provider/auth", null, RequestOptions()).data.asObjectOrNull().arr("opencode")!!
            val prompts = methods[0].asJsonObject.arr("prompts")!!.map { it.asJsonObject }
            assertEquals(listOf("server", "account", "inactive"), prompts.map { it.str("key") })
            assertEquals(true, prompts[0].bool("hidden"))
            assertEquals("https://console.example", prompts[0].str("default"))
            assertEquals("true", prompts[2].str("default"))
            assertEquals("select", prompts[2].str("type"))
            native.request("POST", "/provider/opencode/oauth/authorize", Json.obj("method" to 0, "inputs" to inputs), RequestOptions())
            assertEquals(inputs.str("server") ?: "https://console.example", sent.str("server"))
            assertEquals(inputs.str("account") == "other", sent!!.has("inactive"))
        }
    }

    @Test fun `native history skips control records and keeps inbox and parent identities`() {
        val native = adapter { _, path, _ -> when {
            path.endsWith("/inbox") -> Json.obj("data" to listOf(Json.obj("id" to "msg_pending", "type" to "user", "delivery" to "steer", "time" to Json.obj("created" to 4), "payload" to Json.obj("text" to "queued"))))
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
        assertEquals("msg_assistant:reasoning:0", assistant.arr("parts")!![0].asJsonObject.str("id"))
        assertEquals("msg_pending", messages[1].asJsonObject.obj("info").str("id"))
        assertEquals("steer", messages[1].asJsonObject.obj("info").str("pendingDelivery"))
        val event = native.events(Json.obj("id" to "evt_delta", "type" to "session.reasoning.delta", "data" to Json.obj("sessionID" to "ses_test", "assistantMessageID" to "msg_assistant", "ordinal" to 0, "delta" to "thinking"))).single()
        assertEquals("msg_assistant:reasoning:0", event.obj("properties").str("reasoningID"))
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
