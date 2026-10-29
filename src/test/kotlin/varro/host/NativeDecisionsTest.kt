package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.settings.VarroSettings

class NativeDecisionsTest {
    private val permission = Json.obj("type" to "bash", "sessionID" to "s", "title" to "npm test")
    private val spec = Json.parse("""{"paths":{"/api/experimental/generate/decisions":{"post":{}}}}""")
    private val catalog = Json.obj("data" to listOf(
        Json.obj("type" to "decision", "providerID" to "native", "id" to "judge", "name" to "Reviewer"),
        Json.obj("type" to "chat", "providerID" to "chat", "id" to "model", "name" to "Chat"),
        Json.obj("type" to "decision", "providerID" to "native", "id" to "off", "name" to "Disabled", "enabled" to false),
    ))

    private fun answers(choice: String = "allow", confidence: Double = 0.95, destructive: Double = 0.01, manipulation: Double = 0.01) =
        Json.obj("data" to Json.obj("answers" to listOf(
            Json.obj("name" to "decision", "type" to "choice", "choice" to choice, "confidence" to confidence,
                "probabilities" to listOf("allow", "ask", "reject").map { Json.obj("value" to it, "probability" to if (it == choice) 0.95 else 0.025) }),
            Json.obj("name" to "destructive", "type" to "predicate", "probability" to destructive),
            Json.obj("name" to "manipulation", "type" to "predicate", "probability" to manipulation),
        )))

    @Test fun `native decisions discover base catalog and submit typed evidence without sessions`() {
        val calls = mutableListOf<String>()
        val native = NativeDecisions({ 2 }, { "native/judge" }) { method, path, body, options ->
            calls += "$method $path"
            assertTrue(options.unscoped); assertNull(options.directory); assertEquals(5000L, options.timeoutMs)
            OpenCodeResponse(when (path) {
                "/openapi.json" -> { assertEquals(4 * 1024 * 1024L, options.maxResponseBytes); spec }
                "/api/model?types=decision" -> { assertEquals(1024 * 1024L, options.maxResponseBytes); catalog }
                "/api/experimental/generate/decisions" -> {
                    assertEquals(64 * 1024L, options.maxResponseBytes)
                    assertEquals(Json.obj("providerID" to "native", "id" to "judge"), body.asObjectOrNull().obj("model"))
                    val input = Json.parse(body.asObjectOrNull().arr("input")!![0].asJsonObject.str("text")!!).asJsonObject
                    assertEquals(permission, input.obj("permission"))
                    val questions = body.asObjectOrNull().arr("questions")!!
                    assertEquals(listOf("choice", "predicate", "predicate"), questions.map { it.asJsonObject.str("type") })
                    answers()
                }
                else -> error("Unexpected request $path")
            })
        }
        assertEquals(1, native.status().arr("models")!!.size())
        val judge = PermissionJudge({ "chat/model" }, { _, _, _, _ -> error("No helper sessions") }, {}, native = native)
        assertEquals("native", judge.model().str("providerID"))
        val result = judge.judge(Json.obj("permission" to permission))
        assertEquals("allow", result.str("decision")); assertEquals(native.model, result.obj("reviewerModel"))
        assertEquals(1, calls.count { it.startsWith("POST") })
    }

    @Test fun `probability gates retain ask and require prior rejection`() {
        for ((response, expected) in listOf(answers() to "allow", answers(confidence = 0.5) to "ask",
            answers(destructive = 0.2) to "ask", answers(manipulation = 0.2) to "ask", answers("ask") to "ask", answers("reject") to "ask")) {
            val native = NativeDecisions({ 2 }, { "native/judge" }) { _, path, _, _ -> OpenCodeResponse(if (path == "/openapi.json") spec else response) }
            val judge = PermissionJudge({ "" }, { _, _, _, _ -> error("Ask is terminal") }, {}, native = native)
            assertEquals(expected, judge.judge(Json.obj("permission" to permission)).str("decision"))
        }
        assertEquals("reject", JevDecisions.gate(NativeDecisions.parseAnswers(answers("reject")),
            Json.array(listOf(Json.obj("response" to "reject")))).str("decision"))
    }

    @Test fun `malformed native answers cannot approve`() {
        val mutations: List<(JsonArray) -> Unit> = listOf(
            { it.remove(0) },
            { it[1].asJsonObject.addProperty("name", "decision") },
            { it[0].asJsonObject.addProperty("type", "predicate") },
            { it[0].asJsonObject.addProperty("confidence", 1.1) },
            { it[0].asJsonObject.addProperty("choice", "unknown") },
            { it[1].asJsonObject.addProperty("probability", "0.0") },
            { it[0].asJsonObject.arr("probabilities")!![1].asJsonObject.addProperty("value", "allow") },
            { it[0].asJsonObject.arr("probabilities")!![1].asJsonObject.addProperty("probability", -0.1) },
        )
        for (mutate in mutations) {
            val response = answers(); mutate(response.obj("data").arr("answers")!!)
            assertThrows(IllegalStateException::class.java) { NativeDecisions.parseAnswers(response) }
        }
    }

