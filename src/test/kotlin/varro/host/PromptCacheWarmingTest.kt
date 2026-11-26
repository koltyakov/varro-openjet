package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import varro.host.PromptCacheWarming.Companion.KEEP_ALIVE_PROMPT
import varro.protocol.Json

class PromptCacheWarmingTest {
    private val minute = 60_000L
    /** The keep-alive interval for the 5 min fallback lifetime. */
    private val pingInterval = 4 * minute

    /** Fake timers: timers follow [advance]; [jump] moves only the wall clock, like a sleeping computer. */
    private class ManualScheduler(private var timerNow: Long) : PromptCacheWarming.Scheduler {
        private class Task(val due: Long, val order: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        private var offset = 0L
        val now get() = timerNow + offset

        override fun schedule(delayMs: Long, task: () -> Unit): PromptCacheWarming.Cancellable {
            val entry = Task(timerNow + delayMs, sequence++, task)
            tasks.add(entry)
            return PromptCacheWarming.Cancellable { entry.cancelled = true }
        }

        fun advance(ms: Long) {
            val target = timerNow + ms
            while (true) {
                tasks.removeAll { it.cancelled }
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy({ it.due }, { it.order })) ?: break
                tasks.remove(next)
                timerNow = maxOf(timerNow, next.due)
                next.run()
            }
            timerNow = target
        }

        fun jump(ms: Long) { offset += ms }

        fun pending() = tasks.count { !it.cancelled }
    }

    private class Harness(
        stored: List<String>? = null,
        val readOpenCode: () -> Boolean = { false },
        var providers: (String?) -> JsonArray = { JsonArray() },
    ) : PromptCacheWarming.Deps {
        val scheduler = ManualScheduler(1_000_000)
        var mode = "chosen"
        var durationMs = 30 * 60_000L
        var available = true
        val busy = mutableSetOf<String>()
        var saved: List<String>? = stored
        val posted = mutableListOf<JsonObject>()
        val warnings = mutableListOf<String>()
        val generated = mutableListOf<Triple<String, String?, String>>()
        val writes = mutableListOf<JsonElement?>()
        val providerReads = mutableListOf<String?>()
        var openCodeReads = 0
        var onGenerate: (String, () -> Boolean) -> PromptCacheWarming.Usage = { _, _ -> PromptCacheWarming.Usage(100_000.0, 20.0) }
        val learnerStorage = PromptCacheLearner.Storage.inMemory()
        val warming by lazy { PromptCacheWarming(this, PromptCacheLearner(learnerStorage), scheduler) { it.run() } }

        override fun readMode() = mode
        override fun readDurationMs() = durationMs
        override fun isAvailable() = available
        override fun isWarmable(sessionID: String) = !sessionID.startsWith("child")
        override fun isBusy(sessionID: String) = sessionID in busy
        override fun directoryFor(sessionID: String) = "/workspace"
        override fun generate(sessionID: String, directory: String?, prompt: String, isCancelled: () -> Boolean): PromptCacheWarming.Usage {
            generated.add(Triple(sessionID, directory, prompt))
            return onGenerate(sessionID, isCancelled)
        }
        override fun readProviders(directory: String?): JsonArray { providerReads.add(directory); return providers(directory) }
        override fun readOpenCodeWarming(): Boolean { openCodeReads++; return readOpenCode() }
        override fun writeOpenCodeWarming(value: JsonElement?) { writes.add(value) }
        override fun loadChosen() = saved ?: emptyList()
        override fun saveChosen(ids: List<String>) { saved = ids }
        override fun post(state: JsonObject) { posted.add(state) }
        override fun logWarning(message: String) { warnings.add(message) }
        override fun now() = scheduler.now

        fun latest() = posted.last()
        fun session(id: String) = latest().getAsJsonObject("sessions").getAsJsonObject(id)
        fun learned() = latest().getAsJsonObject("learned")
        fun idle(id: String) = warming.handleServerEvent("session.status", status(id, "idle"))
        fun status(id: String, type: String) = Json.obj("sessionID" to id, "status" to Json.obj("type" to type))
        fun stepStarted(id: String, timestamp: Long, providerID: String, modelID: String) = warming.handleServerEvent(
            "session.next.step.started",
            Json.obj("sessionID" to id, "timestamp" to timestamp, "model" to Json.obj("providerID" to providerID, "id" to modelID, "modelID" to modelID)),
        )
        fun stepEnded(id: String, read: Long, input: Long = 200) = warming.handleServerEvent(
            "session.next.step.ended",
            Json.obj("sessionID" to id, "tokens" to Json.obj("input" to input, "output" to 10, "reasoning" to 0, "cache" to Json.obj("read" to read, "write" to 0))),
        )
    }

