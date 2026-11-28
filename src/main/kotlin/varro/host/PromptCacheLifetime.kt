package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.asObjectOrNull
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.str
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Host-side port of upstream `src/shared/prompt-cache-lifetime.ts`. The webview keeps its own
 * vendored TypeScript copy; this file carries only what the host uses: lifetime resolution for
 * keep-alive scheduling and the learned-lifetime histograms. `promptCacheHistorySamples` stays in
 * the webview, which sends its results as `promptCache/samples`. Constants, buckets, regexes and
 * model/provider tables must stay identical to upstream.
 */

/**
 * How long a provider keeps an idle prompt cache entry, from each provider's documented behavior.
 * Claude and OpenAI refresh an entry whenever a request reads it, and both count its lifetime from
 * when that request was sent, not from when its response finished.
 */
data class PromptCacheLifetime(
    /**
     * How long the entry is sure to live after the request that last read or wrote it. Keep-alives
     * follow this, since one that arrives late pays to rebuild the cache.
     */
    val ttlMs: Long,
    /**
     * How long the entry usually lives when the provider often keeps it past `ttlMs`. Expiry
     * warnings wait for this, so a cache that is probably still warm is not reported as gone.
     */
    val likelyMs: Long? = null,
    /** `configured`: the model's `cache.ttl`; `provider`: the provider's documented default; or `fallback`. */
    val source: String,
    /** One sentence for tooltips explaining where `ttlMs` comes from. */
    val description: String,
    /**
     * One OpenCode step can hold several provider requests, as when Claude Code runs its own loop,
     * so the step's dispatch time is too early. Only the end of its response bounds the last request.
     */
    val multiRequestSteps: Boolean = false,
)

data class PromptCacheModel(val providerID: String, val modelID: String)

/**
 * How long caches have lasted: `ms` is the longest idle gap they usually survived. `bounded` means
 * longer gaps mostly missed, so `ms` is also when they usually expire; otherwise it is only a floor.
 */
data class LearnedPromptCacheLifetime(val ms: Long, val bounded: Boolean)

/** Whether a request after an idle gap read the cache. Shorter gaps say nothing about expiry. */
data class PromptCacheSample(val gapMs: Double, val hit: Boolean)

/** `[hits, misses]` for each idle-gap bucket. */
typealias PromptCacheHistogram = List<Pair<Long, Long>>

/** Claude Code through OpenCode's Claude Agent provider, which lists models by alias such as `sonnet`. */
private const val CLAUDE_AGENT_PROVIDER = "claude-agent"
private const val CLAUDE_AGENT_PACKAGE = "@opencode/claude-agent"

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** The most common default when neither the provider nor the model says otherwise. */
const val FALLBACK_PROMPT_CACHE_TTL_MS = 5 * MINUTE

private val TTL_UNITS = mapOf("ms" to 1L, "s" to 1_000L, "m" to MINUTE, "h" to HOUR, "d" to 24 * HOUR)
private val TTL_PATTERN = Regex("^(\\d+)(ms|s|m|h|d)$")
/** Largest integer a JavaScript number holds exactly; larger TTLs are not meaningful here. */
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

/** Parses OpenCode's `cache.ttl` duration format, such as `"5m"`, `"1h"` or `"24h"`. */
fun parsePromptCacheTtl(value: String?): Long? {
    val match = value?.let(TTL_PATTERN::find) ?: return null
    val ms = match.groupValues[1].toDouble() * TTL_UNITS.getValue(match.groupValues[2])
    return if (ms > 0 && ms <= MAX_SAFE_INTEGER) ms.toLong() else null
}

private fun formatTtl(ms: Long): String =
    if (ms >= HOUR && ms % HOUR == 0L) "${ms / HOUR} h" else "${(ms.toDouble() / MINUTE).roundToLong()} min"

// Bedrock releases that cache for five minutes only; OpenCode drops a "1h" ttl for them.
private val BEDROCK_CLAUDE_5M = listOf(
    "anthropic.claude-3-5-sonnet-20241022-v2:0",
    "anthropic.claude-3-5-haiku-20241022-v1:0",
    "anthropic.claude-3-7-sonnet-20250219-v1:0",
    "anthropic.claude-sonnet-4-20250514-v1:0",
    "anthropic.claude-opus-4-20250514-v1:0",
    "anthropic.claude-opus-4-1-20250805-v1:0",
)

