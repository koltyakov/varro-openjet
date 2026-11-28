package varro.host

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.arr
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.text

/** Learns cache lifetimes from real model steps and loaded history, without sending keep-alives. */
internal class PromptCacheObserver(
    private val learner: PromptCacheLearner,
    private val readOpenCodeWarming: () -> Boolean?,
    private val post: (JsonObject) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Request(val sentAt: Long, val model: PromptCacheModel?)
    private data class CacheUse(val at: Long, val model: PromptCacheModel)
    private data class WarmingReading(val enabled: Boolean, val readAt: Long)

    private val requests = LinkedHashMap<String, Request>()
    private val cacheUses = LinkedHashMap<String, CacheUse>()
    private var warming: WarmingReading? = null
    private var disposed = false

    /** Reads effective config off the EDT and publishes even if that read is unavailable. */
    fun sync() {
        val started = now()
        val refresh = synchronized(this) {
            !disposed && warming?.let { started - it.readAt < 60_000 } != true
        }
        val enabled = if (refresh) {
            try {
                readOpenCodeWarming()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                null // Keep the last known setting.
            }
        } else null
        synchronized(this) {
            if (enabled != null && (warming?.readAt ?: Long.MIN_VALUE) <= started) {
                if (warming?.enabled != enabled) cacheUses.clear()
                warming = WarmingReading(enabled, started)
            }
            publish()
        }
    }

    @Synchronized
    fun recordHistory(sessionId: String, observations: List<PromptCacheObservation>) {
        // OpenCode can renew its cache without leaving history or events.
        if (!disposed && warming?.enabled != true && learner.record(sessionId, observations)) publish()
    }

    @Synchronized
    fun handleServerEvent(type: String, properties: JsonObject?) {
        if (disposed) return
        val sessionId = properties.text("sessionID") ?: return
        when {
            type == "session.next.step.started" -> {
                val model = properties.obj("model")
                val providerId = model.text("providerID")
                val modelId = model.text("modelID") ?: model.text("id")
                val current = now()
                val sentAt = properties.num("timestamp")?.takeIf { it.isFinite() }?.toLong() ?: current
                requests.putRecent(sessionId, Request(minOf(sentAt, current),
                    if (providerId != null && modelId != null) PromptCacheModel(providerId, modelId) else null))
            }
            type == "session.next.step.ended" -> learnFromStep(sessionId, properties.obj("tokens"))
            type.startsWith("session.next.compaction.") -> cacheUses.remove(sessionId)
        }
    }

    private fun learnFromStep(sessionId: String, tokens: JsonObject?) {
        val request = requests.remove(sessionId) ?: return
        val model = request.model ?: return
        val input = tokens.num("input") ?: return
        val read = tokens.obj("cache").num("read") ?: return
        val write = tokens.obj("cache").num("write") ?: return
        val previous = cacheUses[sessionId]
        if (previous?.model == model && warming?.enabled != true) {
            val sample = promptCacheSample(previous.at.toDouble(), request.sentAt.toDouble(), input, read, write)
            if (sample != null && learner.record(sessionId, listOf(
                    PromptCacheObservation(model.providerID, model.modelID, request.sentAt.toDouble(), sample.gapMs, sample.hit),
                ))) publish()
        }
        val usedAt = promptCacheUsedAt(request.sentAt, now(), 1, resolvePromptCacheLifetime(model))
        cacheUses.putRecent(sessionId, CacheUse(usedAt, model))
    }

    private fun publish() {
        if (disposed) return
        val learned = JsonObject()
        for ((key, lifetime) in learner.snapshot()) {
            if (key.length <= 1024 && lifetime.ms > 0) {
                learned.add(key, Json.obj("ms" to lifetime.ms, "bounded" to lifetime.bounded))
            }
        }
        post(Json.obj("learned" to learned))
    }

    @Synchronized
    fun dispose() {
        disposed = true
        requests.clear()
        cacheUses.clear()
    }

    private fun <V> LinkedHashMap<String, V>.putRecent(sessionId: String, value: V) {
        remove(sessionId)
        put(sessionId, value)
        if (size > 200) remove(keys.first())
    }

    companion object {
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]+$")

        fun parseHistorySamples(payload: JsonObject?): Pair<String, List<PromptCacheObservation>>? {
            val sessionId = payload.text("sessionId")?.takeIf { it.length in 1..512 && SESSION_ID.matches(it) } ?: return null
            val samples = payload.arr("samples")?.takeIf { it.size() <= 500 } ?: return null
            val observations = samples.map { element ->
                val sample = element.asObjectOrNull() ?: return null
                val providerId = sample.text("providerID")?.takeIf { it.length in 1..256 } ?: return null
                val modelId = sample.text("modelID")?.takeIf { it.length in 1..256 } ?: return null
                val at = sample.num("at")?.takeIf { it.isFinite() } ?: return null
                val gap = sample.num("gapMs")?.takeIf { it.isFinite() && it > 0 } ?: return null
                val hit = sample.bool("hit") ?: return null
                PromptCacheObservation(providerId, modelId, at, gap, hit)
            }
            return sessionId to observations
        }
    }
}