    @Test fun `pings a chosen session before its cache expires until the window ends`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        assertEquals(Json.parse("""{"warmUntil":null,"sentAt":null,"ttlMs":null,"stopped":null}"""), harness.session("session-1"))

        harness.idle("session-1")
        assertEquals(1_000_000 + 30 * minute, harness.session("session-1")["warmUntil"].asLong)

        harness.scheduler.advance(pingInterval)
        assertEquals(listOf(Triple("session-1", "/workspace", KEEP_ALIVE_PROMPT)), harness.generated)

        harness.scheduler.advance(30 * minute)
        // Pings at 4, 8, ..., 28 minutes; the window closes at 30 without another request.
        assertEquals(7, harness.generated.size)
        assertEquals("expired", harness.session("session-1")["stopped"].asString)
        assertEquals(1_000_000 + 30 * minute, harness.session("session-1")["warmUntil"].asLong)
        assertEquals(1_000_000 + 28 * minute, harness.session("session-1")["sentAt"].asLong)
        assertEquals(5 * minute, harness.session("session-1")["ttlMs"].asLong)
        assertEquals(listOf("session-1"), harness.saved)
        assertEquals(0, harness.scheduler.pending())
    }

    @Test fun `never pings unchosen sessions, child sessions, or a session that is running`() {
        val harness = Harness()
        harness.warming.setSessionWarm("child-1", true)
        harness.idle("other")
        harness.idle("child-1")
        assertEquals(JsonObject(), harness.latest().getAsJsonObject("sessions"))
        assertFalse(harness.warming.isChosen("child-1"))

        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.busy.add("session-1")
        harness.scheduler.advance(pingInterval)
        assertTrue(harness.generated.isEmpty())

        // A new turn replaces the window; its end starts the next one.
        harness.warming.handleServerEvent("session.status", harness.status("session-1", "busy"))
        assertTrue(harness.session("session-1")["warmUntil"].isJsonNull)
        harness.busy.remove("session-1")
        harness.idle("session-1")
        harness.scheduler.advance(pingInterval)
        assertEquals(1, harness.generated.size)
    }

    @Test fun `session idle events also start a window and retry stops it`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.warming.handleServerEvent("session.idle", Json.obj("sessionID" to "session-1"))
        assertEquals(1_000_000L, harness.session("session-1")["sentAt"].asLong)
        harness.warming.handleServerEvent("session.status", harness.status("session-1", "retry"))
        assertTrue(harness.session("session-1")["sentAt"].isJsonNull)
        harness.scheduler.advance(pingInterval * 2)
        assertTrue(harness.generated.isEmpty())
    }

    @Test fun `starts at once for a recent reply and waits for the next reply otherwise`() {
        val harness = Harness()
        harness.idle("recent")
        harness.idle("stale")
        harness.scheduler.advance(2 * minute)
        harness.warming.setSessionWarm("recent", true)
        assertEquals(1_000_000L, harness.session("recent")["sentAt"].asLong)

        harness.scheduler.advance(4 * minute)
        harness.warming.setSessionWarm("stale", true)
        assertTrue(harness.session("stale")["warmUntil"].isJsonNull)
        assertEquals(1, harness.generated.size)
        assertEquals("recent", harness.generated[0].first)
    }

    @Test fun `stops after a keep-alive rebuilt the cache instead of reading it`() {
        val harness = Harness()
        harness.onGenerate = { _, _ -> PromptCacheWarming.Usage(0.0, 90_000.0) }
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.advance(pingInterval * 3)
        assertEquals(1, harness.generated.size)
        assertEquals("cache-miss", harness.session("session-1")["stopped"].asString)
        assertTrue(harness.session("session-1")["warmUntil"].isJsonNull)
    }

    @Test fun `counts Claude's lifetime from the last request's dispatch, not the turn's end`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "anthropic", "claude-sonnet-5-5")
        harness.scheduler.advance(3 * minute)
        harness.idle("session-1")
        assertEquals(1_000_000L, harness.session("session-1")["sentAt"].asLong)
        assertEquals(5 * minute, harness.session("session-1")["ttlMs"].asLong)
        assertEquals(1_000_000 + 33 * minute, harness.session("session-1")["warmUntil"].asLong)
        // Due 4 min after the dispatch, one minute after the turn ended.
        harness.scheduler.advance(minute)
        assertEquals(1, harness.generated.size)
    }

    @Test fun `a server timestamp ahead of this clock counts as now`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now() + 10 * minute, "anthropic", "claude-sonnet-5-5")
        harness.idle("session-1")
        assertEquals(1_000_000L, harness.session("session-1")["sentAt"].asLong)
    }

    @Test fun `counts Claude Code's lifetime from the turn's end, since one step holds its own loop`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "claude-agent", "sonnet")
        harness.scheduler.advance(10 * minute)
        harness.idle("session-1")
        // An unpriced Claude Agent model is a subscription, whose 1 h cache outlives the window.
        assertEquals(1_000_000 + 10 * minute, harness.session("session-1")["sentAt"].asLong)
        assertEquals(60 * minute, harness.session("session-1")["ttlMs"].asLong)
        assertTrue(harness.session("session-1")["stopped"].isJsonNull)
        harness.scheduler.advance(30 * minute)
        assertTrue(harness.generated.isEmpty())
        assertEquals("expired", harness.session("session-1")["stopped"].asString)
    }

    @Test fun `still pings after OpenAI's minimum while its cache is probably warm`() {
        val harness = Harness()
        harness.durationMs = 60 * minute
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "openai", "gpt-6.2-sol")
        harness.idle("session-1")
        // The computer sleeps past the 30 min minimum but well within the usual 3 h.
        harness.scheduler.jump(20 * minute)
        harness.scheduler.advance(27 * minute)
        assertEquals(1, harness.generated.size)
    }

    @Test fun `skips the keep-alive when the last response outlasted the cache`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "anthropic", "claude-opus-5-5")
        harness.scheduler.advance(6 * minute)
        harness.idle("session-1")
        harness.scheduler.advance(minute)
        assertTrue(harness.generated.isEmpty())
        assertEquals("cache-miss", harness.session("session-1")["stopped"].asString)
    }

    @Test fun `skips a keep-alive that fires after the cache expired while the computer slept`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.jump(10 * minute)
        harness.scheduler.advance(pingInterval)
        assertTrue(harness.generated.isEmpty())
        assertEquals("cache-miss", harness.session("session-1")["stopped"].asString)
    }

    @Test fun `does not ping when OpenAI's 30 min cache already covers the window`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "openai", "gpt-6.2-sol")
        harness.idle("session-1")
        harness.scheduler.advance(30 * minute)
        assertTrue(harness.generated.isEmpty())
        assertEquals("expired", harness.session("session-1")["stopped"].asString)

        // A longer window pings 27 min after each request.
        harness.durationMs = 60 * minute
        harness.stepStarted("session-1", harness.now(), "openai", "gpt-6.2-sol")
        harness.idle("session-1")
        harness.scheduler.advance(60 * minute)
        assertEquals(2, harness.generated.size)
        assertEquals("expired", harness.session("session-1")["stopped"].asString)
    }

    @Test fun `uses the model's configured cache ttl from the provider catalog, cached per directory`() {
        val catalog = Json.parse(
            """[{"id":"anthropic","name":"Anthropic","source":"api","options":{"cache":{"ttl":"1h"}},
               "models":{"claude-opus-5-5":{"id":"claude-opus-5-5","name":"Claude Opus 5.5","capabilities":{},"cost":{"input":0,"output":0}}}}]""",
        ).asJsonArray
        val harness = Harness(providers = { catalog })
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "anthropic", "claude-opus-5-5")
        harness.idle("session-1")
        harness.scheduler.advance(30 * minute)
        assertEquals(listOf<String?>("/workspace"), harness.providerReads)
        assertEquals(60 * minute, harness.session("session-1")["ttlMs"].asLong)
        assertTrue(harness.generated.isEmpty())

        // Within a minute the catalog is reused; later it is read again.
        harness.stepStarted("session-1", harness.now(), "anthropic", "claude-opus-5-5")
        harness.idle("session-1")
        harness.stepStarted("session-1", harness.now(), "anthropic", "claude-opus-5-5")
        harness.idle("session-1")
        assertEquals(2, harness.providerReads.size)
        harness.scheduler.advance(30 * minute)
        assertEquals(2, harness.providerReads.size)
    }

    @Test fun `an unreadable catalog falls back to the provider default and is read again next time`() {
        var fail = true
        val harness = Harness(providers = { if (fail) error("Invalid OpenCode provider catalog") else JsonArray() })
        harness.warming.setSessionWarm("session-1", true)
        harness.stepStarted("session-1", harness.now(), "anthropic", "claude-opus-5-5")
        harness.idle("session-1")
        assertEquals(5 * minute, harness.session("session-1")["ttlMs"].asLong)
        fail = false
        harness.idle("session-1")
        assertEquals(2, harness.providerReads.size)
        assertTrue(harness.warnings.isEmpty())
    }

    private fun idleRequest(harness: Harness, sessionID: String, gapMinutes: Long, read: Long) {
        harness.scheduler.advance(gapMinutes * minute)
        harness.stepStarted(sessionID, harness.now(), "anthropic", "claude-opus-5-5")
        harness.stepEnded(sessionID, read, if (read != 0L) 200 else 60_000)
    }

    @Test fun `learns a shorter lifetime from requests after idle gaps and keeps warming by it`() {
        val harness = Harness()
        idleRequest(harness, "session-1", 0, 60_000)
        // Three requests 7 min after the previous one all miss: this account keeps 5 min.
        repeat(3) { idleRequest(harness, "session-1", 7, 0) }
        assertEquals(Json.parse("""{"ms":300000,"bounded":true}"""), harness.learned()["anthropic/claude-opus-5-5"])
        assertEquals(Json.parse("""{"ms":300000,"bounded":true}"""), harness.learned()["anthropic/*"])
        // The learned lifetime survives a restart through the learner's storage.
        assertEquals(
            mapOf("anthropic/claude-opus-5-5" to LearnedPromptCacheLifetime(5 * minute, true), "anthropic/*" to LearnedPromptCacheLifetime(5 * minute, true)),
            PromptCacheLearner(harness.learnerStorage).snapshot(),
        )
    }

    @Test fun `measures gaps from the latest keep-alive and skips compactions`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        idleRequest(harness, "session-1", 0, 60_000)
        harness.idle("session-1")
        // Keep-alives at 4 and 8 min, then a hit at 10 min: a 2 min gap, which proves nothing.
        idleRequest(harness, "session-1", 10, 60_000)
        assertEquals(2, harness.generated.size)
        harness.warming.handleServerEvent("session.next.compaction.ended", Json.obj("sessionID" to "session-1"))
        // The first request after a compaction misses because its prompt changed.
        idleRequest(harness, "session-1", 0, 0)
        assertEquals(JsonObject(), harness.learned())
    }

    @Test fun `learns nothing while OpenCode sends keep-alives Varro cannot see`() {
        val harness = Harness()
        harness.mode = "all"
        idleRequest(harness, "session-1", 0, 60_000)
        repeat(3) { idleRequest(harness, "session-1", 20, 60_000) }
        harness.warming.recordHistory("session-2", listOf(PromptCacheObservation("openai", "gpt-6.2-sol", 1.0, 20.0 * minute, true)))
        assertEquals(JsonObject(), harness.warming.snapshot().getAsJsonObject("learned"))
    }

    @Test fun `learns from history except where keep-alives may have renewed the cache`() {
        val harness = Harness()
        val samples = listOf(1.0, 2.0, 3.0).map { PromptCacheObservation("openai", "gpt-6.2-sol", it, 100.0 * minute, true) }
        harness.warming.setSessionWarm("chosen-1", true)
        harness.warming.recordHistory("chosen-1", samples)
        assertEquals(JsonObject(), harness.learned())
        harness.warming.recordHistory("session-1", samples)
        harness.warming.recordHistory("session-1", samples)
        assertEquals(Json.parse("""{"ms":7200000,"bounded":false}"""), harness.learned()["openai/gpt-6.2-sol"])
    }

    @Test fun `history samples are validated like upstream's webview message parser`() {
        val sample = Json.obj("providerID" to "openai", "modelID" to "gpt-6.2-sol", "at" to 12.5, "gapMs" to 600_000, "hit" to true)
        assertEquals(
            "ses_1" to listOf(PromptCacheObservation("openai", "gpt-6.2-sol", 12.5, 600_000.0, true)),
            PromptCacheWarming.parseHistorySamples(Json.obj("sessionId" to "ses_1", "samples" to listOf(sample))),
        )
        assertEquals("ses_1" to emptyList<PromptCacheObservation>(), PromptCacheWarming.parseHistorySamples(Json.obj("sessionId" to "ses_1", "samples" to emptyList<Any>())))
        for (payload in listOf(
            Json.obj("sessionId" to "bad id", "samples" to listOf(sample)),
            Json.obj("sessionId" to "ses_1"),
            Json.obj("sessionId" to "ses_1", "samples" to List(501) { sample }),
            Json.obj("sessionId" to "ses_1", "samples" to listOf(sample.deepCopy().apply { addProperty("gapMs", 0) })),
            Json.obj("sessionId" to "ses_1", "samples" to listOf(sample.deepCopy().apply { addProperty("hit", "yes") })),
            Json.obj("sessionId" to "ses_1", "samples" to listOf(sample.deepCopy().apply { addProperty("modelID", "") })),
            Json.obj("sessionId" to "ses_1", "samples" to listOf(sample.deepCopy().apply { addProperty("at", "1") })),
        )) assertNull(payload.toString(), PromptCacheWarming.parseHistorySamples(payload))
    }

    @Test fun `a failed keep-alive stops warming and logs a warning`() {
        val harness = Harness()
        harness.onGenerate = { _, _ -> throw IllegalStateException("503 Unavailable") }
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.advance(pingInterval * 3)
        assertEquals(1, harness.generated.size)
        assertEquals("failed", harness.session("session-1")["stopped"].asString)
        assertEquals(listOf("Prompt cache warming stopped for session-1: 503 Unavailable"), harness.warnings)
    }

    @Test fun `a turn that starts during a keep-alive cancels it and discards its result`() {
        val harness = Harness()
        var observedCancel = false
        harness.onGenerate = { id, isCancelled ->
            assertFalse(isCancelled())
            harness.warming.handleServerEvent("session.status", harness.status(id, "busy"))
            observedCancel = isCancelled()
            PromptCacheWarming.Usage(0.0, 90_000.0)
        }
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.advance(pingInterval)
        assertTrue(observedCancel)
        assertEquals(Json.parse("""{"warmUntil":null,"sentAt":null,"ttlMs":null,"stopped":null}"""), harness.session("session-1"))
        assertTrue(harness.warnings.isEmpty())
    }

    @Test fun `defers to OpenCode warming and writes it for all sessions`() {
        val harness = Harness(readOpenCode = { true })
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.advance(pingInterval)
        assertTrue(harness.generated.isEmpty())
        assertTrue(harness.latest()["openCodeWarming"].asBoolean)
        assertTrue(harness.session("session-1")["stopped"].isJsonNull)
        assertTrue(harness.session("session-1")["warmUntil"].isJsonNull)

        harness.mode = "all"
        harness.warming.applySettings("chosen")
        assertEquals(JsonPrimitive(true), harness.writes.last())
        assertEquals(JsonObject(), harness.latest().getAsJsonObject("sessions"))
        harness.mode = "off"
        harness.warming.applySettings("all")
        assertNull(harness.writes.last())
        harness.mode = "chosen"
        harness.warming.applySettings("off")
        assertEquals(2, harness.writes.size)
    }

    @Test fun `all mode writes a custom duration in whole minutes`() {
        val harness = Harness()
        harness.mode = "all"
        harness.durationMs = 45 * minute + 20_000
        harness.warming.applySettings(null)
        assertEquals(Json.obj("duration" to "45 minutes"), harness.writes.single())
        assertEquals(0, harness.openCodeReads)
    }

    @Test fun `OpenCode warming is cached for a minute and unreadable config keeps the last value`() {
        var value = false
        var fail = false
        val harness = Harness(readOpenCode = { if (fail) error("unreadable") else value })
        harness.warming.sync()
        value = true
        harness.warming.sync()
        assertEquals(1, harness.openCodeReads)
        assertFalse(harness.latest()["openCodeWarming"].asBoolean)
        harness.scheduler.advance(minute)
        harness.warming.sync()
        assertEquals(2, harness.openCodeReads)
        assertTrue(harness.latest()["openCodeWarming"].asBoolean)
        fail = true
        harness.scheduler.advance(minute)
        harness.warming.sync()
        assertTrue(harness.latest()["openCodeWarming"].asBoolean)
        harness.available = false
        harness.scheduler.advance(minute)
        harness.warming.sync()
        assertEquals(3, harness.openCodeReads)
    }

    @Test fun `forgets a deleted session`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.warming.handleServerEvent("session.deleted", Json.obj("info" to Json.obj("id" to "session-1")))
        assertFalse(harness.warming.isChosen("session-1"))
        assertEquals(emptyList<String>(), harness.saved)

        harness.warming.setSessionWarm("session-2", true)
        harness.warming.handleServerEvent("session.deleted", Json.obj("sessionID" to "session-2"))
        assertFalse(harness.warming.isChosen("session-2"))
    }

    @Test fun `persisted choices keep the latest fifty and snapshots match the webview shape`() {
        val harness = Harness(stored = (1..55).map { "session-$it" } + "bad\u0001id")
        assertFalse(harness.warming.isChosen("session-1"))
        assertFalse(harness.warming.isChosen("session-6"))
        assertTrue(harness.warming.isChosen("session-7"))
        assertTrue(harness.warming.isChosen("bad\u0001id"))
        val snapshot = harness.warming.snapshot()
        assertEquals(setOf("available", "mode", "durationMs", "openCodeWarming", "sessions", "learned"), snapshot.keySet())
        assertEquals(30 * minute, snapshot["durationMs"].asLong)
        assertEquals("chosen", snapshot["mode"].asString)
        assertEquals(JsonObject(), snapshot["learned"])
        assertEquals(49, snapshot.getAsJsonObject("sessions").size())
        assertFalse(snapshot.getAsJsonObject("sessions").has("bad\u0001id"))

        harness.warming.setSessionWarm("session-new", true)
        assertEquals(50, harness.saved!!.size)
        assertEquals("session-new", harness.saved!!.last())
        assertFalse(harness.warming.isChosen("session-7"))

        harness.mode = "off"
        assertEquals(JsonObject(), harness.warming.snapshot().getAsJsonObject("sessions"))
    }

    @Test fun `learned keys the webview would reject are left out of the snapshot`() {
        val harness = Harness()
        val longModel = "m".repeat(1100)
        harness.warming.recordHistory("session-1", listOf(1.0, 2.0, 3.0).map { PromptCacheObservation("openai", longModel, it, 100.0 * minute, true) })
        assertEquals(setOf("openai/*"), harness.learned().keySet())
    }

    @Test fun `leaving chosen mode and disposing stop pending pings`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.mode = "off"
        harness.warming.applySettings("chosen")
        assertEquals(0, harness.scheduler.pending())
        harness.mode = "chosen"
        harness.idle("session-1")
        assertEquals(1, harness.scheduler.pending())
        val posts = harness.posted.size
        harness.warming.dispose()
        harness.idle("session-1")
        harness.scheduler.advance(pingInterval)
        assertTrue(harness.generated.isEmpty())
        assertEquals(posts, harness.posted.size)
    }
}