// Hosts that serve OpenAI models with OpenAI's own prompt cache. Other hosts of GPT-named models,
// such as GitHub Copilot, manage caching themselves.
private val OPENAI_PROVIDERS = setOf("openai", "azure", "opencode", "openrouter", "amazon-bedrock")
private val OPENAI_PACKAGES = setOf("@ai-sdk/openai", "@ai-sdk/azure")
// Models before GPT-5.6 that support `prompt_cache_retention: "24h"`. GPT-5.5 supports only that.
private val OPENAI_EXTENDED_RETENTION = setOf(
    "gpt-5.5",
    "gpt-5.5-pro",
    "gpt-5.4",
    "gpt-5.2",
    "gpt-5.1-codex-max",
    "gpt-5.1",
    "gpt-5.1-codex",
    "gpt-5.1-codex-mini",
    "gpt-5.1-chat-latest",
    "gpt-5",
    "gpt-5-codex",
    "gpt-4.1",
)

private val CLAUDE_MODEL = Regex("(?:^|[/.:])claude-")
private val OPENAI_MODEL = Regex("^(gpt-\\d|o\\d)")
private val OPENAI_VERSION = Regex("^gpt-(\\d+)(?:\\.(\\d+))?")
private val DATED_SUFFIX = Regex("-\\d{4}-\\d{2}-\\d{2}$")
private val AISDK_PREFIX = Regex("^aisdk:")
private val BEDROCK_OPENAI_PREFIX = Regex("^(?:[a-z]+\\.)?openai\\.")

private fun claudeLifetime(modelID: String, ttlMs: Long?): PromptCacheLifetime {
    if (ttlMs != null && !(ttlMs > 5 * MINUTE && BEDROCK_CLAUDE_5M.any { modelID.contains(it) }))
        return PromptCacheLifetime(
            ttlMs = ttlMs,
            source = "configured",
            description = "Claude keeps the cache ${formatTtl(ttlMs)} (cache.ttl) from the start of each request that reads it.",
        )
    return PromptCacheLifetime(
        ttlMs = 5 * MINUTE,
        source = "provider",
        description = "Claude keeps the cache 5 min from the start of each request that reads it.",
    )
}

private fun openAILifetime(baseID: String, ttl: String?, host: String): PromptCacheLifetime {
    val version = OPENAI_VERSION.find(baseID)
    val major = version?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
    val minor = version?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
    // Bedrock documents only the GPT-5.6+ minimum, and accepts no lifetime besides it.
    val bedrock = host == "amazon-bedrock"
    if (major > 5 || (major == 5.0 && minor >= 6)) {
        // `prompt_cache_options.ttl` takes "30m" (the default) or "12h"; both are minimum lifetimes.
        if (ttl == "12h" && !bedrock)
            return PromptCacheLifetime(
                ttlMs = 12 * HOUR,
                source = "configured",
                description = "OpenAI keeps the cache at least 12 h (cache.ttl) after each request that reads it.",
            )
        // Entries often live much longer than the minimum: in observed GPT-6 sessions, a request after
        // 1 to 3 h idle still read the cache about three times in four, after 3 to 6 h less than half
        // the time, and after 6 h almost never.
        return PromptCacheLifetime(
            ttlMs = 30 * MINUTE,
            likelyMs = 3 * HOUR,
            source = "provider",
            description = "OpenAI keeps the cache at least 30 min after each request that reads it, and often up to about 3 h.",
        )
    }
    val undated = baseID.replace(DATED_SUFFIX, "")
    if (undated in OPENAI_EXTENDED_RETENTION && !bedrock)
        // GPT-5.5 always uses 24-hour retention. Earlier models default to it unless the organization
        // uses Zero Data Retention, which keeps the in-memory default instead.
        return PromptCacheLifetime(
            ttlMs = 30 * MINUTE,
            source = if (ttl == "24h") "configured" else "provider",
            description = if (undated.startsWith("gpt-5.5"))
                "OpenAI's 24-hour retention usually keeps the cache about 30 min after each request, sometimes up to 24 h."
            else
                "OpenAI's 24-hour retention usually keeps the cache about 30 min after each request, sometimes up to 24 h. Zero Data Retention organizations get about 5 to 10 min.",
        )
    return PromptCacheLifetime(
        ttlMs = 5 * MINUTE,
        likelyMs = 10 * MINUTE,
        source = "provider",
        description = "OpenAI's in-memory cache usually lasts 5 to 10 min after each request, at most 1 h.",
    )
}

