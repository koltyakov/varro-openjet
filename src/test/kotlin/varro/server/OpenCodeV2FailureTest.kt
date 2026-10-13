package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class OpenCodeV2FailureTest {
    private fun idle(error: Any? = null) = Json.obj("id" to "msg_failure", "type" to "idle", "outcome" to "failed",
        "time" to Json.obj("created" to 1000), "error" to error)
    private val context = Json.obj("agent" to "build", "model" to Json.obj("providerID" to "fixture", "id" to "model"),
        "error" to Json.obj("type" to "old", "message" to "stale error", "status" to 500))

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

    @Test fun `malformed persisted errors fall back to valid live context or an actionable default`() {
        val projected = OpenCodeV2Projection.message(idle(Json.obj("message" to 42)), "ses_fixture", context = context)
        assertEquals("stale error", projected.obj("info").obj("error").obj("data").str("message"))
        val missing = OpenCodeV2Projection.message(idle(), "ses_fixture")
        assertTrue(missing.obj("info").obj("error").obj("data").str("message")!!.contains("Check the provider connection"))
        assertNull(OpenCodeV2Projection.normalizeError(Json.obj("message" to 42)))
        assertNull(OpenCodeV2Projection.normalizeError(Json.array(listOf("error"))))
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
