package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.RequestOptions
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CancellationException

class PromptCompletionServiceTest {
    private class Fixture {
        var version = 2
        var url = "http://fixture.local"
        val model = Json.obj("id" to "model", "package" to "@opencode/ai/providers/openai", "modelID" to "native-model",
            "capabilities" to Json.obj("input" to listOf("text"), "output" to listOf("text")))
        val provider = Json.obj("id" to "openai", "integrationID" to "integration", "models" to Json.obj("model" to model))
        val config = JsonObject()
        var credential = Json.obj("active" to true, "integrationID" to "integration", "value" to Json.obj("type" to "key", "key" to "fixture-secret"))
        val reads = mutableListOf<Pair<String, RequestOptions>>()
        val sent = mutableListOf<Pair<PromptCompletionService.Connection, JsonObject>>()
        var output: JsonObject? = null
        val service = PromptCompletionService({ version }, { url }, { method, path, body, options ->
            assertEquals("GET", method)
            assertNull(body)
            options.checkCancelled()
            reads.add(path to options)
            when (path) {
                "/config/providers" -> Json.obj("providers" to listOf(provider))
                "/config" -> config
                "/api/credential" -> Json.obj("data" to listOf(credential))
                else -> error("Unexpected route $path")
            }
        }, send = { connection, body, check ->
            check()
            sent.add(connection to body)
            val text = """{"suffix":" a regression test"}"""
            output ?: when (connection.protocol) {
                "responses" -> Json.obj("output" to listOf(Json.obj("type" to "message", "content" to listOf(Json.obj("type" to "output_text", "text" to text)))))
                "messages" -> Json.obj("content" to listOf(Json.obj("type" to "text", "text" to text)))
                else -> Json.obj("choices" to listOf(Json.obj("message" to Json.obj("content" to text))))
            }
        })

        fun complete(body: JsonElement? = input(), cancelled: () -> Boolean = { false }) = service.complete(body, "openai/model", "/fixture", cancelled)
    }

    @Test fun `disabled completion does not read credentials send inference or validate context`() {
        val fixture = Fixture()
        assertEquals("", fixture.service.complete(null, "", "/fixture", { false }).str("suffix"))
        assertTrue(fixture.reads.isEmpty())
        assertTrue(fixture.sent.isEmpty())
    }

    @Test fun `direct protocols send one bounded request without tool or agent execution`() {
        for ((pkg, protocol) in listOf(
            "@opencode/ai/providers/openai" to "responses", "@opencode/ai/providers/openai/chat" to "chat",
            "aisdk:@ai-sdk/openai" to "responses", "@opencode/ai/providers/anthropic" to "messages",
            "@opencode/ai/providers/openrouter" to "openrouter", "@opencode/ai/providers/openai-compatible/responses" to "responses",
        )) {
            val fixture = Fixture()
            fixture.model.addProperty("package", pkg)
            fixture.model.add("body", Json.obj("tools" to listOf(Json.obj("name" to "bash")), "tool_choice" to "required",
                "functions" to listOf("shell"), "n" to 4, "maxTokens" to 10000, "max_completion_tokens" to 10000, "max_output_tokens" to 10000))
            assertEquals(" a regression test", fixture.complete().str("suffix"))
            val (connection, body) = fixture.sent.single()
            assertEquals(protocol, connection.protocol)
            assertEquals("native-model", body.str("model"))
            assertFalse(body.bool("stream")!!)
            for (name in listOf("tools", "tool_choice", "functions", "function_call", "n", "maxTokens")) assertFalse(body.has(name))
            if (protocol == "messages") assertEquals("fixture-secret", connection.headers["x-api-key"])
            else assertEquals("Bearer fixture-secret", connection.headers["Authorization"])
            assertEquals(1, body.keySet().count { it in listOf("max_tokens", "max_completion_tokens", "max_output_tokens") })
            assertFalse(body.toString().contains("fixture-secret"))
            val credentialRead = fixture.reads.single { it.first == "/api/credential" }
            assertTrue(credentialRead.second.unscoped)
            assertNull(credentialRead.second.directory)
            assertTrue(fixture.reads.all { it.first in listOf("/config/providers", "/config", "/api/credential") })
        }
    }

