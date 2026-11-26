package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import varro.protocol.Json
import varro.protocol.obj
import varro.protocol.str
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
 * calls: [sync] and [applySettings] block on the OpenCode config and must run off the EDT, and each
 * keep-alive runs on [executor] so a blocking `/generate` request never occupies the scheduler thread.
 * State is guarded by one lock that is never held across [Deps.generate],
 * [Deps.readOpenCodeWarming], or [Deps.writeOpenCodeWarming]. [Deps.post] and [Deps.saveChosen] run
 * under the lock to keep published states ordered, so they must be quick and must not call back in.
 */
class PromptCacheWarming(
    private val deps: Deps,
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

    private class WarmEntry(
        /** End of the warming window; `null` while waiting for a turn to finish. */
        var warmUntil: Long?,
        /** When the latest turn or ping sent the context, which is when its cache lifetime starts. */
        var sentAt: Long?,
        var stopped: String?,
    ) {
        var timer: Cancellable? = null
        var request: Request? = null
    }

    private class Request {
        @Volatile var cancelled = false
    }

    private class OpenCodeWarmingReading(val value: Boolean, val readAt: Long)

    private val lock = Any()
    private val chosen = LinkedHashSet<String>()
    private val entries = HashMap<String, WarmEntry>()
    private val turnEnds = LinkedHashMap<String, Long>()
    private var openCodeWarming: OpenCodeWarmingReading? = null
    private var disposed = false

    init {
        chosen.addAll(deps.loadChosen().takeLast(MAX_CHOSEN_SESSIONS))
    }

    fun snapshot(): JsonObject = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): JsonObject {
        val mode = deps.readMode()
        val sessions = JsonObject()
        if (mode == "chosen") {
            for (sessionID in chosen) {
                // The webview drops the whole state for one unsafe key, so never publish one.
                if (!isSafeSessionId(sessionID)) continue
                val entry = entries[sessionID]
                sessions.add(sessionID, JsonObject().apply {
                    add("warmUntil", entry?.warmUntil?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                    add("sentAt", entry?.sentAt?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                    add("stopped", entry?.stopped?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                })
            }
        }
        return JsonObject().apply {
            addProperty("available", deps.isAvailable())
            addProperty("mode", mode)
            addProperty("durationMs", deps.readDurationMs())
            addProperty("intervalMs", PROMPT_CACHE_PING_INTERVAL_MS)
            addProperty("openCodeWarming", openCodeWarming?.value ?: false)
            add("sessions", sessions)
        }
    }

    private fun publishLocked() {
        if (!disposed) deps.post(snapshotLocked())
    }

    private fun publish() = synchronized(lock) { publishLocked() }

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
        synchronized(lock) {
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

    fun isChosen(sessionID: String): Boolean = synchronized(lock) { sessionID in chosen }

    fun setSessionWarm(sessionID: String, enabled: Boolean): Unit = synchronized(lock) {
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

    fun handleServerEvent(type: String, properties: JsonObject?): Unit = synchronized(lock) {
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
        if (sessionID == null || (type != "session.status" && type != "session.idle")) return
        val status = properties.obj("status").str("type")
        if (type == "session.status" && (status == "busy" || status == "retry")) {
            turnEnds.remove(sessionID)
            if (sessionID !in chosen) return
            // A real request replaces the warming window; the next turn end starts a new one.
            stopLocked(sessionID)
            entries[sessionID] = WarmEntry(null, null, null)
            publishLocked()
            return
        }
        if (type == "session.status" && status != "idle") return
        val now = deps.now()
        turnEnds.remove(sessionID)
        turnEnds[sessionID] = now
        while (turnEnds.size > MAX_TRACKED_TURN_ENDS) turnEnds.remove(turnEnds.keys.first())
        if (sessionID !in chosen) return
        startWindowLocked(sessionID, now)
        publishLocked()
    }

    /** A newly chosen session is warmed at once only when its last turn ended recently enough. */
    private fun startFromLastTurnLocked(sessionID: String) {
        val endedAt = turnEnds[sessionID]
        if (endedAt != null && !deps.isBusy(sessionID) && deps.now() - endedAt < PROMPT_CACHE_TTL_ESTIMATE_MS) {
            startWindowLocked(sessionID, endedAt)
            return
        }
        stopLocked(sessionID)
        entries[sessionID] = WarmEntry(null, null, null)
    }

    private fun startWindowLocked(sessionID: String, sentAt: Long) {
        stopLocked(sessionID)
        if (deps.readMode() != "chosen") return
        val entry = WarmEntry(sentAt + deps.readDurationMs(), sentAt, null)
        entries[sessionID] = entry
        scheduleLocked(sessionID, entry)
    }

    private fun scheduleLocked(sessionID: String, entry: WarmEntry) {
        val warmUntil = entry.warmUntil ?: return
        val sentAt = entry.sentAt ?: return
        val due = min(sentAt + PROMPT_CACHE_PING_INTERVAL_MS, warmUntil)
        entry.timer = scheduler.schedule(max(0L, due - deps.now())) {
            executor.execute { ping(sessionID, entry) }
        }
    }

    private fun ping(sessionID: String, entry: WarmEntry) {
        val now: Long
        synchronized(lock) {
            entry.timer = null
            val warmUntil = entry.warmUntil
            if (disposed || entries[sessionID] !== entry || warmUntil == null) return
            now = deps.now()
            if (now >= warmUntil) {
                finishLocked(entry, "expired")
                return
            }
            // A running turn sends the context itself and will start a new window when it ends.
            if (deps.isBusy(sessionID)) return
            if (!deps.isAvailable() || deps.readMode() != "chosen") return
        }
        if (refreshOpenCodeWarming()) {
            // OpenCode already warms this session; a second ping would only cost more.
            synchronized(lock) { finishLocked(entry, null) }
            return
        }
        val request = Request()
        val directory: String?
        synchronized(lock) {
            if (entries[sessionID] !== entry) return
            entry.request = request
            directory = deps.directoryFor(sessionID)
        }
        try {
            val usage = deps.generate(sessionID, directory, KEEP_ALIVE_PROMPT) { request.cancelled }
            synchronized(lock) {
                if (entries[sessionID] !== entry || request.cancelled) return
                entry.request = null
                entry.sentAt = now
                // A ping that wrote more than it read rebuilt the cache instead of keeping it, so later
                // pings would keep paying for that. Providers that report no cache usage never miss.
                if (usage.cacheRead < usage.cacheWrite) {
                    finishLocked(entry, "cache-miss")
                    return
                }
                scheduleLocked(sessionID, entry)
                publishLocked()
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (request.cancelled || entries[sessionID] !== entry) return
                entry.request = null
                deps.logWarning("Prompt cache warming stopped for $sessionID: ${error.message ?: error.toString()}")
                finishLocked(entry, "failed")
            }
        }
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

    fun dispose(): Unit = synchronized(lock) {
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
        /** OpenCode's fallback for models without a cache TTL: a little under the common 5 minutes. */
        const val PROMPT_CACHE_PING_INTERVAL_MS = 4 * 60_000L
        /** Most providers keep an idle prompt cache about this long after the request that used it. */
        private const val PROMPT_CACHE_TTL_ESTIMATE_MS = 5 * 60_000L
        const val MAX_CHOSEN_SESSIONS = 50
        private const val MAX_TRACKED_TURN_ENDS = 200
        private const val OPENCODE_WARMING_CACHE_MS = 60_000L
        private const val MAX_PERSISTED_SESSION_ID_LENGTH = 512

        /** Mirrors the webview's `isSafePersistedSessionId`. */
        internal fun isSafeSessionId(id: String): Boolean =
            id.isNotEmpty() && id.length <= MAX_PERSISTED_SESSION_ID_LENGTH &&
                id.none { it.code <= 0x1f || it.code == 0x7f }
    }
}