/**
 * OpenRouter passes a proprietary vendor's requests to that vendor's own cache, under its usual
 * rules. Open-weight models can be served by any host there, each with its own cache.
 */
private val OPENROUTER_VENDORS = mapOf("google" to "google", "x-ai" to "xai")

private fun hostFor(providerID: String, id: String): String {
    if (providerID != "openrouter") return providerID
    // JavaScript `id.slice(0, id.indexOf('/'))`: without a slash, the slice drops the last character.
    val slash = id.indexOf('/')
    val vendor = if (slash >= 0) id.substring(0, slash) else id.dropLast(1)
    return OPENROUTER_VENDORS[vendor] ?: providerID
}

private val GOOGLE_HOST = Regex("^(google|google-vertex)$")
private val ZAI_HOST = Regex("^(zai|zai-coding-plan|zhipuai|zhipuai-coding-plan)$")
private val KIMI_HOST = Regex("^(moonshotai|moonshotai-cn|kimi-for-coding|kimi-code-plan-cn|kimi-code-plan-global)$")
private val ALIBABA_HOST = Regex("^alibaba(-coding-plan|-token-plan)?(-cn)?$")

/**
 * Documented or measured lifetimes for other popular providers. Most cache automatically without
 * promising a lifetime, so `ttlMs` stays at what keep-alives can count on and `likelyMs` follows
 * what the provider describes or independent measurements found.
 */
private fun otherProviderLifetime(host: String, id: String, ttl: String?): PromptCacheLifetime? {
    // Gemini's implicit cache has no fixed lifetime. Google Cloud keeps prefixes at most 24 h and
    // its Argon guide gives a 15 to 20 min window; measurements found 5 to 15 min under load.
    if (GOOGLE_HOST.containsMatchIn(host) && id.contains("gemini"))
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            likelyMs = 15 * MINUTE,
            source = "provider",
            description = "Gemini's implicit cache has no fixed lifetime; it usually lasts 5 to 15 min after each request, at most 24 h.",
        )
    // A disk cache, cleared "usually within a few hours to a few days" once unused.
    if (host == "deepseek")
        return PromptCacheLifetime(
            ttlMs = HOUR,
            likelyMs = 6 * HOUR,
            source = "provider",
            description = "DeepSeek's disk cache is usually cleared a few hours to a few days after its last use.",
        )
    // No published lifetime: entries "can be evicted at any time". Measured at about 30 min.
    if (host == "xai")
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            likelyMs = 30 * MINUTE,
            source = "provider",
            description = "xAI publishes no cache lifetime and may evict entries at any time; it usually lasts about 30 min.",
        )
    // No published lifetime. Measured on the coding plan: every request still hit after 60 min idle.
    if (ZAI_HOST.containsMatchIn(host))
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            likelyMs = HOUR,
            source = "provider",
            description = "Z.ai publishes no cache lifetime; it usually lasts at least 1 h after each request.",
        )
    // 5 min, renewed by each hit; `prompt_cache_options.ttl` (OpenCode's cache.ttl) selects 1 h.
    if (KIMI_HOST.containsMatchIn(host))
        return if (ttl == "1h")
            PromptCacheLifetime(
                ttlMs = HOUR,
                source = "configured",
                description = "Kimi keeps the cache 1 h (cache.ttl) after each request that reads it.",
            )
        else
            PromptCacheLifetime(
                ttlMs = 5 * MINUTE,
                source = "provider",
                description = "Kimi keeps the cache 5 min after each request that reads it, or 1 h with cache.ttl \"1h\".",
            )
    // OpenCode marks Model Studio requests for its explicit cache: 5 min, renewed by each hit.
    // Qwen4 also accepts a 1 h lifetime.
    if (ALIBABA_HOST.containsMatchIn(host))
        return if (ttl == "1h" && id.contains("qwen4"))
            PromptCacheLifetime(
                ttlMs = HOUR,
                source = "configured",
                description = "Qwen keeps the cache 1 h (cache.ttl) after each request that reads it.",
            )
        else
            PromptCacheLifetime(
                ttlMs = 5 * MINUTE,
                source = "provider",
                description = "Model Studio keeps the cache 5 min after each request that reads it.",
            )
    // "All cached data automatically expires after 2 hours without use"; hits are best effort.
    if (host == "groq")
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            likelyMs = 2 * HOUR,
            source = "provider",
            description = "Groq's cache expires 2 h after its last use, and hits are best effort.",
        )
    // "Usually stay in the cache for at least several minutes", up to several hours under low load.
    if (host == "fireworks-ai")
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            likelyMs = HOUR,
            source = "provider",
            description = "Fireworks usually keeps the cache several minutes, and up to several hours under low load.",
        )
    // In memory by default; `prompt_cache_retention: "24h"` is only a hint the server may ignore.
    if (host == "meta")
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            likelyMs = if (ttl == "24h") HOUR else 15 * MINUTE,
            source = if (ttl == "24h") "configured" else "provider",
            description = "Meta keeps the cache in memory until load or inactivity evicts it; the 24 h retention setting is only a hint.",
        )
    // Nova caches for 5 min only and rejects a longer ttl.
    if (host == "amazon-bedrock" && id.contains("amazon.nova"))
        return PromptCacheLifetime(
            ttlMs = 5 * MINUTE,
            source = "provider",
            description = "Nova on Bedrock keeps the cache 5 min after each request that reads it.",
        )
    // Undocumented: entries reportedly drop after a few minutes of quiet.
    if (host == "mistral")
        return PromptCacheLifetime(
            ttlMs = 3 * MINUTE,
            likelyMs = 5 * MINUTE,
            source = "provider",
            description = "Mistral publishes no cache lifetime; it reportedly lasts only a few minutes.",
        )
    return null
}