    @Test fun `unsupported connections never expose secrets or admit a request`() {
        val cases: List<(Fixture) -> Unit> = listOf(
            { it.version = 1 },
            { it.model.addProperty("package", "arbitrary/providers/magic/chat") },
            { it.credential.obj("value")!!.addProperty("type", "oauth") },
            { it.credential.addProperty("active", false) },
            { it.model.addProperty("enabled", false) },
            { it.model.obj("capabilities")!!.add("output", Json.array(listOf("image"))) },
            { it.provider.add("options", Json.obj("apiKey" to "{env:SECRET}")) },
            { it.provider.add("options", Json.obj("baseURL" to "http://remote.example/api")) },
            { it.provider.add("headers", Json.obj("x-invalid" to "header\nsecret")) },
        )
        for (configure in cases) {
            val fixture = Fixture()
            configure(fixture)
            val failure = assertThrows(IllegalArgumentException::class.java) { fixture.complete() }
            assertFalse(failure.message.orEmpty().contains("fixture-secret"))
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test fun `catalog is cached by server and directory but credentials are refreshed`() {
        val fixture = Fixture()
        fixture.complete(); fixture.complete()
        assertEquals(1, fixture.reads.count { it.first == "/config/providers" })
        assertEquals(2, fixture.reads.count { it.first == "/api/credential" })
        fixture.credential.obj("value")!!.addProperty("key", "rotated-key")
        fixture.complete()
        assertEquals("Bearer rotated-key", fixture.sent.last().first.headers["Authorization"])
        fixture.url = "http://another-fixture.local"
        fixture.complete()
        assertEquals(2, fixture.reads.count { it.first == "/config/providers" })
        fixture.service.invalidate()
        fixture.complete()
        assertEquals(3, fixture.reads.count { it.first == "/config/providers" })
        val availability = fixture.service.availability("/fixture", { false })
        assertEquals(true, availability.obj("openai/model").bool("available"))
        assertFalse(availability.toString().contains("rotated-key"))
    }

    @Test fun `host chooses lowest effort regardless of a webview supplied high variant`() {
        val fixture = Fixture()
        fixture.model.add("variants", Json.obj("high" to Json.obj("reasoningEffort" to "high"),
            "low" to Json.obj("settings" to Json.obj("reasoningEffort" to "low")),
            "off" to Json.obj("disabled" to true, "reasoning" to Json.obj("enabled" to false))))
        fixture.complete(input().apply { addProperty("variant", "high") })
        val (_, body) = fixture.sent.single()
        assertEquals("low", body.obj("reasoning").str("effort"))
        assertEquals(1024, body.int("max_output_tokens"))
        assertFalse(body.str("input")!!.contains("variant"))
    }

    @Test fun `GLM forced thinking OpenRouter and Meta use native request settings`() {
        val zai = Fixture()
        zai.model.addProperty("package", "@opencode/ai/providers/zai")
        zai.model.addProperty("modelID", "glm-5.3")
        zai.complete()
        assertEquals("enabled", zai.sent.single().second.obj("thinking").str("type"))
        assertEquals("low", zai.sent.single().second.str("reasoning_effort"))
        assertEquals(1024, zai.sent.single().second.int("max_tokens"))

        val router = Fixture()
        router.model.addProperty("package", "@opencode/ai/providers/openrouter")
        router.model.add("variants", Json.obj("low" to Json.obj("reasoning" to Json.obj("effort" to "low"))))
        router.complete()
        assertEquals(true, router.sent.single().second.obj("reasoning").bool("exclude"))
        assertEquals(1280, router.sent.single().second.int("max_tokens"))

        val meta = Fixture()
        meta.provider.addProperty("canonical", "meta")
        meta.model.addProperty("modelID", "muse-spark")
        meta.complete()
        assertEquals("minimal", meta.sent.single().second.str("reasoning_effort"))
        assertEquals("json_schema", meta.sent.single().second.obj("response_format").str("type"))
    }

    @Test fun `input and output limits reject malformed context and untrusted responses`() {
        for (body in listOf(Json.obj(), input().apply { addProperty("draft", "ab") }, input().apply { addProperty("draft", "x".repeat(4001)) },
            input().apply { add("history", Json.array(List(11) { "test" })) }, input().apply { add("history", Json.array(listOf(1))) },
            input().apply { addProperty("lastAssistantResponse", "x".repeat(6001)) }, input().apply { addProperty("variant", 1) })) {
            val fixture = Fixture()
            assertThrows(IllegalArgumentException::class.java) { fixture.complete(body) }
            assertTrue(fixture.reads.isEmpty())
        }
        assertEquals(" next", PromptCompletionService.normalizeSuffix("Please add next ", "Please add"))
        for (suffix in listOf(null, "", " ", "x".repeat(241), "bad\u0000output")) assertEquals("", PromptCompletionService.normalizeSuffix(suffix, "draft"))
        val fixture = Fixture()
        fixture.output = Json.obj("output" to listOf(Json.obj("type" to "message", "content" to listOf(Json.obj("type" to "output_text", "text" to "Not JSON")))))
        assertEquals("", fixture.complete().str("suffix"))
    }

    @Test fun `completion strips duplicate separators but preserves word continuations and line breaks`() {
        for (separator in listOf(" ", "  ", "\t", "\n")) {
            val draft = "Add a feature$separator"
            for (suffix in listOf(" with tests", " \t with tests  ", "$draft with tests")) {
                assertEquals("with tests", PromptCompletionService.normalizeSuffix(suffix, draft))
            }
        }
        assertEquals("ner?", PromptCompletionService.normalizeSuffix("ner?", "Show it in the cor"))
        assertEquals("\nThen add tests.", PromptCompletionService.normalizeSuffix("\nThen add tests.", "Add a feature "))
    }

    @Test fun `cancellation prevents setup and test does not swallow it`() {
        val fixture = Fixture()
        assertThrows(CancellationException::class.java) { fixture.complete(cancelled = { true }) }
        assertThrows(CancellationException::class.java) { fixture.service.test(Json.obj("providerID" to "openai", "modelID" to "model"), "/fixture", { true }) }
        assertTrue(fixture.reads.isEmpty())
        assertTrue(fixture.sent.isEmpty())
    }

    @Test fun `HTTP transport redacts errors refuses redirects and bounds responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = mutableListOf<String>()
        server.createContext("/") { exchange ->
            hits.add(exchange.requestURI.path)
            val bytes = when (exchange.requestURI.path) {
                "/oversized" -> "x".repeat(65537)
                "/invalid" -> "not json"
                else -> "{\"error\":\"secret-provider-body\"}"
            }.toByteArray()
            if (exchange.requestURI.path == "/redirect") exchange.responseHeaders.add("Location", "/target")
            val status = when (exchange.requestURI.path) { "/redirect" -> 302; "/error" -> 401; else -> 200 }
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val http = DirectCompletionHttp()
            for (path in listOf("/redirect", "/error", "/oversized", "/invalid")) {
                val connection = PromptCompletionService.Connection("chat", URI("http://127.0.0.1:${server.address.port}$path"),
                    mapOf("Content-Type" to "application/json"), "fixture", JsonObject(), false, "max_tokens")
                val error = assertThrows(IllegalStateException::class.java) { http.send(connection, JsonObject()) {} }
                assertFalse(error.message.orEmpty().contains("secret-provider-body"))
            }
            assertEquals(listOf("/redirect", "/error", "/oversized", "/invalid"), hits)
        } finally { server.stop(0) }
    }

    @Test fun `only HTTPS and explicit loopback HTTP endpoints are accepted`() {
        for (base in listOf("https://api.example/v1", "http://localhost:1234/v1", "http://127.0.0.1:1234", "http://[::1]:1234"))
            assertNotNull(PromptCompletionService.completionURL(base, "chat"))
        for (base in listOf("http://remote.example", "https://user:secret@example.com", "https://example.com?key=secret", "https://example.com#fragment", "file:///path", "bad URL"))
            assertNull(PromptCompletionService.completionURL(base, "responses"))
    }

    companion object {
        private fun input() = Json.obj("draft" to "Please add", "history" to listOf("Fix the tests"), "lastAssistantResponse" to "One regression remains")
    }
}
