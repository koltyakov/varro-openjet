package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.quota.*
import varro.protocol.*
import java.nio.file.Files

/** V1-only regression coverage; every credential file and database is a temporary fixture. */
class ProviderQuotaV1CompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L

    private fun credentials(auth: JsonObject? = null, environment: Map<String, String> = emptyMap()): QuotaCredentials =
        QuotaCredentials(temporary.newFolder().toPath(), environment).also {
            if (auth != null) {
                Files.createDirectories(it.authPath.parent)
                Files.writeString(it.authPath, Json.stringify(auth))
            }
        }

    private fun metadata(id: String, options: JsonObject = JsonObject(), env: List<String> = emptyList()) = Json.obj(
        "providers" to listOf(Json.obj("id" to id, "models" to JsonObject(), "options" to options, "env" to env)))

    private fun response(body: String, code: Int = 200) = QuotaResponse(code, body.toByteArray())

    @Test fun `V1 OpenAI and Zai use auth json despite conflicting V2 accounts and database overrides`() {
        for (id in listOf("openai", "zai", "zai-coding-plan")) {
            val db = temporary.newFolder().toPath().resolve("override.db")
            val auth = if (id == "openai") Json.obj("type" to "oauth", "access" to "v1-token", "accountId" to "v1-account")
                else Json.obj("type" to "api", "key" to "v1-token")
            val creds = credentials(Json.obj(id to auth), mapOf("OPENCODE_DB" to db.toString()))
            writeQuotaCredentials(db, Json.array(listOf(Json.obj("active" to true, "integrationID" to id,
                "value" to Json.obj("type" to "oauth", "access" to "v2-token", "metadata" to Json.obj("accountID" to "v2-account"))))))
            val before = Files.readAllBytes(db)
            ProviderQuotaBackend(creds, { method, path, body, directory ->
                assertEquals("GET", method); assertEquals("/config/providers", path)
                assertNull(body); assertEquals("/v1-work", directory)
                metadata(id)
            }, QuotaHttp { request ->
                assertEquals(if (id == "openai") "Bearer v1-token" else "v1-token", request.headers["Authorization"])
                if (id == "openai") {
                    assertEquals("v1-account", request.headers["ChatGPT-Account-Id"])
                    assertEquals("https://chatgpt.com/backend-api/wham/usage", request.url)
                    response("""{"rate_limit":{"primary_window":{"used_percent":20}}}""")
                } else if (request.url.contains("customer-package-reset")) response("{}", 404)
                else {
                    assertEquals("https://api.z.ai/api/monitor/usage/quota/limit", request.url)
                    response("""{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","percentage":20}]}}""")
                }
            }, clock = { now }, apiVersion = { 1 }).use { backend ->
                val result = backend.get(id, null, "/v1-work")
                assertEquals(id, "available", result.str("status"))
                assertEquals(80, result.arr("windows")!![0].asJsonObject.int("remaining"))
                assertArrayEquals(before, Files.readAllBytes(db))
            }
        }
    }

    @Test fun `V1 API key configuration and environment fallback work without auth json or a database`() {
        for (id in listOf("openrouter", "zai", "zai-coding-plan")) for (configured in listOf(false, true)) {
            val creds = credentials(environment = mapOf("FIXTURE_API_KEY" to "environment-key"))
            val options = if (configured) Json.obj("apiKey" to "configured-key") else JsonObject()
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                assertEquals("/config/providers", path)
                metadata(id, options, listOf("FIXTURE_API_KEY"))
            }, QuotaHttp { request ->
                val key = if (configured) "configured-key" else "environment-key"
                assertEquals(if (id == "openrouter") "Bearer $key" else key, request.headers["Authorization"])
                if (id == "openrouter") response("""{"data":{"limit":100,"usage":20}}""")
                else if (request.url.contains("customer-package-reset")) response("{}", 404)
                else response("""{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","percentage":20}]}}""")
            }, clock = { now }, apiVersion = { 1 }).use { backend ->
                assertEquals("available", backend.get(id, null, null).str("status"))
                assertFalse(Files.exists(creds.authPath))
                assertFalse(Files.exists(creds.v2DatabasePath))
            }
        }
    }

    @Test fun `V1 Zai and Gemini aliases still resolve shared auth json credentials`() {
        for ((id, alias) in listOf("zai" to "zai-coding-plan", "zai-coding-plan" to "zai", "google" to "gemini", "gemini" to "google")) {
            val creds = credentials(Json.obj(alias to Json.obj("type" to "oauth", "access" to "shared-token")))
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                assertEquals("/config/providers", path); metadata(id)
            }, QuotaHttp { request ->
                assertEquals(if (id.startsWith("zai")) "shared-token" else "Bearer shared-token", request.headers["Authorization"])
                if (!id.startsWith("zai")) response("""{"buckets":[{"modelId":"fixture-model","remainingFraction":0.8}]}""")
                else if (request.url.contains("customer-package-reset")) response("{}", 404)
                else response("""{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","percentage":20}]}}""")
            }, apiVersion = { 1 }).use { backend ->
                assertEquals(id, "available", backend.get(id, null, null).str("status"))
            }
        }
    }

    @Test fun `V1 Codex Gemini and Copilot local login fallbacks remain usable`() {
        for (id in listOf("openai", "google", "gemini", "github-copilot")) {
            val creds = credentials()
            val file = when (id) {
                "openai" -> creds.home.resolve(".codex/auth.json")
                "github-copilot" -> creds.home.resolve(".config/gh/hosts.yml")
                else -> creds.home.resolve(".gemini/oauth_creds.json")
            }
            Files.createDirectories(file.parent)
            Files.writeString(file, when (id) {
                "openai" -> """{"tokens":{"access_token":"local-token","account_id":"local-account"}}"""
                "github-copilot" -> "github.com:\n  oauth_token: local-token\n"
                else -> """{"access_token":"local-token"}"""
            })
            val options = if (id == "openai") Json.obj("apiKey" to QuotaCredentials.OAUTH_DUMMY) else JsonObject()
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                assertEquals("/config/providers", path); metadata(id, options)
            }, QuotaHttp { request ->
                assertEquals("Bearer local-token", request.headers["Authorization"])
                when (id) {
                    "openai" -> {
                        assertEquals("local-account", request.headers["ChatGPT-Account-Id"])
                        response("""{"rate_limit":{"primary_window":{"used_percent":20}}}""")
                    }
                    "github-copilot" -> response("""{"quota_snapshots":{"premium_interactions":{"remaining":80,"entitlement":100}}}""")
                    else -> response("""{"buckets":[{"modelId":"fixture-model","remainingFraction":0.8}]}""")
                }
            }, apiVersion = { 1 }).use { backend ->
                assertEquals(id, "available", backend.get(id, null, null).str("status"))
                assertFalse(Files.exists(creds.v2DatabasePath))
            }
        }
    }

    @Test fun `V1 xAI refresh persists through the scoped legacy auth endpoint before polling`() {
        for (expired in listOf(false, true)) {
            val creds = credentials(Json.obj("xai" to Json.obj("type" to "oauth", "access" to "old-token", "refresh" to "old-refresh",
                "expires" to if (expired) 1L else now + 3_600_000)))
            val events = mutableListOf<String>()
            val updates = mutableListOf<JsonObject>()
            ProviderQuotaBackend(creds, { method, path, body, directory ->
                assertEquals("/v1-work", directory)
                when (path) {
                    "/config/providers" -> { assertEquals("GET", method); metadata("xai") }
                    "/auth/xai" -> {
                        assertEquals("PUT", method)
                        val value = body!!.asJsonObject
                        assertEquals("oauth", value.str("type")); assertEquals("new-token", value.str("access"))
                        assertEquals("new-refresh", value.str("refresh")); assertEquals(now + 3_600_000, value.long("expires"))
                        Files.writeString(creds.authPath, Json.stringify(Json.obj("xai" to value)))
                        events.add("persist")
                        JsonObject()
                    }
                    else -> error("V1 must not request V2 credential endpoints")
                }
            }, QuotaHttp { request ->
                when {
                    request.url == "https://auth.x.ai/oauth2/token" -> {
                        assertEquals("application/x-www-form-urlencoded", request.contentType)
                        assertTrue(request.body!!.toString(Charsets.UTF_8).contains("refresh_token=old-refresh"))
                        events.add("refresh")
                        response("""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":3600}""")
                    }
                    request.url.endsWith("/GetRemainingResets") -> response("{}", 404)
                    else -> {
                        assertEquals("https://cli-chat-proxy.grok.com/v1/billing?format=credits", request.url)
                        if (request.headers["Authorization"] == "Bearer old-token") {
                            assertFalse(expired); events.add("reject"); response("{}", 401)
                        } else {
                            assertEquals("Bearer new-token", request.headers["Authorization"])
                            assertEquals("persist", events.last()); events.add("poll")
                            response("""{"config":{"creditUsagePercent":20,"currentPeriod":{"type":"WEEKLY"}}}""")
                        }
                    }
                }
            }, updates::add, clock = { now }, apiVersion = { 1 }).use { backend ->
                val result = backend.get("xai", null, "/v1-work")
                assertEquals("available", result.str("status"))
                assertEquals((if (expired) emptyList() else listOf("reject")) + listOf("refresh", "persist", "poll"), events)
                assertEquals("new-token", creds.authStore().obj("xai").str("access"))
                assertFalse(Json.stringify(updates.single()).contains("new-token"))
                assertFalse(Files.exists(creds.v2DatabasePath))
            }
        }
    }

    @Test fun `V1 local Claude refresh retries usage and preserves credential fields`() {
        val creds = credentials()
        Files.createDirectories(creds.claudePath.parent)
        Files.writeString(creds.claudePath, """{"other":"keep","claudeAiOauth":{"accessToken":"old-token","refreshToken":"old-refresh","scopes":["usage"]}}""")
        var usageReads = 0
        ProviderQuotaBackend(creds, { _, path, _, _ ->
            assertEquals("/config/providers", path); metadata("anthropic")
        }, QuotaHttp { request ->
            if (request.url == "https://console.anthropic.com/v1/oauth/token") {
                val body = Json.parse(request.body!!.toString(Charsets.UTF_8)).asJsonObject
                assertEquals("old-refresh", body.str("refresh_token"))
                response("""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":3600}""")
            } else {
                assertEquals("https://api.anthropic.com/api/oauth/usage", request.url)
                usageReads++
                assertEquals(if (usageReads == 1) "Bearer old-token" else "Bearer new-token", request.headers["Authorization"])
                if (usageReads == 1) response("{}", 401) else response("""{"five_hour":{"utilization":20}}""")
            }
        }, clock = { now }, apiVersion = { 1 }).use { backend ->
            assertEquals("available", backend.get("anthropic", null, null).str("status"))
            assertEquals(2, usageReads)
            val saved = creds.read(creds.claudePath)!!
            assertEquals("keep", saved.str("other"))
            assertEquals("new-token", saved.obj("claudeAiOauth").str("accessToken"))
            assertEquals("new-refresh", saved.obj("claudeAiOauth").str("refreshToken"))
            assertEquals("usage", saved.obj("claudeAiOauth").arr("scopes")!![0].asString)
            assertFalse(Files.exists(creds.v2DatabasePath))
        }
    }

    @Test fun `V1 OpenAI and xAI API keys retain distinct request token and generic header windows`() {
        for (id in listOf("openai", "xai")) {
            val creds = credentials(Json.obj(id to Json.obj("type" to "api", "key" to "v1-key")))
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                assertEquals("/config/providers", path); metadata(id)
            }, QuotaHttp { request ->
                assertEquals(if (id == "openai") "https://api.openai.com/v1/models" else "https://api.x.ai/v1/models", request.url)
                assertEquals("Bearer v1-key", request.headers["Authorization"])
                QuotaResponse(200, byteArrayOf(), mapOf(
                    "x-ratelimit-remaining-requests" to "80", "x-ratelimit-limit-requests" to "100", "x-ratelimit-reset-requests" to "60",
                    "x-ratelimit-remaining-tokens" to "800", "x-ratelimit-limit-tokens" to "1000", "x-ratelimit-reset-tokens" to "120",
                    "ratelimit-remaining" to "50", "ratelimit-limit" to "100",
                ))
            }, clock = { now }, apiVersion = { 1 }).use { backend ->
                val result = backend.get(id, null, null)
                assertEquals("available", result.str("status"))
                val windows = result.arr("windows")!!.map { it.asJsonObject }
                assertEquals(listOf("requests", "tokens", "limit"), windows.map { it.str("id") })
                assertEquals(listOf(80, 800, 50), windows.map { it.int("remaining") })
                assertEquals(listOf(100, 1000, 100), windows.map { it.int("limit") })
                assertEquals(listOf(now + 60_000, now + 120_000, null), windows.map { it.long("resetAt") })
            }
        }
    }

    @Test fun `V1 missing or malformed auth json never substitutes a V2 database credential`() {
        for (malformed in listOf(false, true)) {
            val creds = credentials()
            writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(Json.obj("active" to true, "integrationID" to "openrouter",
                "value" to Json.obj("type" to "key", "key" to "v2-only-key")))))
            if (malformed) Files.writeString(creds.authPath, "invalid JSON with fixture-secret")
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                assertEquals("/config/providers", path); metadata("openrouter")
            }, QuotaHttp { error("V1 must not substitute a V2 credential") }, apiVersion = { 1 }).use { backend ->
                val result = backend.get("openrouter", null, null)
                assertEquals(if (malformed) "error" else "unsupported", result.str("status"))
                assertFalse(result.has("windows"))
                assertFalse(Json.stringify(result).contains("v2-only-key"))
                assertFalse(Json.stringify(result).contains("fixture-secret"))
            }
        }
    }
}
