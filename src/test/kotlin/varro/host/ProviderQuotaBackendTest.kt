package varro.host

import com.google.gson.JsonObject
import varro.host.quota.*
import varro.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProviderQuotaBackendTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun credentials(auth: JsonObject = JsonObject()): QuotaCredentials {
        val home = temporary.newFolder().toPath()
        return QuotaCredentials(home, emptyMap()).also {
            Files.createDirectories(it.authPath.parent)
            Files.writeString(it.authPath, Json.stringify(auth))
        }
    }

    private fun metadata(id: String, options: JsonObject = JsonObject()) = Json.obj("providers" to Json.array(listOf(
        Json.obj("id" to id, "models" to JsonObject(), "options" to options),
    )))
    private fun response(json: String, code: Int = 200) = QuotaResponse(code, json.toByteArray())

    @Test fun `the runtime no longer packages the Node quota helper`() {
        assertNull(ProviderQuotaBackend::class.java.getResource("/quota/provider-quota.mjs"))
    }

    @Test fun `wire encoding preserves explicit null quota fields`() {
        val result = Json.obj("windows" to Json.array(listOf(Json.obj("id" to "spend", "remaining" to 10, "limit" to null, "resetAt" to null))))
        val envelope = Json.message("provider-limit/updated", Json.obj("directory" to null, "status" to result))
        val wire = Json.parse(Json.stringifyMessage(envelope)).asJsonObject.obj("payload").obj("status").arr("windows")!![0].asJsonObject
        assertTrue("Missing null limit makes the webview reject quota updates", wire.has("limit"))
        assertTrue("Missing null resetAt makes the webview reject quota updates", wire.has("resetAt"))
        assertTrue(wire.get("limit").isJsonNull)
        val api = Json.parse(Json.stringifyMessage(Json.message("api/response", Json.obj("id" to 1, "data" to result)))).asJsonObject
        assertTrue(api.obj("payload").obj("data").arr("windows")!![0].asJsonObject.has("resetAt"))
    }

    @Test fun `native Codex poll sends account headers and caches quota HTTP requests`() {
        val creds = credentials(Json.obj("openai" to Json.obj("type" to "oauth", "access" to "test-token", "accountId" to "test-account")))
        val requests = AtomicInteger()
        val updates = mutableListOf<JsonObject>()
        ProviderQuotaBackend(creds, { method, path, _, directory ->
            assertEquals("GET", method); assertEquals("/config/providers", path); assertEquals("/work", directory)
            metadata("openai")
        }, QuotaHttp {
            requests.incrementAndGet()
            assertEquals("https://chatgpt.com/backend-api/wham/usage", it.url)
            assertEquals("Bearer test-token", it.headers["Authorization"])
            assertEquals("test-account", it.headers["ChatGPT-Account-Id"])
            response("""{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":20,"reset_at":2000000000},"secondary_window":{"used_percent":40}}}""")
        }, updates::add).use { backend ->
            val first = backend.get("openai", "gpt-test", "/work")
            assertEquals("available", first.str("status"))
            assertEquals("Pro 20x", first.str("planName"))
            assertEquals(80, first.arr("windows")!![0].asJsonObject.int("remaining"))
            assertEquals(first, backend.get("openai", "gpt-test", "/work"))
            assertEquals(1, requests.get())
            assertEquals("/work", updates.single().str("directory"))
            backend.clearCache()
            backend.get("openai", "gpt-test", "/work")
            assertEquals(2, requests.get())
        }
    }

    @Test fun `V2 Codex limits use database OAuth and account metadata instead of legacy auth`() {
        val creds = credentials()
        // An unreadable V1 store must not interfere with a valid V2 connection.
        Files.writeString(creds.authPath, "not JSON")
        writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(
            Json.obj("active" to false, "integrationID" to "codex-account", "value" to Json.obj("type" to "oauth", "access" to "inactive-token")),
            Json.obj("active" to true, "integrationID" to "codex-account", "value" to Json.obj("type" to "oauth", "access" to "v2-token",
                "metadata" to Json.obj("accountID" to "v2-account"))),
        )))
        var polls = 0
        val updates = mutableListOf<JsonObject>()
        ProviderQuotaBackend(creds, { method, path, body, directory ->
            assertEquals("GET", method)
            assertNull(body)
            when (path) {
                "/config/providers" -> {
                    assertEquals("/work", directory)
                    Json.obj("providers" to listOf(Json.obj("id" to "openai", "integrationID" to "codex-account", "models" to JsonObject())))
                }
                else -> error("Unexpected OpenCode request")
            }
        }, QuotaHttp {
            polls++
            assertEquals("https://chatgpt.com/backend-api/wham/usage", it.url)
            assertEquals("Bearer v2-token", it.headers["Authorization"])
            assertEquals("v2-account", it.headers["ChatGPT-Account-Id"])
            response("""{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":20},"secondary_window":{"used_percent":40}}}""")
        }, updates::add, apiVersion = { 2 }).use { backend ->
            val result = backend.get("openai", "gpt-test", "/work")
            assertEquals("available", result.str("status"))
            assertEquals(80, result.arr("windows")!![0].asJsonObject.int("remaining"))
            assertEquals(result, backend.get("openai", "gpt-test", "/work"))
            assertEquals(1, polls)
            for (status in updates + result) {
                assertFalse(Json.stringify(status).contains("v2-token"))
                assertFalse(Json.stringify(status).contains("v2-account"))
                assertFalse(Json.stringify(status).contains("inactive-token"))
            }
        }
    }

    @Test fun `V2 API keys bypass stale V1 credentials`() {
        val creds = credentials(Json.obj("openrouter" to Json.obj("type" to "api", "key" to "stale-v1-key")))
        writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(Json.obj("active" to true, "integrationID" to "openrouter",
            "value" to Json.obj("type" to "key", "key" to "v2-key")))))
        ProviderQuotaBackend(creds, { _, path, _, _ ->
            when (path) {
                "/config/providers" -> metadata("openrouter")
                else -> error("Unexpected OpenCode request")
            }
        }, QuotaHttp {
            assertEquals("Bearer v2-key", it.headers["Authorization"])
            response("""{"data":{"limit":100,"usage":35}}""")
        }, apiVersion = { 2 }).use { backend ->
            assertEquals("available", backend.get("openrouter", null, null).str("status"))
        }
    }

    @Test fun `missing invalid or unavailable V2 credentials never fall back to V1`() {
        for (failure in listOf("empty", "invalid", "unavailable")) {
            val creds = credentials(Json.obj("openai" to Json.obj("type" to "oauth", "access" to "stale-v1-token")))
            if (failure != "unavailable") writeQuotaCredentials(creds.v2DatabasePath, Json.array(
                if (failure == "empty") emptyList() else listOf(Json.obj("active" to true, "integrationID" to "openai", "rawValue" to "invalid JSON"))))
            ProviderQuotaBackend(creds, { _, path, _, _ ->
                when (path) {
                    "/config/providers" -> metadata("openai")
                    else -> error("Unexpected OpenCode request")
                }
            }, QuotaHttp { error("Must not use stale credentials") }, apiVersion = { 2 }).use { backend ->
                val result = backend.get("openai", "gpt-test", null)
                assertEquals(failure, if (failure == "empty") "unsupported" else "error", result.str("status"))
                assertFalse(result.has("windows"))
                assertFalse(Json.stringify(result).contains("stale-v1-token"))
            }
        }
    }

    @Test fun `V2 account changes invalidate cached quotas and last-good fallback`() {
        var account = "first"
        var polls = 0
        val creds = credentials()
        fun writeAccount() = writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(Json.obj("active" to true, "integrationID" to "openai",
            "value" to Json.obj("type" to "oauth", "access" to "$account-token", "metadata" to Json.obj("accountId" to account))))))
        writeAccount()
        ProviderQuotaBackend(creds, { _, path, _, _ ->
            when (path) {
                "/config/providers" -> metadata("openai")
                else -> error("Unexpected OpenCode request")
            }
        }, QuotaHttp {
            polls++
            assertEquals(account, it.headers["ChatGPT-Account-Id"])
            if (account == "first") response("""{"rate_limit":{"primary_window":{"used_percent":20}}}""")
            else response("{}", 429)
        }, apiVersion = { 2 }).use { backend ->
            assertEquals("available", backend.get("openai", null, null).str("status"))
            account = "second"
            writeAccount()
            val next = backend.get("openai", null, null)
            assertEquals("error", next.str("status"))
            assertFalse(next.has("windows"))
            assertEquals(2, polls)
        }
    }

    @Test fun `V2 owns OAuth refresh instead of quota polling writing legacy secrets`() {
        var polls = 0
        val creds = credentials()
        writeQuotaCredentials(creds.v2DatabasePath, Json.array(listOf(Json.obj("active" to true, "integrationID" to "xai",
            "value" to Json.obj("type" to "oauth", "access" to "server-token", "refresh" to "server-refresh", "expires" to 1)))))
        val before = Files.readAllBytes(creds.v2DatabasePath)
        ProviderQuotaBackend(creds, { method, path, _, _ ->
            assertEquals("GET", method)
            when (path) {
                "/config/providers" -> metadata("xai")
                else -> error("Unexpected OpenCode request")
            }
        }, QuotaHttp {
            polls++
            assertEquals("https://cli-chat-proxy.grok.com/v1/billing?format=credits", it.url)
            assertEquals("Bearer server-token", it.headers["Authorization"])
            response("{}", 401)
        }, apiVersion = { 2 }).use { backend ->
            assertEquals("unsupported", backend.get("xai", null, null).str("status"))
            assertEquals(1, polls)
            assertArrayEquals(before, Files.readAllBytes(creds.v2DatabasePath))
        }
    }

    @Test fun `credential changes bypass a cached result and never reuse another account's quota`() {
        val creds = credentials(Json.obj("openrouter" to Json.obj("type" to "api", "key" to "first")))
        ProviderQuotaBackend(creds, { _, _, _, _ -> metadata("openrouter") }, QuotaHttp {
            if (it.headers["Authorization"] == "Bearer first") response("""{"data":{"limit":100,"usage":10}}""")
            else response("{}", 401)
        }).use { backend ->
            assertEquals("available", backend.get("openrouter", null, "/one").str("status"))
            Files.writeString(creds.authPath, """{"openrouter":{"type":"api","key":"second"}}""")
            val status = backend.get("openrouter", null, "/one")
            assertEquals("unsupported", status.str("status"))
            assertFalse(status.has("windows"))
            assertFalse(Json.stringify(status).contains("second"))
        }
    }

    @Test fun `rate limiting backs off and retains a bounded last successful snapshot`() {
        val creds = credentials(Json.obj("openrouter" to Json.obj("type" to "api", "key" to "test")))
        val requests = AtomicInteger()
        var now = 1_800_000_000_000L
        ProviderQuotaBackend(creds, { _, _, _, _ -> metadata("openrouter") }, QuotaHttp {
            if (requests.incrementAndGet() == 1) response("""{"data":{"limit":100,"usage":25}}""") else response("{}", 429)
        }, clock = { now }).use { backend ->
            val first = backend.get("openrouter", null, null)
            now += 30_001
            val stale = backend.get("openrouter", null, null)
            assertEquals("available", stale.str("status"))
            assertEquals(first.long("checkedAt"), stale.long("checkedAt"))
            assertTrue(stale.str("note")!!.contains("429"))
            now += 59_000
            backend.get("openrouter", null, null)
            assertEquals(2, requests.get())
            now += 901_000
            assertEquals("error", backend.get("openrouter", null, null).str("status"))
        }
    }

    @Test(timeout = 10_000) fun `sequential polls recheck expiry instead of reusing a completed in-flight result`() {
        val creds = credentials(Json.obj("openrouter" to Json.obj("type" to "api", "key" to "test")))
        val requests = AtomicInteger()
        val now = AtomicLong(1_800_000_000_000L)
        ProviderQuotaBackend(creds, { _, _, _, _ -> metadata("openrouter") }, QuotaHttp {
            requests.incrementAndGet()
            response("""{"data":{"limit":100,"usage":25}}""")
        }, clock = now::get).use { backend ->
            repeat(1_000) { poll ->
                val status = backend.get("openrouter", null, null)
                assertEquals("Poll $poll returned an expired result", now.get(), status.long("checkedAt"))
                assertEquals(poll + 1, requests.get())
                now.addAndGet(30_001)
            }
        }
    }

    @Test(timeout = 10_000) fun `concurrent callers share a poll and invalidation retires late updates`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val updates = AtomicInteger()
        ProviderQuotaBackend(credentials(Json.obj("ollama-cloud" to Json.obj("type" to "api", "key" to "test"))),
            { _, _, _, _ -> metadata("ollama-cloud") }, QuotaHttp {
                entered.countDown(); release.await()
                response("""{"limits":{"session":{"usage":0.5}}}""")
            }, { updates.incrementAndGet() }).use { backend ->
            val first = CompletableFuture.supplyAsync { backend.get("ollama-cloud", null, null) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            backend.clearCache()
            release.countDown()
            assertEquals("error", first.get(2, TimeUnit.SECONDS).str("status"))
            assertEquals(0, updates.get())
            assertEquals("available", backend.get("ollama-cloud", null, null).str("status"))
        }
    }

    @Test fun `project metadata remains isolated and requires no credentials or runtime`() {
        ProviderQuotaBackend(credentials(), { _, _, _, directory ->
            val provider = Json.obj("id" to "custom", "models" to JsonObject(),
                "quota" to Json.obj("requests" to Json.obj("remaining" to if (directory == "/one") 75 else 25, "limit" to 100)))
            Json.obj("providers" to Json.array(listOf(provider)))
        }, QuotaHttp { error("Metadata quota must not contact a provider") }).use { backend ->
            assertEquals(75, backend.get("custom", null, "/one").arr("windows")!![0].asJsonObject.int("remaining"))
            assertEquals(25, backend.get("custom", null, "/two").arr("windows")!![0].asJsonObject.int("remaining"))
        }
    }

    @Test fun `loopback descriptors cannot send tokens to arbitrary hosts`() {
        ProviderQuotaBackend(credentials(), { _, _, _, _ -> metadata("claude-code", Json.obj("claude-code" to Json.obj("providerLimits" to
            Json.obj("schemaVersion" to 1, "transport" to "http", "url" to "http://example.com/quota", "token" to "secret")))) },
            QuotaHttp { error("Must reject the descriptor before HTTP") }).use { backend ->
            val status = backend.get("claude-code", null, null)
            assertEquals("error", status.str("status"))
            assertFalse(Json.stringify(status).contains("secret"))
        }
    }
}
