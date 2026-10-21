package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.quota.*
import varro.protocol.*
import java.nio.file.Files

/** Exercises credential discovery and adapters together without live logins or provider traffic. */
class ProviderQuotaMatrixTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private data class Fixture(val id: String, val oauth: Boolean, val endpoint: String, val payload: String, val remaining: Int)
    private val fixtures = listOf(
        Fixture("openai", true, "https://chatgpt.com/backend-api/wham/usage", """{"rate_limit":{"primary_window":{"used_percent":20}}}""", 80),
        Fixture("anthropic", true, "https://api.anthropic.com/api/oauth/usage", """{"five_hour":{"utilization":17}}""", 83),
        Fixture("github-copilot", true, "https://api.github.com/copilot_internal/user", """{"quota_snapshots":{"premium_interactions":{"remaining":200,"entitlement":300}}}""", 200),
        Fixture("google", true, "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota", """{"buckets":[{"modelId":"gemini-pro","remainingFraction":0.8}]}""", 80),
        Fixture("gemini", true, "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota", """{"buckets":[{"modelId":"gemini-flash","remainingFraction":0}]}""", 0),
        Fixture("ollama-cloud", false, "https://ollama.com/api/usage", """{"limits":{"session":{"usage":0.25}}}""", 75),
        Fixture("opencode-go", false, "https://opencode.ai/inference/go/v1/usage", """{"usage":{"rolling":{"percent":70}}}""", 30),
        Fixture("openrouter", false, "https://openrouter.ai/api/v1/auth/key", """{"data":{"limit":100,"usage":35}}""", 65),
        Fixture("zai", false, "https://api.z.ai/api/monitor/usage/quota/limit", """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":45}]}}""", 55),
        Fixture("zai-coding-plan", false, "https://api.z.ai/api/monitor/usage/quota/limit", """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":6,"percentage":60}]}}""", 40),
        Fixture("minimax", false, "https://api.minimax.io/v1/api/openplatform/coding_plan/remains", """{"base_resp":{"status_code":0},"model_remains":[{"current_interval_total_count":100,"current_interval_usage_count":30}]}""", 30),
        Fixture("kimi-for-coding", false, "https://api.kimi.com/coding/v1/usages", """{"usage":{"limit":100,"used":60}}""", 40),
        Fixture("xai", true, "https://cli-chat-proxy.grok.com/v1/billing?format=credits", """{"config":{"creditUsagePercent":20,"currentPeriod":{"type":"WEEKLY"}}}""", 80),
    )

    private fun credentials(auth: JsonObject): QuotaCredentials = QuotaCredentials(temporary.newFolder().toPath(), emptyMap()).also {
        Files.createDirectories(it.authPath.parent)
        Files.writeString(it.authPath, Json.stringify(auth))
    }

    @Test fun `all credential-backed quota adapters accept realistic V1 and V2 connections`() {
        for (version in listOf(1, 2)) for (fixture in fixtures) {
            val description = "V$version ${fixture.id}"
            val token = "${fixture.id}-fixture-secret"
            val auth = if (fixture.oauth) Json.obj("type" to "oauth", "access" to token)
                else Json.obj("type" to "api", "key" to token)
            val creds = credentials(Json.obj(fixture.id to if (version == 1) auth else Json.obj("type" to "api", "key" to "stale-v1-secret")))
            if (version == 2) {
                Files.writeString(creds.authPath, "unreadable V1 fixture")
                writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(Json.obj("active" to true, "integrationID" to "integration-${fixture.id}",
                    "value" to auth.deepCopy().apply { if (!fixture.oauth) addProperty("type", "key") }))))
            } else {
                // V1 must ignore even an unreadable V2 database for every adapter.
                Files.writeString(creds.v2DatabasePath, "invalid SQLite fixture")
            }
            val databaseBefore = Files.readAllBytes(creds.v2DatabasePath)
            val updates = mutableListOf<JsonObject>()
            ProviderQuotaBackend(creds, { method, path, body, directory ->
                assertEquals(description, "GET", method)
                assertNull(body)
                when (path) {
                    "/config/providers" -> {
                        assertEquals("/fixture", directory)
                        Json.obj("providers" to listOf(Json.obj("id" to fixture.id, "integrationID" to "integration-${fixture.id}", "models" to JsonObject())))
                    }
                    else -> error("Unexpected OpenCode request for $description")
                }
            }, QuotaHttp { request ->
                if (request.url.contains("customer-package-reset") || request.url.endsWith("/GetRemainingResets"))
                    return@QuotaHttp QuotaResponse(404, byteArrayOf())
                assertEquals(description, fixture.endpoint, request.url)
                assertEquals(description, if (fixture.id.startsWith("zai")) token else "Bearer $token", request.headers["Authorization"])
                QuotaResponse(200, fixture.payload.toByteArray())
            }, updates::add, clock = { now }, apiVersion = { version }).use { backend ->
                val result = backend.get(fixture.id, "fixture-model", "/fixture")
                assertEquals(description, "available", result.str("status"))
                assertEquals(description, fixture.remaining, result.arr("windows")!![0].asJsonObject.int("remaining"))
                assertEquals(result, updates.single().obj("status"))
                val wire = Json.parse(Json.stringifyMessage(Json.message("provider-limit/updated", updates.single()))).asJsonObject
                val window = wire.obj("payload").obj("status").arr("windows")!![0].asJsonObject
                assertTrue(description, window.has("limit"))
                assertTrue(description, window.has("resetAt"))
                assertFalse(description, Json.stringifyMessage(wire).contains(token))
                assertArrayEquals(description, databaseBefore, Files.readAllBytes(creds.v2DatabasePath))
            }
        }
    }

    @Test fun `provider authentication rejection is contained and never exposes response bodies`() {
        for (version in listOf(1, 2)) for (fixture in fixtures) {
            val auth = if (fixture.oauth) Json.obj("type" to "oauth", "access" to "fixture-secret") else Json.obj("type" to "api", "key" to "fixture-secret")
            val creds = credentials(Json.obj(fixture.id to auth))
            if (version == 2) writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(Json.obj("active" to true, "integrationID" to fixture.id,
                "value" to auth.deepCopy().apply { if (!fixture.oauth) addProperty("type", "key") }))))
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                when (path) {
                    "/config/providers" -> Json.obj("providers" to listOf(Json.obj("id" to fixture.id, "models" to JsonObject())))
                    else -> error("Unexpected OpenCode request")
                }
            }, QuotaHttp { QuotaResponse(401, "private provider response with fixture-secret".toByteArray()) },
                clock = { now }, apiVersion = { version }).use { backend ->
                val status = backend.get(fixture.id, null, "/fixture")
                assertEquals("V$version ${fixture.id}", "unsupported", status.str("status"))
                assertFalse(status.has("windows"))
                assertFalse(Json.stringify(status).contains("fixture-secret"))
                assertFalse(Json.stringify(status).contains("private provider response"))
            }
        }
    }

    @Test fun `an unrelated malformed V2 credential cannot hide another provider's limits`() {
        val creds = credentials(JsonObject())
        writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(
            Json.obj("active" to true, "integrationID" to "openrouter", "value" to Json.obj("type" to "key", "key" to "fixture-secret")),
            Json.obj("active" to true, "integrationID" to "unrelated", "rawValue" to "invalid JSON"),
        )))
        ProviderQuotaBackend(creds, { _, path, _, _ ->
            when (path) {
                "/config/providers" -> Json.obj("providers" to listOf(Json.obj("id" to "openrouter", "models" to JsonObject()),
                    Json.obj("id" to "unrelated", "models" to JsonObject())))
                else -> error("Unexpected OpenCode request")
            }
        }, QuotaHttp { QuotaResponse(200, """{"data":{"limit":100,"usage":20}}""".toByteArray()) }, apiVersion = { 2 }).use { backend ->
            assertEquals("available", backend.get("openrouter", null, "/fixture").str("status"))
        }
    }

    @Test fun `metadata and local adapters do not depend on the V2 credential database`() {
        for (version in listOf(1, 2)) for (id in listOf("metadata-provider", "antigravity", "claude-code", "varro-claude")) {
            val home = temporary.newFolder().toPath()
            val creds = QuotaCredentials(home, mapOf("ANTIGRAVITY_BASE_URL" to "http://127.0.0.1:1234", "ANTIGRAVITY_CSRF_TOKEN" to "fixture-csrf"))
            val descriptor = Json.obj("schemaVersion" to 1, "transport" to "http", "url" to "http://127.0.0.1:1234/quota", "token" to "fixture-local-secret")
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                assertEquals("Local quota paths must not read server credentials", "/config/providers", path)
                Json.obj("providers" to listOf(Json.obj("id" to id, "models" to JsonObject(), "options" to Json.obj(id to Json.obj("providerLimits" to descriptor)),
                    "quota" to if (id == "metadata-provider") Json.obj("requests" to Json.obj("remaining" to 75, "limit" to 100)) else null)))
            }, QuotaHttp { request ->
                if (id == "antigravity") {
                    assertEquals("fixture-csrf", request.headers["X-Codeium-Csrf-Token"])
                    QuotaResponse(200, """{"userStatus":{"cascadeModelConfigData":{"clientModelConfigs":[{"modelOrAlias":{"model":"fixture-model"},"quotaInfo":{"remainingFraction":0.75}}]}}}""".toByteArray())
                } else {
                    assertEquals("http://127.0.0.1:1234/quota", request.url)
                    assertEquals("Bearer fixture-local-secret", request.headers["Authorization"])
                    QuotaResponse(200, Json.stringify(Json.obj("schemaVersion" to 1, "providerLimit" to Json.obj(
                        "providerID" to id, "modelID" to null, "status" to "available", "source" to "provider", "checkedAt" to now,
                        "windows" to listOf(Json.obj("id" to "five_hour", "label" to "5-Hour Limit", "unit" to "credits", "remaining" to 75, "limit" to 100, "resetAt" to null)),
                    ))).toByteArray())
                }
            }, clock = { now }, apiVersion = { version }).use { backend ->
                val result = backend.get(id, "fixture-model", "/fixture")
                assertEquals("V$version $id", "available", result.str("status"))
                assertEquals(75, result.arr("windows")!![0].asJsonObject.int("remaining"))
            }
        }
    }

    @Test fun `generic API limit headers create one window rather than inventing request and token quotas`() {
        for (id in listOf("openai", "xai")) {
            ProviderQuotaBackend(credentials(Json.obj(id to Json.obj("type" to "api", "key" to "fixture-key"))),
                { _, _, _, _ -> Json.obj("providers" to listOf(Json.obj("id" to id, "models" to JsonObject()))) },
                QuotaHttp { QuotaResponse(200, "{}".toByteArray(), mapOf("ratelimit-remaining" to "75", "ratelimit-limit" to "100", "ratelimit-reset" to "60")) },
                clock = { now }).use { backend ->
                val result = backend.get(id, null, "/fixture")
                assertEquals("available", result.str("status"))
                val windows = result.arr("windows")!!
                assertEquals(id, 1, windows.size())
                assertEquals("limit", windows[0].asJsonObject.str("id"))
                assertEquals(now + 60_000, windows[0].asJsonObject.long("resetAt"))
            }
        }
    }
}
