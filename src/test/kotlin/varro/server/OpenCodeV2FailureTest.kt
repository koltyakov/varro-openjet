package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class OpenCodeV2FailureTest {
    private fun idle(error: Any? = null) = Json.obj("id" to "msg_failure", "type" to "idle", "outcome" to "failed",
        "time" to Json.obj("created" to 1000), "error" to error)
    private val context = Json.obj("agent" to "build", "model" to Json.obj("providerID" to "fixture", "id" to "model"),
        "error" to Json.obj("type" to "old", "message" to "stale error", "status" to 500))

    @Test fun `status rows retain only complete contextual model identities`() {
        for (outcome in listOf("failed", "interrupted")) {
            for (model in listOf(null, Json.obj("providerID" to "", "id" to "", "variant" to "high"),
                Json.obj("providerID" to "fixture", "id" to "", "variant" to "high"),
                Json.obj("providerID" to "", "id" to "model", "variant" to "high"),
                Json.obj("providerID" to "fixture", "id" to "model", "variant" to "high"))) {
                val record = idle().apply { addProperty("outcome", outcome) }
                val projected = OpenCodeV2Projection.message(record, "ses_fixture", "/repo", "msg_user",
                    Json.obj("agent" to "build", "model" to model))
                val info = projected.obj("info")!!
                val complete = !model.str("providerID").isNullOrEmpty() && !model.str("id").isNullOrEmpty()
                assertEquals(if (complete) "fixture" else "", info.str("providerID"))
                assertEquals(if (complete) "model" else "", info.str("modelID"))
                assertEquals(if (complete) "high" else null, info.str("variant"))
                assertEquals("msg_user", info.str("parentID"))
                assertEquals(1000L, info.obj("time").long("completed"))
                assertEquals(if (outcome == "interrupted") "MessageAbortedError" else "UnknownError", info.obj("error").str("name"))
                assertTrue(projected.arr("parts")!!.isEmpty)
            }
        }
    }

    @Test fun `persisted authorization failures override stale live context`() {
        for (message in listOf("Request failed: 401 Unauthorized", "Integration.Authorization: Request failed: 403 Forbidden")) {
            val projected = OpenCodeV2Projection.message(idle(Json.obj("type" to "integration", "message" to message)), "ses_fixture", context = context)
            val error = projected.obj("info").obj("error")
            assertEquals("ProviderAuthError", error.str("name"))
            assertEquals(message, error.obj("data").str("message"))
            assertEquals("fixture", error.obj("data").str("providerID"))
            assertTrue(error.obj("data").int("statusCode") in listOf(401, 403))
            assertEquals(1000L, projected.obj("info").obj("time").long("completed"))
            assertTrue(projected.arr("parts")!!.isEmpty)
        }
    }

    @Test fun `explicit status takes precedence and ordinary failures use UnknownError`() {
        val projected = OpenCodeV2Projection.message(idle(Json.obj("type" to "provider", "message" to "Request failed: 401", "status" to 503)), "ses_fixture", context = context)
        assertEquals("UnknownError", projected.obj("info").obj("error").str("name"))
        assertEquals(503, projected.obj("info").obj("error").obj("data").int("statusCode"))
    }

    @Test fun `free limit errors preserve usage guidance and status in history and live events`() {
        val message = "Free usage limit reached. Connect a provider or subscribe to OpenCode Go to keep going, or switch models."
        val failure = Json.obj("type" to "provider.free-limit", "message" to message, "status" to 429, "retryAfterMs" to 60000)
        val history = OpenCodeV2Projection.message(idle(failure), "ses_fixture", context = context).obj("info").obj("error").obj("data")
        val live = OpenCodeV2Events.project(Json.obj("id" to "evt_free_limit", "type" to "session.execution.failed", "created" to 1000,
            "data" to Json.obj("sessionID" to "ses_fixture", "error" to failure)), context)
            .single { it.str("type") == "session.error" }.obj("properties").obj("error").obj("data")
        for (error in listOf(history, live)) {
            assertEquals(message, error.str("message"))
            assertEquals(429, error.int("statusCode"))
        }
    }

    @Test fun `malformed persisted errors fall back to valid live context or an actionable default`() {
        val projected = OpenCodeV2Projection.message(idle(Json.obj("message" to 42)), "ses_fixture", context = context)
        assertEquals("stale error", projected.obj("info").obj("error").obj("data").str("message"))
        val missing = OpenCodeV2Projection.message(idle(), "ses_fixture")
        assertTrue(missing.obj("info").obj("error").obj("data").str("message")!!.contains("Check the provider connection"))
        assertNull(OpenCodeV2Projection.normalizeError(Json.obj("message" to 42)))
        assertNull(OpenCodeV2Projection.normalizeError(Json.array(listOf("error"))))
    }

    @Test fun `released Go and ChatGPT limit diagnostics survive history and live projection`() {
        for ((type, message) in listOf(
            "provider.go-limit" to "OpenCode Go weekly usage limit reached. Switch to a free model, or turn on Extra Usage on the Go page in OpenCode Console to keep going.",
            "provider.chatgpt-limit" to "ChatGPT subscription sharing usage limit reached.",
        )) {
            val failure = Json.obj("type" to type, "message" to message, "status" to 429)
            val history = OpenCodeV2Projection.message(idle(failure), "ses_fixture", context = context).obj("info").obj("error").obj("data")
            val live = OpenCodeV2Events.project(Json.obj("id" to "limit", "type" to "session.execution.failed", "created" to 1000,
                "data" to Json.obj("sessionID" to "ses_fixture", "error" to failure)), context)
                .single { it.str("type") == "session.error" }.obj("properties").obj("error").obj("data")
            for (error in listOf(history, live)) { assertEquals(message, error.str("message")); assertEquals(429, error.int("statusCode")) }
        }
    }

    @Test fun `history and live execution errors have bounded diagnostics before idle`() {
        val message = "Integration.Authorization: Request failed: 401 " + "x".repeat(20000)
        val failure = Json.obj("type" to "integration", "message" to message)
        val history = OpenCodeV2Projection.message(idle(failure), "ses_fixture", context = context)
        assertEquals(16384, history.obj("info").obj("error").obj("data").str("message")!!.length)
        val event = Json.obj("id" to "evt_failure", "type" to "session.execution.failed", "created" to 1000,
            "data" to Json.obj("sessionID" to "ses_fixture", "error" to failure))
        val projected = OpenCodeV2Events.project(event, context)
        assertEquals(listOf("message.updated", "session.error", "session.status"), projected.map { it.str("type") })
        assertEquals("ProviderAuthError", projected[0].obj("properties").obj("info").obj("error").str("name"))
        assertEquals(401, projected[1].obj("properties").obj("error").obj("data").int("statusCode"))
        assertEquals(16384, projected[1].obj("properties").obj("error").obj("data").str("message")!!.length)
        assertEquals("idle", projected[2].obj("properties").obj("status").str("type"))
    }

    @Test fun `interrupted records stay aborted rather than becoming provider failures`() {
        val record = idle(Json.obj("message" to "Request failed: 401")).apply { addProperty("outcome", "interrupted") }
        val projected = OpenCodeV2Projection.message(record, "ses_fixture", context = context)
        assertEquals("MessageAbortedError", projected.obj("info").obj("error").str("name"))
    }
}
