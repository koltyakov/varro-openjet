package varro.host.quota

import varro.protocol.*
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class QuotaAdaptersTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private data class Fixture(val id: String, val endpoint: String, val payload: String, val remaining: Int, val quotaId: String)

    @Test fun `provider fixtures preserve provider-specific quota semantics`() {
        val fixtures = listOf(
            Fixture("anthropic", "https://api.anthropic.com/api/oauth/usage", """{"five_hour":{"utilization":17,"resets_at":"2027-01-01T00:00:00Z"}}""", 83, "five_hour"),
            Fixture("github-copilot", "https://api.github.com/copilot_internal/user", """{"quota_snapshots":{"premium_interactions":{"remaining":200,"entitlement":300,"percent_remaining":66.6667}},"quota_reset_date_utc":"2027-01-01T00:00:00Z"}""", 200, "premium_interactions"),
            Fixture("google", "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota", """{"buckets":[{"modelId":"gemini-pro","remainingFraction":0.8}]}""", 80, "gemini-pro"),
            Fixture("gemini", "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota", """{"buckets":[{"modelId":"gemini-flash","remainingFraction":0}]}""", 0, "gemini-flash"),
            Fixture("ollama-cloud", "https://ollama.com/api/usage", """{"limits":{"session":{"usage":0.25},"weekly":{"usage":0.5}}}""", 75, "five_hour"),
            Fixture("opencode-go", "https://opencode.ai/zen/go/v1/usage", """{"usage":{"rolling":{"percent":70,"resetsAt":"2027-01-01T00:00:00Z"}}}""", 30, "five_hour"),
            Fixture("openrouter", "https://openrouter.ai/api/v1/auth/key", """{"data":{"limit":100,"usage":35}}""", 65, "spend"),
            Fixture("zai-coding-plan", "https://api.z.ai/api/monitor/usage/quota/limit", """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":45}]}}""", 55, "five_hour"),
            Fixture("minimax", "https://api.minimax.io/v1/api/openplatform/coding_plan/remains", """{"base_resp":{"status_code":0},"model_remains":[{"current_interval_total_count":100,"current_interval_usage_count":30,"remains_time":60000}]}""", 30, "requests"),
            Fixture("kimi-for-coding", "https://api.kimi.com/coding/v1/usages", """{"usage":{"limit":100,"used":60,"resetTime":"2027-01-01T00:00:00Z"}}""", 40, "seven_day"),
            Fixture("xai", "https://cli-chat-proxy.grok.com/v1/billing?format=credits", """{"config":{"creditUsagePercent":20,"currentPeriod":{"type":"WEEKLY","end":"2027-01-01T00:00:00Z"}}}""", 80, "credits"),
        )
        fixtures.forEach { fixture ->
            val credentials = QuotaCredentials(temporary.newFolder().toPath(), emptyMap())
            val adapters = QuotaAdapters(QuotaHttp { request ->
                if (request.url.contains("customer-package-reset") || request.url.endsWith("/GetRemainingResets")) {
                    return@QuotaHttp QuotaResponse(404, byteArrayOf())
                }
                assertEquals(fixture.id, fixture.endpoint, request.url)
                assertEquals(fixture.id, if (fixture.id.startsWith("zai")) "test-token" else "Bearer test-token", request.headers["Authorization"])
                if (fixture.id in setOf("google", "gemini")) assertEquals("{}", request.body?.toString(Charsets.UTF_8))
                QuotaResponse(200, fixture.payload.toByteArray())
            }, credentials) { _, _ -> fail("Unexpected OAuth refresh") }
            val provider = Json.obj("id" to fixture.id, "models" to JsonObject())
            val auth = Json.obj(fixture.id to Json.obj("type" to "oauth", "access" to "test-token"))
            val result = adapters.poll(provider, auth, "test-model", now)!!
            assertEquals(fixture.id, "available", result.str("status"))
            val window = result.arr("windows")!![0].asJsonObject
            assertEquals(fixture.id, fixture.quotaId, window.str("id"))
            assertEquals(fixture.id, fixture.remaining, window.int("remaining"))
            assertTrue(fixture.id, window.has("resetAt"))
        }
    }

    @Test fun `Codex free plan and Spark quotas are distinct`() {
        val free = Json.parse("""{"plan_type":"free","rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":604800}},"spark_rate_limit":{"primary_window":{"used_percent":80},"secondary_window":{"used_percent":10}}}""").asJsonObject
        val windows = QuotaParsers.codex(free, now)
        assertEquals(listOf("seven_day", "spark_five_hour", "spark_seven_day"), windows.map { it.str("id") })
        assertEquals(listOf(75, 20, 90), windows.map { it.int("remaining") })
    }

    @Test fun `Copilot unlimited buckets are skipped and legacy usage is subtracted once`() {
        assertEquals(emptyList<JsonObject>(), QuotaParsers.copilot(Json.parse("""{"quota_snapshots":{"chat":{"unlimited":true,"remaining":100}}}""").asJsonObject, now))
        val legacy = QuotaParsers.copilot(Json.parse("""{"limited_user_quotas":{"chat":20},"monthly_quotas":{"chat":100}}""").asJsonObject, now)
        assertEquals(80, legacy.single().int("remaining"))
    }

    @Test fun `credential fallback honors isolated XDG and provider home overrides`() {
        val home = temporary.newFolder().toPath()
        val data = temporary.newFolder().toPath()
        val codexHome = temporary.newFolder().toPath()
        val creds = QuotaCredentials(home, mapOf("XDG_DATA_HOME" to data.toString(), "CODEX_HOME" to codexHome.toString()))
        assertEquals(data.resolve("opencode/auth.json"), creds.authPath)
        Files.writeString(codexHome.resolve("auth.json"), """{"tokens":{"access_token":"fallback","account_id":"account"}}""")
        assertEquals("fallback", creds.codex(JsonObject()).str("access"))
        val preferred = Json.obj("openai" to Json.obj("type" to "oauth", "access" to "opencode", "accountId" to "current"))
        assertEquals("opencode", creds.codex(preferred).str("access"))
        assertEquals("current", creds.codex(preferred).str("accountId"))
    }

    @Test fun `Claude token refresh preserves unrelated fields and rejects changed credentials`() {
        val creds = QuotaCredentials(temporary.newFolder().toPath(), emptyMap())
        Files.createDirectories(creds.claudePath.parent)
        Files.writeString(creds.claudePath, """{"other":"keep","claudeAiOauth":{"accessToken":"old","refreshToken":"old-refresh","scopes":["usage"]}}""")
        creds.updateClaude("old-refresh", Json.obj("access_token" to "new", "refresh_token" to "new-refresh", "expires_in" to 60), now)
        val saved = creds.read(creds.claudePath)!!
        assertEquals("keep", saved.str("other"))
        assertEquals("new", saved.obj("claudeAiOauth").str("accessToken"))
        assertEquals(now + 60_000, saved.obj("claudeAiOauth").long("expiresAt"))
        assertEquals("usage", saved.obj("claudeAiOauth").arr("scopes")!![0].asString)
        assertThrows(QuotaFailure::class.java) { creds.updateClaude("old-refresh", Json.obj("access_token" to "stale"), now) }
        assertEquals("new", creds.read(creds.claudePath).obj("claudeAiOauth").str("accessToken"))
    }

    @Test fun `Antigravity quotas select the normalized model and hide thinking suffix`() {
        val payload = Json.parse("""{"userStatus":{"cascadeModelConfigData":{"clientModelConfigs":[{"modelOrAlias":{"model":"claude-sonnet"},"label":"Claude Sonnet (Thinking)","quotaInfo":{"remainingFraction":0.75}},{"modelOrAlias":{"model":"gemini-pro"},"quotaInfo":{"remainingFraction":0.5}}]}}}""").asJsonObject
        val result = AntigravityQuota.parse(payload, "Claude_Sonnet", now)
        assertEquals(1, result.size)
        assertEquals("Claude Sonnet", result.single().str("label"))
        assertEquals(75, result.single().int("remaining"))
    }

    @Test fun `reset parsing handles durations epoch seconds and ISO dates without inventing missing values`() {
        assertEquals(now + 3_723_500, resetAt(Json.toElement("1h2m3s500ms"), now))
        assertEquals(2_000_000_000_000L, resetAt(Json.toElement("2000000000"), now))
        assertEquals(2_000_000_000_000L, resetAt(Json.toElement(2_000_000_000_000L), now))
        assertEquals(1_798_761_600_000L, resetAt(Json.toElement("2027-01-01T00:00:00Z"), now))
        assertNull(resetAt(Json.toElement("unknown"), now))
        assertNull(resetAt(null, now))
    }

    @Test fun `Grok read-only RPC decodes quota resets and rejects malformed frames`() {
        fun frame(payload: ByteArray, flags: Byte = 0) = java.nio.ByteBuffer.allocate(payload.size + 5)
            .put(flags).putInt(payload.size).put(payload).array()
        val credits = java.util.HexFormat.of().parseHex("0a082a060880a8d6b907")
        val resets = java.util.HexFormat.of().parseHex("0a0c0a0269641a060880a8d6b907")
        val rpc = GrokQuotaRpc(QuotaHttp { request ->
            assertEquals("application/grpc-web+proto", request.contentType)
            assertArrayEquals(ByteArray(5), request.body)
            QuotaResponse(200, if (request.url.endsWith("GetRemainingResets"))
                frame(resets) + frame("grpc-status: 0\r\n".toByteArray(), 0x80.toByte()) else frame(credits))
        }, "fixture")
        assertEquals(2_000_000_000_000L, rpc.credits(now).long("resetAt"))
        val reset = rpc.resets(now)!!
        assertEquals(1, reset.int("availableCount"))
        assertEquals(2_000_000_000_000L, reset.arr("credits")!![0].asJsonObject.long("expiresAt"))
        assertThrows(QuotaFailure::class.java) { GrokQuotaRpc.frames(byteArrayOf(0, 0, 0, 0, 10, 1)) }
        assertThrows(QuotaFailure::class.java) { GrokQuotaRpc.frames(frame("grpc-status: 16\r\n".toByteArray(), 0x80.toByte())) }
        assertThrows(QuotaFailure::class.java) { GrokQuotaRpc.frames(frame(resets), requireTrailers = true) }
    }
}
