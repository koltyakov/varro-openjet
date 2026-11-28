package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.obj

class PromptCacheObserverTest {
    private val minute = 60_000L
    private val learner = PromptCacheLearner()
    private val posted = mutableListOf<JsonObject>()
    private var clock = 1_000_000L
    private var warming = false
    private val observer = PromptCacheObserver(learner, { warming }, { posted.add(it) }) { clock }

    private fun step(gapMinutes: Int, read: Long) {
        clock += gapMinutes * minute
        observer.handleServerEvent("session.next.step.started", Json.obj(
            "sessionID" to "ses_live", "timestamp" to clock,
            "model" to Json.obj("providerID" to "anthropic", "id" to "claude-opus"),
        ))
        observer.handleServerEvent("session.next.step.ended", Json.obj(
            "sessionID" to "ses_live",
            "tokens" to Json.obj("input" to 60_000 - read, "cache" to Json.obj("read" to read, "write" to 0)),
        ))
    }

    @Test fun `learns from real idle gaps but ignores the first step after compaction`() {
        step(0, 60_000)
        observer.handleServerEvent("session.next.compaction.ended", Json.obj("sessionID" to "ses_live"))
        repeat(3) { step(7, 0) }
        assertTrue(learner.snapshot().isEmpty())
        step(7, 0)
        assertEquals(Json.obj("ms" to 5 * minute, "bounded" to true), posted.last().obj("learned").obj("anthropic/claude-opus"))
    }

    @Test fun `ignores live and historical samples while OpenCode warms the cache`() {
        warming = true
        observer.sync()
        step(0, 60_000)
        repeat(3) { step(20, 60_000) }
        val history = (1..3).map { PromptCacheObservation("openai", "gpt", it.toDouble(), 100.0 * minute, true) }
        observer.recordHistory("ses_history", history)
        assertEquals(listOf(Json.obj("learned" to Json.obj())), posted)

        warming = false
        observer.sync()
        observer.recordHistory("ses_history", history)
        observer.recordHistory("ses_history", history)
        assertEquals(3, posted.size)
        assertEquals(Json.obj("ms" to 120 * minute, "bounded" to false), posted.last().obj("learned").obj("openai/gpt"))
    }

    @Test fun `rejects malformed history samples as a batch`() {
        val sample = Json.obj("providerID" to "openai", "modelID" to "gpt", "at" to 1, "gapMs" to minute, "hit" to true)
        fun payload() = Json.obj("sessionId" to "ses_history", "samples" to listOf(sample))
        assertEquals("ses_history" to listOf(PromptCacheObservation("openai", "gpt", 1.0, minute.toDouble(), true)),
            PromptCacheObserver.parseHistorySamples(payload()))
        assertNull(PromptCacheObserver.parseHistorySamples(payload().apply { addProperty("sessionId", "../session") }))
        sample.addProperty("hit", "true")
        assertNull(PromptCacheObserver.parseHistorySamples(payload()))
    }
}