/**
 * The expected cache lifetime of a model. `ttl` is the effective `cache.ttl` setting, which OpenCode
 * sends to the provider as given; `npm`, `apiModelID` and `priced` (the catalog lists an input
 * price) come from the provider catalog when known.
 */
fun resolvePromptCacheLifetime(
    model: PromptCacheModel,
    ttl: String? = null,
    npm: String? = null,
    apiModelID: String? = null,
    priced: Boolean? = null,
): PromptCacheLifetime {
    val id = (apiModelID?.takeIf { it.isNotEmpty() } ?: model.modelID).lowercase()
    val ttlMs = parsePromptCacheTtl(ttl)
    // OpenCode prefixes AI SDK packages it loads itself, such as `aisdk:@ai-sdk/openai`.
    val pkg = npm?.replaceFirst(AISDK_PREFIX, "") ?: ""
    // Claude Code sets its own cache markers and ignores OpenCode's cache.ttl. Claude subscriptions
    // get a 1 h cache unless they run on extra usage; API billing keeps Claude's 5 min default.
    // Claude Agent prices models only for API billing, so an unpriced model means a subscription.
    if (model.providerID == CLAUDE_AGENT_PROVIDER || pkg == CLAUDE_AGENT_PACKAGE)
        return if (priced == true)
            PromptCacheLifetime(
                ttlMs = 5 * MINUTE,
                source = "provider",
                description = "Claude Code on API billing keeps the cache 5 min after its last request.",
                multiRequestSteps = true,
            )
        else
            PromptCacheLifetime(
                ttlMs = HOUR,
                source = "provider",
                description = "Claude Code on a Claude subscription keeps the cache 1 h after its last request, 5 min while on extra usage.",
                multiRequestSteps = true,
            )
    // Model IDs identify Claude on every host: Anthropic, Bedrock, Vertex, OpenRouter and gateways.
    // Packages do not, since other providers expose Anthropic-compatible APIs for their own models.
    if (CLAUDE_MODEL.containsMatchIn(id)) return claudeLifetime(id, ttlMs)
    // Bedrock names OpenAI models `openai.gpt-…`, with an optional regional profile prefix.
    val baseID = id.substring(id.lastIndexOf('/') + 1).replaceFirst(BEDROCK_OPENAI_PREFIX, "")
    if ((model.providerID in OPENAI_PROVIDERS || pkg in OPENAI_PACKAGES) &&
        OPENAI_MODEL.containsMatchIn(baseID) && !baseID.startsWith("gpt-oss")
    )
        return openAILifetime(baseID, ttl, model.providerID)
    otherProviderLifetime(hostFor(model.providerID, id), id, ttl)?.let { return it }
    if (ttlMs != null)
        return PromptCacheLifetime(
            ttlMs = ttlMs,
            source = "configured",
            description = "The model's cache.ttl keeps the cache ${formatTtl(ttlMs)} after each request.",
        )
    // Most measured caches outlive 5 min, so the estimate only calls one likely expired after 15.
    return PromptCacheLifetime(
        ttlMs = FALLBACK_PROMPT_CACHE_TTL_MS,
        likelyMs = 15 * MINUTE,
        source = "fallback",
        description = "This provider publishes no cache lifetime; most keep the cache 5 to 30 min after each request.",
    )
}

