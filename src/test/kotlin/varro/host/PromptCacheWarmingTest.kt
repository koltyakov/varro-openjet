package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import varro.host.PromptCacheWarming.Companion.KEEP_ALIVE_PROMPT
import varro.host.PromptCacheWarming.Companion.PROMPT_CACHE_PING_INTERVAL_MS
import varro.protocol.Json

class PromptCacheWarmingTest {
    private val minute = 60_000L

    private class ManualScheduler(var now: Long) : PromptCacheWarming.Scheduler {
        private class Task(val due: Long, val order: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L

        override fun schedule(delayMs: Long, task: () -> Unit): PromptCacheWarming.Cancellable {
            val entry = Task(now + delayMs, sequence++, task)
            tasks.add(entry)
            return PromptCacheWarming.Cancellable { entry.cancelled = true }
        }

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                tasks.removeAll { it.cancelled }
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy({ it.due }, { it.order })) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.run()
            }
            now = target
        }

        fun pending() = tasks.count { !it.cancelled }
    }

    private class Harness(
        stored: List<String>? = null,
        val readOpenCode: () -> Boolean = { false },
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
        var openCodeReads = 0
        var onGenerate: (String, () -> Boolean) -> PromptCacheWarming.Usage = { _, _ -> PromptCacheWarming.Usage(100_000.0, 20.0) }
        val warming by lazy { PromptCacheWarming(this, scheduler) { it.run() } }

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
        override fun readOpenCodeWarming(): Boolean { openCodeReads++; return readOpenCode() }
        override fun writeOpenCodeWarming(value: JsonElement?) { writes.add(value) }
        override fun loadChosen() = saved ?: emptyList()
        override fun saveChosen(ids: List<String>) { saved = ids }
        override fun post(state: JsonObject) { posted.add(state) }
        override fun logWarning(message: String) { warnings.add(message) }
        override fun now() = scheduler.now

        fun latest() = posted.last()
        fun session(id: String) = latest().getAsJsonObject("sessions").getAsJsonObject(id)
        fun idle(id: String) = warming.handleServerEvent("session.status", status(id, "idle"))
        fun status(id: String, type: String) = Json.obj("sessionID" to id, "status" to Json.obj("type" to type))
    }

    @Test fun `pings a chosen session before its cache expires until the window ends`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        assertEquals(Json.parse("""{"warmUntil":null,"sentAt":null,"stopped":null}"""), harness.session("session-1"))

        harness.idle("session-1")
        assertEquals(1_000_000 + 30 * minute, harness.session("session-1")["warmUntil"].asLong)

        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS)
        assertEquals(listOf(Triple("session-1", "/workspace", KEEP_ALIVE_PROMPT)), harness.generated)

        harness.scheduler.advance(30 * minute)
        // Pings at 4, 8, ..., 28 minutes; the window closes at 30 without another request.
        assertEquals(7, harness.generated.size)
        assertEquals("expired", harness.session("session-1")["stopped"].asString)
        assertEquals(1_000_000 + 30 * minute, harness.session("session-1")["warmUntil"].asLong)
        assertEquals(1_000_000 + 28 * minute, harness.session("session-1")["sentAt"].asLong)
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
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS)
        assertTrue(harness.generated.isEmpty())

        // A new turn replaces the window; its end starts the next one.
        harness.warming.handleServerEvent("session.status", harness.status("session-1", "busy"))
        assertTrue(harness.session("session-1")["warmUntil"].isJsonNull)
        harness.busy.remove("session-1")
        harness.idle("session-1")
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS)
        assertEquals(1, harness.generated.size)
    }

    @Test fun `session idle events also start a window and retry stops it`() {
        val harness = Harness()
        harness.warming.setSessionWarm("session-1", true)
        harness.warming.handleServerEvent("session.idle", Json.obj("sessionID" to "session-1"))
        assertEquals(1_000_000L, harness.session("session-1")["sentAt"].asLong)
        harness.warming.handleServerEvent("session.status", harness.status("session-1", "retry"))
        assertTrue(harness.session("session-1")["sentAt"].isJsonNull)
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS * 2)
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
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS * 3)
        assertEquals(1, harness.generated.size)
        assertEquals("cache-miss", harness.session("session-1")["stopped"].asString)
        assertTrue(harness.session("session-1")["warmUntil"].isJsonNull)
    }

    @Test fun `a failed keep-alive stops warming and logs a warning`() {
        val harness = Harness()
        harness.onGenerate = { _, _ -> throw IllegalStateException("503 Unavailable") }
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS * 3)
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
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS)
        assertTrue(observedCancel)
        assertEquals(Json.parse("""{"warmUntil":null,"sentAt":null,"stopped":null}"""), harness.session("session-1"))
        assertTrue(harness.warnings.isEmpty())
    }

    @Test fun `defers to OpenCode warming and writes it for all sessions`() {
        val harness = Harness(readOpenCode = { true })
        harness.warming.setSessionWarm("session-1", true)
        harness.idle("session-1")
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS)
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
        assertEquals(setOf("available", "mode", "durationMs", "intervalMs", "openCodeWarming", "sessions"), snapshot.keySet())
        assertEquals(240_000L, snapshot["intervalMs"].asLong)
        assertEquals(30 * minute, snapshot["durationMs"].asLong)
        assertEquals("chosen", snapshot["mode"].asString)
        assertEquals(49, snapshot.getAsJsonObject("sessions").size())
        assertFalse(snapshot.getAsJsonObject("sessions").has("bad\u0001id"))

        harness.warming.setSessionWarm("session-new", true)
        assertEquals(50, harness.saved!!.size)
        assertEquals("session-new", harness.saved!!.last())
        assertFalse(harness.warming.isChosen("session-7"))

        harness.mode = "off"
        assertEquals(JsonObject(), harness.warming.snapshot().getAsJsonObject("sessions"))
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
        harness.scheduler.advance(PROMPT_CACHE_PING_INTERVAL_MS)
        assertTrue(harness.generated.isEmpty())
        assertEquals(posts, harness.posted.size)
    }
}