    @Test fun `native errors fall back to enabled Jev then chat and local rules bypass native`() {
        val native = NativeDecisions({ 2 }, { "native/judge" }) { _, _, _, _ -> error("Unavailable") }
        val jev = JevDecisions(JevClient({ "key" }, JevHttp { _, _, _, _ ->
            val normalized = NativeDecisions.parseAnswers(answers())
            200 to Json.stringify(Json.obj("answers" to normalized))
        }), { JevSettings(true, "jev-latest") }, { true })
        val judge = PermissionJudge({ "chat/model" }, { _, _, _, _ -> error("No chat fallback") }, {}, jev, native)
        assertEquals("typesafe", judge.judge(Json.obj("permission" to permission)).obj("reviewerModel").str("providerID"))
        val chat = PermissionJudge({ "chat/model" }, { _, path, _, _ -> when (path) {
            "/session" -> Json.obj("id" to "helper")
            "/session/helper/message" -> Json.obj("info" to Json.obj("structured" to Json.obj("decision" to "ask", "reason" to "Unclear")))
            else -> Json.toElement(true)
        } }, {}, native = native)
        assertEquals("chat", chat.judge(Json.obj("permission" to permission)).obj("reviewerModel").str("providerID"))
        assertEquals("allow", judge.judge(Json.obj("permission" to Json.obj("type" to "read", "sessionID" to "s"))).str("decision"))
        assertEquals("ask", judge.judge(Json.obj("permission" to Json.obj("type" to "external_directory", "sessionID" to "s"))).str("decision"))
    }

    @Test fun `V1 and unavailable capabilities do not advertise native support`() {
        val v1 = NativeDecisions({ 1 }, { "native/judge" }) { _, _, _, _ -> error("No V1 native requests") }
        assertNull(v1.model); assertEquals(false, v1.status().bool("supported"))
        val old = NativeDecisions({ 2 }, { "native/judge" }) { _, path, _, _ ->
            assertEquals("/openapi.json", path); OpenCodeResponse(Json.obj("paths" to JsonObject()))
        }
        assertEquals(false, old.status().bool("supported")); assertNotNull(old.model)
    }

    @Test fun `experimental client namespaces do not change native decision HTTP discovery`() {
        for (operationID in listOf("generate.decisions", "experimental.generate.decisions")) {
            val namespacedSpec = spec.deepCopy().asJsonObject.apply {
                obj("paths").obj("/api/experimental/generate/decisions").obj("post")!!.addProperty("operationId", operationID)
            }
            val calls = mutableListOf<String>()
            val native = NativeDecisions({ 2 }, { "native/judge" }) { method, path, _, _ ->
                calls += "$method $path"
                OpenCodeResponse(when (path) {
                    "/openapi.json" -> namespacedSpec
                    "/api/model?types=decision" -> catalog
                    "/api/experimental/generate/decisions" -> answers()
                    else -> error("Unexpected request $path")
                })
            }
            assertEquals(true, native.status().bool("supported"))
            assertEquals("allow", native.judgePermission(native.model!!, permission, JsonArray()).str("decision"))
            assertEquals(listOf("POST /api/experimental/generate/decisions"), calls.filter { it.startsWith("POST") })
        }
    }

    @Test fun `decision provider selection validates catalog and hidden reviewer remains active`() {
        val settings = VarroSettings()
        var changes = 0
        val native = NativeDecisions({ 2 }, { settings.chatAutoApproveDecisionModel }) { _, path, _, _ -> OpenCodeResponse(if (path == "/openapi.json") spec else catalog) }
        val providers = DecisionProviders(settings, secrets = object : DecisionProviders.SecretStore {
            override fun get(): String? = null
            override fun set(value: String?) { error("Native opt-in must not change Jev credentials") }
        }, environment = { emptyMap() }, ui = object : DecisionProviders.Ui {
            override fun promptApiKey(): String? = error("No credential prompt")
            override fun showError(message: String) = error(message)
            override fun showInfo(message: String) = error(message)
        }, onChanged = { changes++ }, native = native)
        assertFalse(providers.status().obj("native").bool("visible")!!)
        assertThrows(IllegalArgumentException::class.java) { providers.handle(Json.obj("action" to "native", "model" to "native/off")) }
        assertThrows(IllegalArgumentException::class.java) { providers.handle(Json.obj("action" to "native", "model" to 42)) }
        val selected = providers.handle(Json.obj("action" to "native", "model" to "native/judge"))
        assertFalse(selected.obj("native").bool("visible")!!); assertNotNull(native.model)
        settings.debugShowNativeDecisionModels = true
        assertTrue(providers.status().obj("native").bool("visible")!!)
        providers.handle(Json.obj("action" to "native", "model" to ""))
        assertNull(native.model); assertEquals(2, changes)
    }
}