/**
 * When a completed response last used the cache. Providers count the lifetime from when a request
 * is sent, so a long response uses up part of it. A V2 step or a single-step V1 response is one
 * request, sent at `created`. A V1 response with several steps, or a step that holds several
 * requests such as Claude Code's own loop, sent its last request at an unknown time; its
 * completion is the closest known bound.
 */
fun promptCacheUsedAt(created: Long, completed: Long, steps: Int, lifetime: PromptCacheLifetime): Long =
    if (steps > 1 || lifetime.multiRequestSteps) completed else created

fun promptCacheModelKey(model: PromptCacheModel): String = "${model.providerID}/${model.modelID}"

fun promptCacheProviderKey(providerID: String): String = "$providerID/*"

/**
 * Applies how long this user's caches have actually lasted. Accounts differ, by plan, data
 * retention or extra usage, so evidence replaces the usual-lifetime guess. It only shortens the
 * lifetime keep-alives follow: one that arrives late pays to rebuild the cache.
 */
fun withLearnedPromptCacheLifetime(
    lifetime: PromptCacheLifetime,
    learned: LearnedPromptCacheLifetime?,
): PromptCacheLifetime {
    if (learned == null) return lifetime
    val likelyMs = lifetime.likelyMs ?: lifetime.ttlMs
    if (!learned.bounded) {
        // Survived gaps raise the estimate but say nothing about when caches expire.
        if (learned.ms <= likelyMs) return lifetime
        return lifetime.copy(
            likelyMs = learned.ms,
            description = "${lifetime.description} In your recent sessions it lasted at least ${formatTtl(learned.ms)}.",
        )
    }
    return lifetime.copy(
        ttlMs = min(lifetime.ttlMs, learned.ms),
        likelyMs = learned.ms,
        description = "${lifetime.description} In your recent sessions it usually lasted about ${formatTtl(learned.ms)}.",
    )
}

/** JavaScript `a ?? b` over JSON members: JSON `null` and absent both fall through. */
private fun JsonElement?.orIfNullish(other: () -> JsonElement?): JsonElement? =
    if (this == null || isJsonNull) other() else this

/**
 * Resolves a model's lifetime from the V1-shaped provider catalog (`/config/providers`'
 * `providers` array). Model settings override provider settings, as in OpenCode's runtime merge.
 */
fun promptCacheLifetimeFor(
    providers: JsonArray?,
    model: PromptCacheModel,
    learned: Map<String, LearnedPromptCacheLifetime>? = null,
): PromptCacheLifetime {
    val provider: JsonObject? = providers
        ?.firstOrNull { it.asObjectOrNull().str("id") == model.providerID }
        .asObjectOrNull()
    val info = provider.obj("models").obj(model.modelID)
    val ttl = info.obj("options").obj("cache")?.get("ttl")
        .orIfNullish { provider.obj("options").obj("cache")?.get("ttl") }
    val lifetime = resolvePromptCacheLifetime(
        model,
        ttl = if (ttl != null && ttl.isJsonPrimitive && ttl.asJsonPrimitive.isString) ttl.asString else null,
        npm = info.obj("api").str("npm"),
        apiModelID = info.obj("api").str("id"),
        priced = if (info != null) (info.obj("cost").num("input") ?: 0.0) > 0 else null,
    )
    return withLearnedPromptCacheLifetime(
        lifetime,
        learned?.get(promptCacheModelKey(model)) ?: learned?.get(promptCacheProviderKey(model.providerID)),
    )
}

private const val SAMPLE_MIN_GAP_MS = 5 * MINUTE
// Below this, a prompt may be under the provider's minimum cacheable size and never cache at all.
private const val SAMPLE_MIN_TOKENS = 8_192

/**
 * The evidence from a request sent at `sentAt` after the cache was last used at `previousUsedAt`.
 * A request that read most of its input from the cache found it alive.
 */
