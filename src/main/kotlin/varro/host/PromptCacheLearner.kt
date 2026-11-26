package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import varro.protocol.asObjectOrNull
import varro.protocol.obj

/** One request after an idle gap, sent at `at`. Mirrors the webview's `PromptCacheHistorySample`. */
data class PromptCacheObservation(
    val providerID: String,
    val modelID: String,
    val at: Double,
    val gapMs: Double,
    val hit: Boolean,
)

/**
 * Learns how long each model's prompt cache actually lasts for this user, from whether requests
 * after idle gaps read it. Port of upstream `src/extension/prompt-cache-learning.ts`, which keeps it
 * in global state, since accounts and plans span workspaces: back [Storage] with an
 * application-level store. Stored as `{ histograms: { [model]: [[hits, misses], ...] }, watermarks: { [session]: at } }`.
 */
class PromptCacheLearner(private val storage: Storage = Storage.inMemory()) {
    interface Storage {
        fun load(): JsonElement?
        /** Called under the learner's lock; must be quick and must not call back in. */
        fun save(value: JsonElement)

        companion object {
            fun inMemory(initial: JsonElement? = null): Storage = object : Storage {
                @Volatile private var value: JsonElement? = initial
                override fun load() = value
                override fun save(value: JsonElement) { this.value = value }
            }
        }
    }

    private var histograms = LinkedHashMap<String, PromptCacheHistogram>()
    /** The latest observation recorded per session, so a reloaded history does not count twice. */
    private var watermarks = LinkedHashMap<String, Double>()
    private var learned: Map<String, LearnedPromptCacheLifetime>? = null

    init {
        val value = storage.load().asObjectOrNull()
        value.obj("histograms")?.entrySet()?.forEach { (key, histogram) ->
            parsePromptCacheHistogram(histogram)?.let { histograms[key] = it }
        }
        value.obj("watermarks")?.entrySet()?.forEach { (sessionID, at) ->
            if (at.isJsonPrimitive && at.asJsonPrimitive.isNumber && at.asDouble.isFinite()) watermarks[sessionID] = at.asDouble
        }
    }

    /**
     * Records a session's observations. Only those after the latest one already recorded for the
     * session count, so live events and a history sent again later are each counted once.
     * Returns whether anything was recorded.
     */
    @Synchronized fun record(sessionID: String, observations: List<PromptCacheObservation>): Boolean {
        var watermark = watermarks[sessionID] ?: Double.NEGATIVE_INFINITY
        val next = LinkedHashMap(histograms)
        var changed = false
        for (observation in observations.sortedBy { it.at }) {
            if (observation.at <= watermark) continue
            watermark = observation.at
            val key = promptCacheModelKey(PromptCacheModel(observation.providerID, observation.modelID))
            // Re-inserting keeps the most recently used models when the map is trimmed.
            val histogram = next.remove(key) ?: emptyPromptCacheHistogram()
            next[key] = addPromptCacheSample(histogram, PromptCacheSample(observation.gapMs, observation.hit))
            changed = true
        }
        if (!changed) return false
        val nextWatermarks = LinkedHashMap(watermarks)
        nextWatermarks.remove(sessionID)
        nextWatermarks[sessionID] = watermark
        histograms = bounded(next, MAX_MODELS)
        watermarks = bounded(nextWatermarks, MAX_SESSIONS)
        learned = null
        storage.save(stored())
        return true
    }

    /** Learned lifetimes per model, and per provider (keyed by [promptCacheProviderKey]) from all of its models together. */
    @Synchronized fun snapshot(): Map<String, LearnedPromptCacheLifetime> {
        learned?.let { return it }
        val result = LinkedHashMap<String, LearnedPromptCacheLifetime>()
        val providers = LinkedHashMap<String, PromptCacheHistogram>()
        for ((key, histogram) in histograms) {
            learnPromptCacheLifetime(histogram)?.let { result[key] = it }
            // Upstream's `key.slice(0, key.indexOf('/'))`, including its behavior without a slash.
            val slash = key.indexOf('/')
            val providerKey = promptCacheProviderKey(if (slash >= 0) key.substring(0, slash) else key.dropLast(1))
            val total = providers[providerKey] ?: emptyPromptCacheHistogram()
            providers[providerKey] = total.mapIndexed { index, (hits, misses) ->
                (hits + (histogram.getOrNull(index)?.first ?: 0)) to (misses + (histogram.getOrNull(index)?.second ?: 0))
            }
        }
        for ((key, histogram) in providers) learnPromptCacheLifetime(histogram)?.let { result[key] = it }
        return result.also { learned = it }
    }

    private fun stored(): JsonObject = JsonObject().apply {
        add("histograms", JsonObject().apply { histograms.forEach { (key, value) -> add(key, promptCacheHistogramJson(value)) } })
        add("watermarks", JsonObject().apply { watermarks.forEach { (key, value) -> add(key, number(value)) } })
    }

    private companion object {
        const val MAX_MODELS = 100
        const val MAX_SESSIONS = 500

        fun <V> bounded(map: LinkedHashMap<String, V>, limit: Int): LinkedHashMap<String, V> =
            if (map.size <= limit) map else LinkedHashMap<String, V>().apply {
                map.entries.drop(map.size - limit).forEach { (key, value) -> put(key, value) }
            }

        /** Whole millisecond timestamps are stored as integers, like JavaScript's JSON. */
        fun number(value: Double): JsonPrimitive =
            if (value == Math.floor(value) && Math.abs(value) <= 9_007_199_254_740_991.0) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    }
}
