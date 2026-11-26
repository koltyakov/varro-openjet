package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import varro.protocol.Json
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.str
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Keeps chosen sessions' provider prompt caches alive while they sit idle, by sending OpenCode's
 * transient `/generate` keep-alive just before the cache would expire. "All sessions" mode is
 * delegated to OpenCode's own `warming` config instead, so it also covers the TUI and closed windows.
 *
 * Port of upstream `src/extension/prompt-cache-warming.ts`. Upstream's promise chains become blocking
 * calls: [sync] and [applySettings] block on the OpenCode config and must run off the EDT. Each
 * keep-alive and each provider-catalog lookup runs on [executor], so blocking requests never
 * occupy the scheduler thread or the caller of [handleServerEvent] and [setSessionWarm].
 * State is guarded by one lock that is never held across [Deps.generate], [Deps.readProviders],
 * [Deps.readOpenCodeWarming], or [Deps.writeOpenCodeWarming]. [Deps.post], [Deps.saveChosen] and the
 * learner's storage run under the lock to keep published states ordered, so they must be quick and
 * must not call back in.
 */
class PromptCacheWarming(
    private val deps: Deps,
    /** Learns each model's real cache lifetime from requests after idle gaps. Share one per application. */
    private val learner: PromptCacheLearner = PromptCacheLearner(),
    private val scheduler: Scheduler = Scheduler.application(),
    private val executor: Executor = Executor { task ->
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute(task)
    },
) {
    data class Usage(val cacheRead: Double, val cacheWrite: Double)

    interface Deps {
        /** `off`, `chosen`, or `all`. */
        fun readMode(): String
        fun readDurationMs(): Long
        /** Warming needs OpenCode v2's transient `/generate` route on a running server. */
        fun isAvailable(): Boolean
        /** Root, user-visible sessions only: children and helper sessions are never warmed. */
        fun isWarmable(sessionID: String): Boolean
        fun isBusy(sessionID: String): Boolean
        fun directoryFor(sessionID: String): String?
        /** Blocking keep-alive request; it should abort promptly once [isCancelled] returns true. */
        fun generate(sessionID: String, directory: String?, prompt: String, isCancelled: () -> Boolean): Usage
        /**
         * The V1-shaped provider catalog (`GET /config/providers` → `providers`), for each model's
         * effective `cache.ttl`. Blocking; throw when the catalog is unreadable or malformed.
         */
        fun readProviders(directory: String?): JsonArray = JsonArray()
        /** Whether OpenCode's effective config already warms every session. Blocking. */
        fun readOpenCodeWarming(): Boolean
        /** Writes `true`, `{ "duration": "<n> minutes" }`, or removes the key for `null`. Blocking. */
        fun writeOpenCodeWarming(value: JsonElement?)
        fun loadChosen(): List<String>
        fun saveChosen(ids: List<String>)
        /** Receives the `warming/state` payload. */
        fun post(state: JsonObject)
        fun logWarning(message: String)
        fun now(): Long = System.currentTimeMillis()
    }

    fun interface Cancellable {
        fun cancel()
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, task: () -> Unit): Cancellable

        companion object {
            fun application(): Scheduler = Scheduler { delayMs, task ->
                val future = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                    .schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
                Cancellable { future.cancel(false) }
            }
        }
    }

    /** The latest model request of a session: providers count the cache lifetime from its dispatch. */
    private class SessionRequest(val sentAt: Long, val model: PromptCacheModel?)
    private class TurnEnd(val endedAt: Long, val sentAt: Long, val model: PromptCacheModel?)
    private class Lifetime(val ttlMs: Long, val likelyMs: Long)
    private class CacheUse(val at: Long, val model: PromptCacheModel)

    private class WarmEntry(
        /** End of the warming window; `null` while waiting for a turn to finish. */
        var warmUntil: Long?,
        /** When the latest request or ping sent the context, which is when its cache lifetime starts. */
        var sentAt: Long?,
        /** The model's guaranteed cache lifetime; `null` until the provider catalog is read. */
        var ttlMs: Long? = null,
        /** How long the cache usually lasts, at least `ttlMs`; `null` until the catalog is read. */
        var likelyMs: Long? = null,
        var stopped: String? = null,
    ) {
        var timer: Cancellable? = null
        var request: Request? = null
    }

    private class Request {
        @Volatile var cancelled = false
    }

    private class OpenCodeWarmingReading(val value: Boolean, val readAt: Long)

    private class CatalogRead(val readAt: Long) {
        val providers = CompletableFuture<JsonArray>()
    }

    private val lock = Any()
    private val chosen = LinkedHashSet<String>()
    private val entries = HashMap<String, WarmEntry>()
    private val requests = LinkedHashMap<String, SessionRequest>()
    private val turnEnds = LinkedHashMap<String, TurnEnd>()
    /** When each session's cache was last read or written, by a request or a keep-alive. */
    private val cacheUses = LinkedHashMap<String, CacheUse>()
    private val catalogs = LinkedHashMap<String, CatalogRead>()
    private var openCodeWarming: OpenCodeWarmingReading? = null
    private var disposed = false
    /** Blocking work queued under the lock, handed to [executor] once the lock is released. */
    private val deferred = ArrayList<() -> Unit>()

    init {
        chosen.addAll(deps.loadChosen().takeLast(MAX_CHOSEN_SESSIONS))
    }

    private fun <T> locked(block: () -> T): T {
        var tasks: List<() -> Unit> = emptyList()
        val result = synchronized(lock) {
            block().also {
                if (deferred.isNotEmpty()) {
                    tasks = deferred.toList()
                    deferred.clear()
                }
            }
        }
        tasks.forEach { task -> executor.execute { task() } }
        return result
    }

    fun snapshot(): JsonObject = locked { snapshotLocked() }

    private fun snapshotLocked(): JsonObject {
        val mode = deps.readMode()
        val sessions = JsonObject()
        if (mode == "chosen") {
            for (sessionID in chosen) {
                // The webview drops the whole state for one unsafe key, so never publish one.
                if (!isSafeSessionId(sessionID)) continue
                val entry = entries[sessionID]
                sessions.add(sessionID, JsonObject().apply {
                    add("warmUntil", nullable(entry?.warmUntil))
                    add("sentAt", nullable(entry?.sentAt))
                    add("ttlMs", nullable(entry?.ttlMs))
                    add("stopped", entry?.stopped?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                })
            }
        }
        val learned = JsonObject()
        for ((key, lifetime) in learner.snapshot()) {
            // The webview rejects the whole state for one oversized key.
            if (key.length > MAX_LEARNED_KEY_LENGTH || lifetime.ms <= 0) continue
            learned.add(key, Json.obj("ms" to lifetime.ms, "bounded" to lifetime.bounded))
        }
        return JsonObject().apply {
            addProperty("available", deps.isAvailable())
            addProperty("mode", mode)
            addProperty("durationMs", deps.readDurationMs())
            addProperty("openCodeWarming", openCodeWarming?.value ?: false)
            add("sessions", sessions)
            add("learned", learned)
        }
    }

    /**
     * Whether keep-alives Varro cannot see may renew caches, which would make them look longer
     * lived. OpenCode's own warming sends them for every session.
     */
    private fun hiddenKeepAlivesLocked() = deps.readMode() == "all" || openCodeWarming?.value == true

    /**
     * Records a session's history from the webview's `warming/samples`. A session chosen for warming
     * may have been kept alive by keep-alives that left no messages, so its history proves nothing
     * about expiry.
     */
    fun recordHistory(sessionID: String, observations: List<PromptCacheObservation>): Unit = locked {
        if (sessionID !in chosen && !hiddenKeepAlivesLocked() && learner.record(sessionID, observations)) publishLocked()
    }

    private fun publishLocked() {
        if (!disposed) deps.post(snapshotLocked())
    }

    private fun publish() = locked { publishLocked() }

    /** Applies a changed mode or duration. `all` is OpenCode's own config, so it is written there. Blocking. */
    fun applySettings(previousMode: String?) {
        val mode = deps.readMode()
        if (mode == "all") {
            val minutes = (deps.readDurationMs() / 60_000.0).roundToLong()
            // OpenCode's default window is 30 minutes, which plain `true` expresses.
            deps.writeOpenCodeWarming(
                if (minutes == 30L) JsonPrimitive(true) else Json.obj("duration" to "$minutes minutes")
            )
        } else if (previousMode == "all") {
            deps.writeOpenCodeWarming(null)
        }
        locked {
            openCodeWarming = null
            if (mode != "chosen") stopAllLocked()
        }
        if (mode == "chosen") refreshOpenCodeWarming()
        publish()
    }

    /** Refreshes OpenCode's warming flag and publishes the state. Blocking. */
    fun sync() {
        try {
            refreshOpenCodeWarming()
        } finally {
            publish()
        }
    }

    fun isChosen(sessionID: String): Boolean = locked { sessionID in chosen }

    fun setSessionWarm(sessionID: String, enabled: Boolean): Unit = locked { setSessionWarmLocked(sessionID, enabled) }

    private fun setSessionWarmLocked(sessionID: String, enabled: Boolean) {
        if (enabled == (sessionID in chosen)) {
            publishLocked()
            return
        }
        if (enabled) {
            if (!deps.isWarmable(sessionID)) {
                publishLocked()
                return
            }
            chosen.add(sessionID)
            while (chosen.size > MAX_CHOSEN_SESSIONS) {
                val oldest = chosen.first()
                chosen.remove(oldest)
                stopLocked(oldest)
            }
            startFromLastTurnLocked(sessionID)
        } else {
            chosen.remove(sessionID)
            stopLocked(sessionID)
        }
        deps.saveChosen(chosen.toList())
        publishLocked()
    }

    fun handleServerEvent(type: String, properties: JsonObject?): Unit = locked { handleServerEventLocked(type, properties) }

    private fun handleServerEventLocked(type: String, properties: JsonObject?) {
        if (disposed) return
        val sessionID = properties.str("sessionID")
        if (type == "session.deleted") {
            val id = sessionID ?: properties.obj("info").str("id")
            if (id != null && chosen.remove(id)) {
                stopLocked(id)
                deps.saveChosen(chosen.toList())
                publishLocked()
            }
            return
        }
        if (sessionID == null) return
        if (type == "session.next.step.started") {
            // Each step is one model request, and its dispatch starts the cache lifetime. Long responses
            // use up part of it, so the turn's end is too late to count from. A remote server's clock
            // can run ahead of this one, so a later timestamp counts as now.
            val now = deps.now()
            val model = properties.obj("model")
            val providerID = model.str("providerID")
            val modelIDElement = model?.get("modelID")?.takeUnless { it.isJsonNull } ?: model?.get("id")
            val modelID = modelIDElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val timestamp = properties.num("timestamp")
            boundedPut(requests, sessionID, SessionRequest(
                sentAt = if (timestamp != null) min(timestamp, now.toDouble()).toLong() else now,
                model = if (providerID != null && modelID != null) PromptCacheModel(providerID, modelID) else null,
            ))
            return
        }
        if (type == "session.next.step.ended") {
            learnFromStepLocked(sessionID, properties.obj("tokens"))
            return
        }
        // A compacted context is a new prompt, so the next request misses for reasons unrelated to expiry.
        if (type.startsWith("session.next.compaction.")) {
            cacheUses.remove(sessionID)
            return
        }
        if (type != "session.status" && type != "session.idle") return
        val status = properties.obj("status").str("type")
        if (type == "session.status" && (status == "busy" || status == "retry")) {
            turnEnds.remove(sessionID)
            if (sessionID !in chosen) return
            // A real request replaces the warming window; the next turn end starts a new one.
            stopLocked(sessionID)
            entries[sessionID] = WarmEntry(null, null)
            publishLocked()
            return
        }
        if (type == "session.status" && status != "idle") return
        val now = deps.now()
        val request = requests[sessionID]
        val model = request?.model
        // A step that holds several requests, such as Claude Code's own loop, sent its last one at an
        // unknown time; the turn's end is the closest known bound.
        val multiRequest = model != null && resolvePromptCacheLifetime(model).multiRequestSteps
        val turn = TurnEnd(endedAt = now, sentAt = if (request != null && !multiRequest) request.sentAt else now, model = model)
        boundedPut(turnEnds, sessionID, turn)
        if (sessionID !in chosen) return
        startWindowLocked(sessionID, turn)
        publishLocked()
    }

    /**
     * A finished step is evidence when it followed an idle gap on the same model: whether it read
     * the cache shows whether the cache outlived that gap.
     */
    private fun learnFromStepLocked(sessionID: String, tokens: JsonObject?) {
        val request = requests[sessionID]
        val model = request?.model ?: return
        val input = tokens.num("input") ?: return
        val read = tokens.obj("cache").num("read") ?: return
        val write = tokens.obj("cache").num("write") ?: return
        val previous = cacheUses[sessionID]
        if (previous != null && previous.model == model && !hiddenKeepAlivesLocked()) {
            val sample = promptCacheSample(previous.at.toDouble(), request.sentAt.toDouble(), input, read, write)
            if (sample != null && learner.record(
                    sessionID,
                    listOf(PromptCacheObservation(model.providerID, model.modelID, request.sentAt.toDouble(), sample.gapMs, sample.hit)),
                )
            ) publishLocked()
        }
        val usedAt = promptCacheUsedAt(request.sentAt, deps.now(), 1, resolvePromptCacheLifetime(model))
        boundedPut(cacheUses, sessionID, CacheUse(usedAt, model))
    }

    /** A newly chosen session is warmed at once only while its last request's cache is probably alive. */
    private fun startFromLastTurnLocked(sessionID: String) {
        stopLocked(sessionID)
        val entry = WarmEntry(null, null)
        entries[sessionID] = entry
        val turn = turnEnds[sessionID]
        if (turn == null || deps.isBusy(sessionID)) return
        deferred.add {
            val lifetime = lifetimeFor(sessionID, turn.model)
            locked {
                if (entries[sessionID] === entry && !deps.isBusy(sessionID) && deps.now() - turn.sentAt < lifetime.likelyMs) {
                    startWindowLocked(sessionID, turn, lifetime)
                    publishLocked()
                }
            }
        }
    }

    private fun startWindowLocked(sessionID: String, turn: TurnEnd, lifetime: Lifetime? = null) {
        stopLocked(sessionID)
        if (deps.readMode() != "chosen") return
        val entry = WarmEntry(
            warmUntil = turn.endedAt + deps.readDurationMs(),
            sentAt = turn.sentAt,
            ttlMs = lifetime?.ttlMs,
            likelyMs = lifetime?.likelyMs,
        )
        entries[sessionID] = entry
        if (lifetime != null) {
            scheduleLocked(sessionID, entry)
            return
        }
        deferred.add {
            val resolved = lifetimeFor(sessionID, turn.model)
            locked {
                if (entries[sessionID] === entry) {
                    entry.ttlMs = resolved.ttlMs
                    entry.likelyMs = resolved.likelyMs
                    scheduleLocked(sessionID, entry)
                    publishLocked()
                }
            }
        }
    }

    private fun scheduleLocked(sessionID: String, entry: WarmEntry) {
        val warmUntil = entry.warmUntil ?: return
        val sentAt = entry.sentAt ?: return
        val ttlMs = entry.ttlMs ?: return
        // A cache that outlives the window needs no keep-alive; the timer only closes the window.
        val due = if (sentAt + ttlMs >= warmUntil) warmUntil else sentAt + promptCacheKeepAliveIntervalMs(ttlMs)
        entry.timer = scheduler.schedule(max(0L, due - deps.now())) {
            executor.execute { ping(sessionID, entry) }
        }
    }

    /** The cache lifetime of a session's model, with its configured `cache.ttl` when readable. Blocking. */
    private fun lifetimeFor(sessionID: String, model: PromptCacheModel?): Lifetime {
        if (model == null) return Lifetime(FALLBACK_PROMPT_CACHE_TTL_MS, FALLBACK_PROMPT_CACHE_TTL_MS)
        val providers = try {
            providers(deps.directoryFor(sessionID))
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            // Without the catalog, the provider's documented default still applies.
            null
        }
        val lifetime = promptCacheLifetimeFor(providers, model, learner.snapshot())
        return Lifetime(lifetime.ttlMs, lifetime.likelyMs ?: lifetime.ttlMs)
    }

    /** The provider catalog for a directory, read at most once a minute; concurrent lookups share one read. */
    private fun providers(directory: String?): JsonArray {
        val key = directory ?: ""
        val now = deps.now()
        var owner = false
        val read = synchronized(lock) {
            val cached = catalogs[key]
            if (cached != null && now - cached.readAt < PROVIDER_CATALOG_CACHE_MS) cached
            else CatalogRead(now).also {
                boundedPut(catalogs, key, it)
                owner = true
            }
        }
        if (owner) {
            try {
                read.providers.complete(deps.readProviders(directory))
            } catch (error: Exception) {
                // A failed read is retried by the next window instead of being cached.
                synchronized(lock) { if (catalogs[key] === read) catalogs.remove(key) }
                read.providers.completeExceptionally(error)
            }
        }
        return read.providers.get()
    }

    private class PingStart(val directory: String?)

    private fun ping(sessionID: String, entry: WarmEntry) {
        val now = locked { pingCheckLocked(sessionID, entry) } ?: return
        if (refreshOpenCodeWarming()) {
            // OpenCode already warms this session; a second ping would only cost more.
            locked { finishLocked(entry, null) }
            return
        }
        val request = Request()
        val start = locked {
            if (entries[sessionID] !== entry) null
            else {
                entry.request = request
                PingStart(deps.directoryFor(sessionID))
            }
        } ?: return
        try {
            val usage = deps.generate(sessionID, start.directory, KEEP_ALIVE_PROMPT) { request.cancelled }
            locked { pingSucceededLocked(sessionID, entry, request, now, usage) }
        } catch (error: Exception) {
            locked {
                if (!request.cancelled && entries[sessionID] === entry) {
                    entry.request = null
                    deps.logWarning("Prompt cache warming stopped for $sessionID: ${error.message ?: error.toString()}")
                    finishLocked(entry, "failed")
                }
            }
        }
    }

    /** Returns the ping's time when it should go ahead, or `null` when it should not. */
    private fun pingCheckLocked(sessionID: String, entry: WarmEntry): Long? {
        entry.timer = null
        val warmUntil = entry.warmUntil
        if (disposed || entries[sessionID] !== entry || warmUntil == null) return null
        val now = deps.now()
        if (now >= warmUntil) {
            finishLocked(entry, "expired")
            return null
        }
        // A running turn sends the context itself and will start a new window when it ends.
        if (deps.isBusy(sessionID)) return null
        // The last request outlasted the cache, or the timer fired late after the computer slept:
        // a ping would only write a new cache, and the next real request does that anyway. Past the
        // provider's minimum but within its usual lifetime, a ping still probably renews it.
        val sentAt = entry.sentAt
        val likelyMs = entry.likelyMs
        if (sentAt != null && likelyMs != null && now >= sentAt + likelyMs) {
            finishLocked(entry, "cache-miss")
            return null
        }
        if (!deps.isAvailable() || deps.readMode() != "chosen") return null
        return now
    }

    private fun pingSucceededLocked(sessionID: String, entry: WarmEntry, request: Request, now: Long, usage: Usage) {
        if (entries[sessionID] !== entry || request.cancelled) return
        entry.request = null
        entry.sentAt = now
        // The keep-alive renewed the cache, so later gaps count from it.
        requests[sessionID]?.model?.let { boundedPut(cacheUses, sessionID, CacheUse(now, it)) }
        // A ping that wrote more than it read rebuilt the cache instead of keeping it, so later
        // pings would keep paying for that. Providers that report no cache usage never miss.
        if (usage.cacheRead < usage.cacheWrite) {
            finishLocked(entry, "cache-miss")
            return
        }
        scheduleLocked(sessionID, entry)
        publishLocked()
    }

    private fun finishLocked(entry: WarmEntry, stopped: String?) {
        entry.stopped = stopped
        entry.warmUntil = if (stopped == "expired") entry.warmUntil else null
        publishLocked()
    }

    private fun refreshOpenCodeWarming(): Boolean {
        val now = deps.now()
        synchronized(lock) {
            openCodeWarming?.let { if (now - it.readAt < OPENCODE_WARMING_CACHE_MS) return it.value }
            if (!deps.isAvailable()) return false
        }
        return try {
            val value = deps.readOpenCodeWarming()
            synchronized(lock) { openCodeWarming = OpenCodeWarmingReading(value, now) }
            value
        } catch (_: Exception) {
            // An unreadable config keeps Varro's own schedule; OpenCode warms only when configured.
            synchronized(lock) { openCodeWarming?.value ?: false }
        }
    }

    private fun stopLocked(sessionID: String) {
        val entry = entries.remove(sessionID) ?: return
        entry.timer?.cancel()
        entry.timer = null
        entry.request?.cancelled = true
    }

    private fun stopAllLocked() {
        for (sessionID in entries.keys.toList()) stopLocked(sessionID)
    }

    fun dispose(): Unit = locked {
        disposed = true
        stopAllLocked()
    }

    companion object {
        /**
         * OpenCode's own keep-alive prompt. Its warming plugin ignores a request with exactly this text,
         * so a Varro ping never restarts OpenCode's schedule when both are configured.
         */
        const val KEEP_ALIVE_PROMPT =
            "This is a keep-alive request. Do not perform any work or use tools. Reply with exactly: OK"
        const val MAX_CHOSEN_SESSIONS = 50
        /** The webview's `warming/samples` limit per message. */
        const val MAX_PROMPT_CACHE_SAMPLES = 500
        private const val MAX_TRACKED_SESSIONS = 200
        private const val OPENCODE_WARMING_CACHE_MS = 60_000L
        private const val PROVIDER_CATALOG_CACHE_MS = 60_000L
        private const val MAX_PERSISTED_SESSION_ID_LENGTH = 512
        private const val MAX_LEARNED_KEY_LENGTH = 1024

        private fun nullable(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE

        private fun <V> boundedPut(map: LinkedHashMap<String, V>, key: String, value: V) {
            map.remove(key)
            map[key] = value
            while (map.size > MAX_TRACKED_SESSIONS) map.remove(map.keys.first())
        }

        /** Mirrors the webview's `isSafePersistedSessionId`. */
        internal fun isSafeSessionId(id: String): Boolean =
            id.isNotEmpty() && id.length <= MAX_PERSISTED_SESSION_ID_LENGTH &&
                id.none { it.code <= 0x1f || it.code == 0x7f }

        /**
         * Validates a webview `warming/samples` payload as upstream's `parseWebviewMessage` does, and
         * returns its session ID and samples as observations; `null` rejects the whole message.
         */
        fun parseHistorySamples(payload: JsonObject?): Pair<String, List<PromptCacheObservation>>? {
            val sessionId = payload.str("sessionId")?.takeIf { it.length in 1..512 && SESSION_ID.matches(it) } ?: return null
            val samples = payload?.get("samples")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (samples.size() > MAX_PROMPT_CACHE_SAMPLES) return null
            val observations = samples.map { element ->
                val sample = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                val providerID = sample.str("providerID")?.takeIf { it.length in 1..256 } ?: return null
                val modelID = sample.str("modelID")?.takeIf { it.length in 1..256 } ?: return null
                val at = sample.num("at")?.takeIf { it.isFinite() } ?: return null
                val gapMs = sample.num("gapMs")?.takeIf { it.isFinite() && it > 0 } ?: return null
                val hit = sample.get("hit")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
                PromptCacheObservation(providerID, modelID, at, gapMs, hit)
            }
            return sessionId to observations
        }

        private val SESSION_ID = Regex("^[A-Za-z0-9_-]+$")
    }
}