fun promptCacheSample(previousUsedAt: Double, sentAt: Double, input: Double, cacheRead: Double, cacheWrite: Double): PromptCacheSample? {
    val gapMs = sentAt - previousUsedAt
    val total = input + cacheRead + cacheWrite
    if (gapMs < SAMPLE_MIN_GAP_MS || total < SAMPLE_MIN_TOKENS) return null
    return PromptCacheSample(gapMs, cacheRead * 2 > total)
}

/** Lower bounds of the idle-gap buckets; the last one is open-ended. */
private val GAP_BUCKETS_MS = listOf(5, 10, 20, 30, 45, 60, 90, 120, 180, 360, 720, 1440).map { it * MINUTE }
/** Buckets pool samples until they have this many before they count as evidence either way. */
private const val MIN_BUCKET_SAMPLES = 3
/** Past this many samples a bucket halves its counts, so recent behavior outweighs old. */
private const val MAX_BUCKET_SAMPLES = 40

fun emptyPromptCacheHistogram(): PromptCacheHistogram = GAP_BUCKETS_MS.map { 0L to 0L }

private fun sampleCount(value: JsonElement?): Long? {
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    val number = value.asDouble
    return if (number.isFinite() && number == Math.floor(number) && number >= 0 && number <= MAX_SAFE_INTEGER) number.toLong() else null
}

/** Reads a persisted histogram, or `null` when it is not exactly upstream's `isPromptCacheHistogram` shape. */
fun parsePromptCacheHistogram(value: JsonElement?): PromptCacheHistogram? {
    if (value == null || !value.isJsonArray) return null
    val array = value.asJsonArray
    if (array.size() != GAP_BUCKETS_MS.size) return null
    return array.map { bucket ->
        if (!bucket.isJsonArray || bucket.asJsonArray.size() != 2) return null
        val hits = sampleCount(bucket.asJsonArray[0]) ?: return null
        val misses = sampleCount(bucket.asJsonArray[1]) ?: return null
        hits to misses
    }
}

fun isPromptCacheHistogram(value: JsonElement?): Boolean = parsePromptCacheHistogram(value) != null

fun promptCacheHistogramJson(histogram: PromptCacheHistogram): JsonArray = JsonArray().apply {
    histogram.forEach { (hits, misses) -> add(JsonArray().apply { add(hits); add(misses) }) }
}

fun addPromptCacheSample(histogram: PromptCacheHistogram, sample: PromptCacheSample): PromptCacheHistogram {
    var index = GAP_BUCKETS_MS.size - 1
    while (index > 0 && sample.gapMs < GAP_BUCKETS_MS[index]) index -= 1
    return histogram.mapIndexed { bucket, (hits, misses) ->
        if (bucket != index) return@mapIndexed hits to misses
        val next = if (sample.hit) (hits + 1) to misses else hits to (misses + 1)
        if (next.first + next.second > MAX_BUCKET_SAMPLES)
            Math.round(next.first / 2.0) to Math.round(next.second / 2.0)
        else next
    }
}

/**
 * How long caches usually last by the evidence: to the end of the longest idle gap that still found
 * them alive at least half the time, before the first gap where most requests missed. Neighboring
 * gaps with too few samples are pooled until they have enough. `null` until there is evidence.
 */
fun learnPromptCacheLifetime(histogram: PromptCacheHistogram): LearnedPromptCacheLifetime? {
    var survived: Long? = null
    var pooledLower: Long? = null
    var pooledHits = 0L
    var pooledMisses = 0L
    for ((index, bucket) in histogram.withIndex()) {
        val (hits, misses) = bucket
        if (hits + misses == 0L) continue
        val lower = pooledLower ?: GAP_BUCKETS_MS[index]
        pooledLower = lower
        pooledHits += hits
        pooledMisses += misses
        if (pooledHits + pooledMisses < MIN_BUCKET_SAMPLES) continue
        if (pooledMisses > pooledHits) return LearnedPromptCacheLifetime(survived ?: lower, true)
        survived = GAP_BUCKETS_MS.getOrNull(index + 1) ?: GAP_BUCKETS_MS[index]
        pooledLower = null
        pooledHits = 0
        pooledMisses = 0
    }
    return survived?.let { LearnedPromptCacheLifetime(it, false) }
}
