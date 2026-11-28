package varro.host

import com.google.gson.JsonArray
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class PromptCacheLifetimeTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun ttlOf(providerID: String, modelID: String, ttl: String? = null, npm: String? = null) =
        resolvePromptCacheLifetime(PromptCacheModel(providerID, modelID), ttl = ttl, npm = npm).ttlMs

    private fun resolve(providerID: String, modelID: String, ttl: String? = null, npm: String? = null, priced: Boolean? = null) =
        resolvePromptCacheLifetime(PromptCacheModel(providerID, modelID), ttl = ttl, npm = npm, priced = priced)

    @Test fun `parses OpenCode cache ttl durations`() {
        assertEquals(5 * minute, parsePromptCacheTtl("5m"))
        assertEquals(hour, parsePromptCacheTtl("1h"))
        assertEquals(90_000L, parsePromptCacheTtl("90s"))
        assertNull(parsePromptCacheTtl("0m"))
        assertNull(parsePromptCacheTtl("in_memory"))
        assertNull(parsePromptCacheTtl(null))
    }

    @Test fun `applies Claude defaults and the 1 h ttl on every host`() {
        assertEquals(5 * minute, ttlOf("anthropic", "claude-sonnet-5-5"))
        assertEquals(hour, ttlOf("anthropic", "claude-opus-5-5", ttl = "1h"))
        assertEquals(hour, ttlOf("openrouter", "anthropic/claude-opus-5-5", ttl = "1h"))
        assertEquals(hour, ttlOf("amazon-bedrock", "us.anthropic.claude-sonnet-5-5-v1:0", ttl = "1h"))
        // Older Bedrock releases cache for five minutes only, so OpenCode drops the 1 h ttl.
        assertEquals(5 * minute, ttlOf("amazon-bedrock", "anthropic.claude-sonnet-4-20250514-v1:0", ttl = "1h"))
    }

    @Test fun `recognizes Claude Code model aliases, whose steps hold several requests`() {
        // Claude Agent prices models only for API billing; subscriptions get Claude Code's 1 h cache.
        for (lifetime in listOf(
            resolve("claude-agent", "sonnet", ttl = "5m"),
            resolve("my-claude", "fable", npm = "aisdk:@opencode/claude-agent", priced = false),
        )) {
            assertEquals(hour, lifetime.ttlMs)
            assertTrue(lifetime.multiRequestSteps)
        }
        val api = resolve("claude-agent", "opus", priced = true)
        assertEquals(5 * minute, api.ttlMs)
        assertTrue(api.multiRequestSteps)
        assertFalse(resolve("anthropic", "claude-opus-5-5").multiRequestSteps)
    }

    @Test fun `does not treat Anthropic-compatible APIs of other models as Claude`() {
        val lifetime = resolve("my-gateway", "glm-5.3", npm = "@ai-sdk/anthropic")
        assertEquals(5 * minute, lifetime.ttlMs)
        assertEquals("fallback", lifetime.source)
    }

    @Test fun `follows other popular providers`() {
        fun lifetime(providerID: String, modelID: String, ttl: String? = null): List<Long> {
            val resolved = resolve(providerID, modelID, ttl = ttl)
            return listOf(resolved.ttlMs / minute, (resolved.likelyMs ?: resolved.ttlMs) / minute)
        }
        assertEquals(listOf(5L, 15L), lifetime("google", "gemini-4-argon"))
        assertEquals(listOf(5L, 15L), lifetime("google-vertex", "gemini-3.8-flash"))
        assertEquals(listOf(5L, 15L), lifetime("openrouter", "google/gemini-4-argon"))
        assertEquals(listOf(60L, 360L), lifetime("deepseek", "deepseek-v4-flash"))
        assertEquals(listOf(5L, 30L), lifetime("xai", "grok-4.6"))
        assertEquals(listOf(5L, 30L), lifetime("openrouter", "x-ai/grok-4.6"))
        assertEquals(listOf(5L, 60L), lifetime("zai-coding-plan", "glm-5.3"))
        assertEquals(listOf(5L, 5L), lifetime("kimi-for-coding", "k3"))
        assertEquals(listOf(60L, 60L), lifetime("moonshotai", "kimi-k3.1-pro", "1h"))
        assertEquals(listOf(5L, 5L), lifetime("alibaba", "qwen3.8-max", "1h"))
        assertEquals(listOf(60L, 60L), lifetime("alibaba", "qwen4-max", "1h"))
        assertEquals(listOf(5L, 120L), lifetime("groq", "openai/gpt-oss-120b"))
        assertEquals(listOf(5L, 60L), lifetime("fireworks-ai", "accounts/fireworks/models/glm-5"))
        assertEquals(listOf(5L, 15L), lifetime("meta", "muse-spark-1.3"))
        assertEquals(listOf(5L, 60L), lifetime("meta", "muse-spark-1.3", "24h"))
        assertEquals(listOf(5L, 5L), lifetime("amazon-bedrock", "amazon.nova-pro-v1:0"))
        assertEquals(listOf(30L, 180L), lifetime("amazon-bedrock", "openai.gpt-6.1-sol", "12h"))
        assertEquals(listOf(5L, 10L), lifetime("amazon-bedrock", "openai.gpt-5.4"))
        assertEquals(listOf(5L, 5L), lifetime("kimi-code-plan-global", "kimi-k3"))
        assertEquals(listOf(60L, 60L), lifetime("alibaba-token-plan-cn", "qwen4-max", "1h"))
        assertEquals(listOf(3L, 5L), lifetime("mistral", "mistral-large-latest"))
        // Open-weight models on OpenRouter can be served by any host, with its own cache.
        assertEquals(listOf(5L, 15L), lifetime("openrouter", "deepseek/deepseek-v4"))
        assertEquals(listOf(5L, 15L), lifetime("github-copilot", "mai-code-1.1-flash"))
    }

    @Test fun `strips Bedrock regional OpenAI prefixes and maps OpenRouter vendors without a slash like JavaScript`() {
        // `us.openai.gpt-6.1-sol` names the same model as `openai.gpt-6.1-sol` on Bedrock.
        assertEquals(30 * minute, ttlOf("amazon-bedrock", "us.openai.gpt-6.1-sol"))
        // Upstream slices to `indexOf('/')`, so an ID without a slash drops its last character.
        assertEquals(30 * minute, resolve("openrouter", "x-aiz").likelyMs)
        assertEquals("fallback", resolve("openrouter", "google").source)
    }

    @Test fun `follows OpenAI retention by model generation`() {
        // GPT-5.6 and later: a 30 min minimum by default that often lasts about 3 h, 12 h when configured.
        val gpt6 = resolve("openai", "gpt-6.2-sol")
        assertEquals(30 * minute, gpt6.ttlMs)
        assertEquals(3 * hour, gpt6.likelyMs)
        assertEquals(30 * minute, ttlOf("openai", "gpt-5.6"))
        assertEquals(12 * hour, ttlOf("openai", "gpt-6-luna", ttl = "12h"))
        assertEquals(30 * minute, ttlOf("openrouter", "openai/gpt-6.1-terra"))
        // 24-hour retention usually lasts about 30 min.
        assertEquals(30 * minute, ttlOf("openai", "gpt-5.5"))
        assertEquals(30 * minute, ttlOf("opencode", "gpt-5.1-codex"))
        assertEquals(30 * minute, ttlOf("openai", "gpt-4.1-2025-04-14"))
        assertEquals("configured", resolve("openai", "gpt-5.1", ttl = "24h").source)
        // In-memory retention otherwise.
        assertEquals(5 * minute, ttlOf("openai", "gpt-4.1-mini"))
        assertEquals(5 * minute, ttlOf("openai", "gpt-4o"))
        assertEquals(5 * minute, ttlOf("openai", "o4-mini"))
    }

    @Test fun `leaves GPT-named models on other hosts to the generic estimate`() {
        val oss = resolve("groq", "openai/gpt-oss-120b")
        assertEquals(5 * minute, oss.ttlMs)
        assertEquals(2 * hour, oss.likelyMs)
        assertEquals("fallback", resolve("github-copilot", "gpt-6.2-sol").source)
        assertEquals(30 * minute, ttlOf("my-gateway", "gpt-6.2-sol", npm = "@ai-sdk/openai"))
        assertEquals(30 * minute, ttlOf("my-gateway", "gpt-6.2-sol", npm = "aisdk:@ai-sdk/openai"))
    }

    @Test fun `uses a configured ttl for other providers`() {
        val lifetime = resolve("my-gateway", "deepseek-v4", ttl = "1h")
        assertEquals(hour, lifetime.ttlMs)
        assertEquals("configured", lifetime.source)
        assertEquals("The model's cache.ttl keeps the cache 1 h after each request.", lifetime.description)
    }

    private fun model(id: String, extra: String = "") =
        """"$id":{"id":"$id","name":"$id","capabilities":{},"cost":{"input":0,"output":0}$extra}"""

    @Test fun `reads cache ttl from model settings over provider settings`() {
        val agent = Json.parse(
            """[{"id":"claude-agent","name":"claude-agent","source":"api","models":{${model("opus")},
               "sonnet":{"id":"sonnet","name":"sonnet","capabilities":{},"cost":{"input":3,"output":15}}}}]""",
        ).asJsonArray
        assertEquals(hour, promptCacheLifetimeFor(agent, PromptCacheModel("claude-agent", "opus")).ttlMs)
        assertEquals(5 * minute, promptCacheLifetimeFor(agent, PromptCacheModel("claude-agent", "sonnet")).ttlMs)
        val providers = Json.parse(
            """[{"id":"anthropic","name":"anthropic","source":"api","options":{"cache":{"ttl":"1h"}},"models":{
               ${model("sonnet", ""","api":{"id":"claude-sonnet-5-5","npm":"@ai-sdk/anthropic","url":""}""")},
               ${model("opus", ""","options":{"cache":{"ttl":"5m"}},"api":{"id":"claude-opus-5-5","npm":"@ai-sdk/anthropic","url":""}""")}}}]""",
        ).asJsonArray
        assertEquals(hour, promptCacheLifetimeFor(providers, PromptCacheModel("anthropic", "sonnet")).ttlMs)
        assertEquals(5 * minute, promptCacheLifetimeFor(providers, PromptCacheModel("anthropic", "opus")).ttlMs)
        assertEquals(5 * minute, promptCacheLifetimeFor(null, PromptCacheModel("anthropic", "claude-x-1")).ttlMs)
        assertEquals(5 * minute, promptCacheLifetimeFor(JsonArray(), PromptCacheModel("anthropic", "claude-x-1")).ttlMs)
    }

    @Test fun `counts from the request when a response is one request, else from its end`() {
        val claude = resolve("anthropic", "claude-x-1")
        val agent = resolve("claude-agent", "opus")
        assertNull(claude.likelyMs)
        assertEquals(1_000L, promptCacheUsedAt(1_000, 9_000, 0, claude))
        assertEquals(1_000L, promptCacheUsedAt(1_000, 9_000, 1, claude))
        assertEquals(9_000L, promptCacheUsedAt(1_000, 9_000, 2, claude))
        assertEquals(9_000L, promptCacheUsedAt(1_000, 9_000, 0, agent))
    }

    private fun learn(vararg samples: Triple<Int, Int, Int>): LearnedPromptCacheLifetime? {
        var histogram = emptyPromptCacheHistogram()
        for ((minutes, hits, misses) in samples) {
            repeat(hits) { histogram = addPromptCacheSample(histogram, PromptCacheSample(minutes * minute.toDouble(), true)) }
            repeat(misses) { histogram = addPromptCacheSample(histogram, PromptCacheSample(minutes * minute.toDouble(), false)) }
        }
        return learnPromptCacheLifetime(histogram)
    }

    @Test fun `finds the end of a hard lifetime, skipping sparse gaps`() {
        // Claude Code on a subscription: alive through 1 h, gone after.
        assertEquals(
            LearnedPromptCacheLifetime(hour, true),
            learn(
                Triple(7, 34, 3), Triple(15, 16, 5), Triple(25, 6, 1), Triple(40, 6, 2),
                Triple(50, 5, 1), Triple(70, 0, 1), Triple(100, 0, 2), Triple(200, 0, 3),
            ),
        )
        // API billing: gone after 5 min.
        assertEquals(LearnedPromptCacheLifetime(5 * minute, true), learn(Triple(7, 0, 4)))
    }

    @Test fun `finds where a gradual decline drops below half`() {
        // OpenAI GPT-6: 94% to 70% hits up to 3 h, 44% after.
        assertEquals(
            LearnedPromptCacheLifetime(3 * hour, true),
            learn(Triple(20, 30, 2), Triple(50, 13, 2), Triple(75, 8, 2), Triple(100, 7, 2), Triple(150, 7, 3), Triple(240, 4, 5), Triple(500, 0, 10)),
        )
    }

    @Test fun `treats survived gaps without misses as a floor`() {
        assertEquals(LearnedPromptCacheLifetime(10 * minute, false), learn(Triple(7, 5, 0)))
        assertNull(learn(Triple(7, 2, 0)))
        // The open-ended last bucket survives up to its own lower bound.
        assertEquals(LearnedPromptCacheLifetime(1440 * minute, false), learn(Triple(2000, 3, 0)))
    }

    @Test fun `lets recent samples outweigh old ones`() {
        // 40 old hits, then the account starts missing.
        assertEquals(LearnedPromptCacheLifetime(10 * minute, true), learn(Triple(15, 40, 0), Triple(15, 0, 30)))
    }

    @Test fun `applies a floor only when it lengthens the estimate, and a bound always`() {
        val claude = resolve("claude-agent", "opus")
        assertSame(claude, withLearnedPromptCacheLifetime(claude, LearnedPromptCacheLifetime(10 * minute, false)))
        val floor = withLearnedPromptCacheLifetime(claude, LearnedPromptCacheLifetime(90 * minute, false))
        assertEquals(hour, floor.ttlMs)
        assertEquals(90 * minute, floor.likelyMs)
        assertTrue(floor.description.endsWith(" In your recent sessions it lasted at least 90 min."))
        // Extra usage or API billing: keep-alives follow the shorter, observed lifetime.
        val bound = withLearnedPromptCacheLifetime(claude, LearnedPromptCacheLifetime(5 * minute, true))
        assertEquals(5 * minute, bound.ttlMs)
        assertEquals(5 * minute, bound.likelyMs)
        val gpt = withLearnedPromptCacheLifetime(resolve("openai", "gpt-6.2-sol"), LearnedPromptCacheLifetime(6 * hour, true))
        assertEquals(30 * minute, gpt.ttlMs)
        assertEquals(6 * hour, gpt.likelyMs)
        assertTrue(gpt.description.endsWith(" In your recent sessions it usually lasted about 6 h."))
    }

    @Test fun `prefers the model, then the provider`() {
        val learned = mapOf(
            "openai/gpt-6.2-sol" to LearnedPromptCacheLifetime(2 * hour, true),
            "openai/*" to LearnedPromptCacheLifetime(hour, true),
        )
        fun likely(modelID: String) = promptCacheLifetimeFor(null, PromptCacheModel("openai", modelID), learned).likelyMs
        assertEquals(2 * hour, likely("gpt-6.2-sol"))
        assertEquals(hour, likely("gpt-6.1-sol"))
    }

    @Test fun `samples only idle gaps of large prompts`() {
        fun sample(gapMs: Long, read: Double, input: Double = 100.0) = promptCacheSample(0.0, gapMs.toDouble(), input, read, 0.0)
        assertNull(sample(4 * minute, 50_000.0))
        assertNull(sample(6 * minute, 1_000.0))
        assertEquals(PromptCacheSample(6.0 * minute, true), sample(6 * minute, 50_000.0))
        assertEquals(PromptCacheSample(6.0 * minute, false), sample(6 * minute, 0.0, 50_000.0))
    }

    @Test fun `validates persisted histograms`() {
        assertTrue(isPromptCacheHistogram(promptCacheHistogramJson(emptyPromptCacheHistogram())))
        assertFalse(isPromptCacheHistogram(Json.parse("[[1,2]]")))
        assertFalse(isPromptCacheHistogram(Json.parse(List(12) { "[-1,0]" }.joinToString(",", "[", "]"))))
        assertFalse(isPromptCacheHistogram(Json.parse(List(12) { "[1.5,0]" }.joinToString(",", "[", "]"))))
        assertFalse(isPromptCacheHistogram(Json.parse(List(12) { "[1,0,0]" }.joinToString(",", "[", "]"))))
        assertEquals(Json.parse(List(12) { "[0,0]" }.joinToString(",", "[", "]")), promptCacheHistogramJson(emptyPromptCacheHistogram()))
    }

    @Test fun `keys match the webview`() {
        assertEquals("openai/gpt-6.2-sol", promptCacheModelKey(PromptCacheModel("openai", "gpt-6.2-sol")))
        assertEquals("openai/*", promptCacheProviderKey("openai"))
    }
}
