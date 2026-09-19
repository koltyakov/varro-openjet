package varro.host

import com.google.gson.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.obj
import varro.protocol.str
import varro.settings.VarroSettings

class JevDecisionsTest {

    private val permission = Json.obj("type" to "bash", "sessionID" to "s", "title" to "npm test")

    private fun answers(choice: String, probability: Double, confidence: Double = 0.9, destructive: Double = 0.05, manipulation: Double = 0.02) =
        Json.obj("answers" to Json.obj(
            "decision" to Json.obj("type" to "choice", "choice" to choice, "confidence" to confidence,
                "probabilities" to Json.obj("allow" to if (choice == "allow") probability else 0.05,
                    "ask" to if (choice == "ask") probability else 0.05,
                    "reject" to if (choice == "reject") probability else 0.05)),
            "destructive" to Json.obj("type" to "noul", "noul" to destructive),
            "manipulation" to Json.obj("type" to "noul", "noul" to manipulation),
        ))

    private fun jev(response: () -> Pair<Int, String>, enabled: Boolean = true) =
        JevDecisions(JevClient({ "key" }, JevHttp { _, _, _, _ -> response() }), { JevSettings(enabled, "jev-latest") }, { true })

    @Test fun `posts typed questions with bearer auth`() {
        var seen: Pair<Map<String, String>, String>? = null
        val client = JevClient({ "secret" }, JevHttp { url, headers, body, timeout ->
            assertEquals(JevClient.ENDPOINT, url)
            assertEquals(5000, timeout)
            seen = headers to body
            200 to Json.stringify(answers("allow", 0.95))
        })
        JevDecisions(client, { JevSettings(true, "jev-latest") }, { true }).judgePermission(permission, JsonArray())
        assertEquals("Bearer secret", seen!!.first["Authorization"])
        val body = Json.parse(seen!!.second).asObjectOrNull()
        assertEquals("jev-latest", body.str("model"))
        assertEquals("choice", body.obj("questions").obj("decision").str("type"))
        assertEquals("npm test", body.obj("state").obj("permission").str("title"))
    }

    @Test fun `reports HTTP failures, malformed answers, and missing keys`() {
        fun failure(block: () -> Unit) = try { block(); fail("expected failure"); "" } catch (e: JevApiError) { e.message.orEmpty() }
        assertTrue(failure { jev({ 401 to "" }).judgePermission(permission, JsonArray()) }.contains("401"))
        assertTrue(failure { jev({ 200 to """{"answers":{"decision":{"type":"noul","noul":1}}}""" }).judgePermission(permission, JsonArray()) }
            .contains("invalid answer for \"decision\""))
        val keyless = JevClient({ null }, JevHttp { _, _, _, _ -> fail("must not call TypeSafe"); 0 to "" })
        assertTrue(failure { keyless.evaluate("m", Json.obj(), Json.obj(), 1) }.contains("not configured"))
        assertNull(JevClient.parseAnswer(Json.obj("type" to "noul", "noul" to 1.5), Json.obj("type" to "noul")))
    }

    @Test fun `allows only confident, low-risk answers`() {
        fun decide(vararg args: Double) = jev({ 200 to Json.stringify(answers("allow", args[0], args[1], args[2], args[3])) })
            .judgePermission(permission, JsonArray()).str("decision")
        assertEquals("allow", decide(0.9, 0.9, 0.05, 0.02))
        assertEquals("ask", decide(0.7, 0.9, 0.05, 0.02))
        assertEquals("ask", decide(0.9, 0.6, 0.05, 0.02))
        assertEquals("ask", decide(0.9, 0.9, 0.3, 0.02))
        assertEquals("ask", decide(0.9, 0.9, 0.05, 0.3))
    }

    @Test fun `rejects only when the user rejected before`() {
        val rejecting = jev({ 200 to Json.stringify(answers("reject", 0.95)) })
        assertEquals("ask", rejecting.judgePermission(permission, JsonArray()).str("decision"))
        val prior = Json.array(listOf(Json.obj("type" to "bash", "title" to "npm test", "response" to "reject")))
        assertEquals("reject", rejecting.judgePermission(permission, prior).str("decision"))
    }

    @Test fun `judge uses Jev before the model judge and reports it as the reviewer`() {
        val judge = PermissionJudge({ "provider/model" }, { _, _, _, _ -> fail("Jev verdicts must not create judge sessions"); null }, {},
            jev({ 200 to Json.stringify(answers("allow", 0.95)) }))
        val result = judge.judge(Json.obj("permission" to permission))
        assertEquals("allow", result.str("decision"))
        assertEquals("typesafe", result.obj("reviewerModel").str("providerID"))
        assertEquals("typesafe", judge.model().str("providerID"))
    }

    @Test fun `judge falls back to the model judge when Jev fails or is off`() {
        listOf(jev({ 500 to "" }), jev({ fail("disabled Jev must not be called"); 0 to "" }, enabled = false)).forEach { jev ->
            val judge = PermissionJudge({ "provider/model" }, { method, path, _, _ ->
                when ("$method $path") {
                    "POST /session" -> Json.obj("id" to "helper")
                    "POST /session/helper/message" -> Json.obj("info" to Json.obj("structured" to
                        Json.obj("decision" to "allow", "reason" to "Local tests", "actionSummary" to "Run tests")))
                    else -> Json.toElement(true)
                }
            }, {}, jev)
            val result = judge.judge(Json.obj("permission" to permission))
            assertEquals("allow", result.str("decision"))
            assertEquals("provider", result.obj("reviewerModel").str("providerID"))
        }
    }

    @Test fun `decision providers report credentials and persist the opt-in`() {
        var stored: String? = null
        val settings = VarroSettings()
        var prompted = false
        val providers = DecisionProviders(settings,
            secrets = object : DecisionProviders.SecretStore {
                override fun get() = stored
                override fun set(value: String?) { stored = value }
            },
            environment = { emptyMap() },
            ui = object : DecisionProviders.Ui {
                override fun promptApiKey(): String { prompted = true; return " key " }
                override fun showError(message: String) = fail(message)
                override fun showInfo(message: String) {}
            },
            createClient = { key -> JevClient({ key }, JevHttp { _, _, _, _ -> 200 to """{"answers":{"ok":{"type":"noul","noul":1}}}""" }) },
        )
        assertEquals(false, providers.status().obj("jev").bool("connected"))
        val connected = providers.handle(Json.obj("action" to "connect")).obj("jev")
        assertTrue(prompted)
        assertEquals("key", stored)
        assertEquals("secret", connected.str("credentialSource"))
        assertEquals(true, providers.handle(Json.obj("action" to "update", "autoApprove" to true)).obj("jev").bool("autoApprove"))
        assertTrue(settings.decisionsJevAutoApprove)
        assertFalse(providers.handle(Json.obj("action" to "disconnect")).obj("jev").bool("connected")!!)
    }
}
