package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.quota.*
import varro.protocol.*
import varro.server.OpenCodeRequestException
import java.nio.file.Files

class OpenCodeUsageLimitsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun budget(id: String = "b", coverage: String = "all", per: String = "set", models: List<JsonObject> = emptyList()) =
        Json.obj("id" to id, "coverage" to coverage, "per" to per, "limit" to 100, "spent" to 80, "resetsAt" to 2_000_000_000_000L, "models" to models)

    private fun info(vararg budgets: JsonObject) = Json.obj("workspace" to Json.obj("id" to "fixture", "name" to "Test"), "budgets" to budgets.toList())
    private fun model(id: String, spent: Int? = null) = Json.obj("providerID" to "opencode", "modelID" to id).apply {
        if (spent != null) addProperty("spent", spent)
    }

    @Test fun `Console budgets use the selected model spend and zero for absent counters`() {
        val value = info(budget(), budget("per-model", per = "model", models = listOf(model("selected", 15), model("other", 90))),
            budget("absent", coverage = "models", per = "model", models = listOf(model("selected"))),
            budget("wrong", coverage = "models", models = listOf(model("other", 90))))
        val status = OpenCodeUsageLimits.project(value, "opencode", "selected", 123)
        assertEquals("available", status.str("status")); assertEquals("opencode", status.str("source"))
        assertEquals(listOf(20, 85, 100), status.arr("windows")!!.map { it.asJsonObject.int("remaining") })
        assertEquals(123L, status.long("checkedAt"))
        assertEquals("unsupported", OpenCodeUsageLimits.project(info(), "opencode", "selected", 123).str("status"))
    }

    @Test fun `Go allowance remains separate from Console-wide budgets`() {
        val value = info(budget()).apply { add("go", Json.obj(*listOf("rolling", "weekly", "monthly").map {
            it to Json.obj("percent" to 20, "limited" to (it == "rolling"), "resetsAt" to 2_000_000_000_000L)
        }.toTypedArray())) }
        val status = OpenCodeUsageLimits.project(value, "opencode-go", "model", 123)
        assertEquals("Go", status.str("planName"))
        assertEquals(listOf(0, 80, 80), status.arr("windows")!!.map { it.asJsonObject.int("remaining") })
        assertEquals(listOf("five_hour", "weekly", "monthly"), status.arr("windows")!!.map { it.asJsonObject.str("id") })
        assertEquals(1, OpenCodeUsageLimits.project(value, "opencode", "model", 123).arr("windows")!!.size())
    }

    @Test fun `invalid payloads fail instead of reporting guessed allowance`() {
        val invalid = listOf(Json.obj(), info(budget().apply { addProperty("limit", -1) }),
            info(budget().apply { addProperty("spent", "1") }), info(budget().apply { addProperty("coverage", "unknown") }),
            info(budget(per = "model", models = listOf(model("selected").apply { addProperty("spent", -1) }))),
            info().apply { add("go", Json.obj("rolling" to Json.obj("percent" to 101))) })
        for (value in invalid) assertThrows(IllegalStateException::class.java) {
            OpenCodeUsageLimits.project(value, if (value.has("go")) "opencode-go" else "opencode", "selected", 123)
        }
    }

    private fun credentials(): QuotaCredentials = QuotaCredentials(temporary.newFolder().toPath(), emptyMap()).also {
        // Any accidental credential read would fail, not silently succeed.
        Files.createDirectories(it.authPath.parent); Files.writeString(it.authPath, "invalid local auth")
        Files.writeString(it.v2DatabasePath, "invalid local database")
    }

    @Test fun `server-owned limits work attached and local before any credential or provider HTTP access`() {
        for (attached in listOf(false, true)) for (id in listOf("opencode", "opencode-go", "custom-console")) {
            val calls = mutableListOf<String>()
            ProviderQuotaBackend(credentials(), { _, path, body, directory ->
                assertNull(body); assertEquals("/work", directory); calls += path
                when (path) {
                    "/config/providers" -> Json.obj("providers" to listOf(Json.obj("id" to id, "integrationID" to "opencode")))
                    "/api/experimental/usage/limits" -> Json.obj("data" to info(budget()))
                    else -> error("Must not request credentials")
                }
            }, QuotaHttp { error("No provider HTTP") }, apiVersion = { 2 }, attachOnly = { attached }).use {
                val result = it.get(id, "model", "/work")
                assertEquals(if (id == "opencode-go") "unsupported" else "available", result.str("status"))
                assertEquals("opencode", result.str("source"))
                assertEquals(listOf("/config/providers", "/api/experimental/usage/limits"), calls)
            }
        }
    }

    @Test fun `only generic route 404 permits a legacy adapter and never in attached mode`() {
        for (attached in listOf(false, true)) for (message in listOf("404 Not found", "404 Location not found", "401 Unauthorized", "503 Unavailable", "malformed", "signed-out")) {
            val calls = mutableListOf<String>()
            ProviderQuotaBackend(credentials(), { _, path, _, _ ->
                calls += path
                when (path) {
                    "/config/providers" -> Json.obj("providers" to listOf(Json.obj("id" to "opencode")))
                    "/api/experimental/usage/limits" -> when (message) {
                        "malformed" -> Json.obj("data" to Json.obj())
                        "signed-out" -> Json.obj("data" to null)
                        else -> throw OpenCodeRequestException(message)
                    }
                    else -> error("Unexpected request")
                }
            }, QuotaHttp { error("No HTTP") }, apiVersion = { 2 }, attachOnly = { attached }).use {
                val result = it.get("opencode", "model", null)
                val expected = if (message == "signed-out" || message == "404 Not found") "unsupported" else "error"
                assertEquals("$attached $message", expected, result.str("status"))
                if (message != "404 Not found") assertEquals("opencode", result.str("source"))
                assertFalse(result.has("windows"))
            }
        }
    }

    @Test fun `server errors retain bounded last-good data while sign-out and account changes clear it`() {
        var response = "first"
        var now = 1000L
        ProviderQuotaBackend(credentials(), { _, path, _, _ -> when (path) {
            "/config/providers" -> Json.obj("providers" to listOf(Json.obj("id" to "opencode")))
            else -> when (response) {
                "error" -> throw OpenCodeRequestException("503 Unavailable")
                "signed-out" -> Json.obj("data" to null)
                else -> Json.obj("data" to info(budget()).apply { obj("workspace")!!.addProperty("id", response) })
            }
        } }, QuotaHttp { error("No HTTP") }, clock = { now }, apiVersion = { 2 }).use {
            val first = it.get("opencode", "model", null)
            now += 30001; response = "error"
            assertEquals(first.long("checkedAt"), it.get("opencode", "model", null).long("checkedAt"))
            now += 900001
            assertEquals("error", it.get("opencode", "model", null).str("status"))
            response = "second"
            assertEquals("available", it.get("opencode", "model", null).str("status"))
            response = "signed-out"
            assertEquals("unsupported", it.get("opencode", "model", null).str("status"))
            response = "error"
            assertEquals("error", it.get("opencode", "model", null).str("status"))
        }
    }
}
