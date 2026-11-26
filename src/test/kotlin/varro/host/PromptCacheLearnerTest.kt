package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class PromptCacheLearnerTest {
    private val minute = 60_000L

    private fun observations(modelID: String, gapMinutes: Int, results: List<Boolean>, firstAt: Int = 1) =
        results.mapIndexed { index, hit ->
            PromptCacheObservation("claude-agent", modelID, (firstAt + index).toDouble(), gapMinutes * minute.toDouble(), hit)
        }

    @Test fun `learns per model and per provider, and keeps it across restarts`() {
        val storage = PromptCacheLearner.Storage.inMemory()
        val learner = PromptCacheLearner(storage)
        assertTrue(learner.record("ses_1", observations("opus", 40, listOf(true, true, true))))
        assertTrue(learner.record("ses_2", observations("fable", 70, listOf(false, false, false))))
        assertEquals(
            mapOf(
                "claude-agent/opus" to LearnedPromptCacheLifetime(45 * minute, false),
                "claude-agent/fable" to LearnedPromptCacheLifetime(60 * minute, true),
                "claude-agent/*" to LearnedPromptCacheLifetime(45 * minute, true),
            ),
            learner.snapshot(),
        )
        assertEquals(learner.snapshot(), PromptCacheLearner(storage).snapshot())
        assertEquals(Json.parse("""{"ses_1":3,"ses_2":3}"""), storage.load()!!.asJsonObject["watermarks"])
    }

    @Test fun `counts each session observation once, even when its history is sent again`() {
        val learner = PromptCacheLearner()
        learner.record("ses_1", observations("opus", 40, listOf(true, true), 10))
        assertFalse(learner.record("ses_1", observations("opus", 40, listOf(true, true), 10)))
        assertEquals(emptyMap<String, LearnedPromptCacheLifetime>(), learner.snapshot())
        // A later request in the same session still counts.
        assertTrue(learner.record("ses_1", observations("opus", 40, listOf(true), 20)))
        assertEquals(LearnedPromptCacheLifetime(45 * minute, false), learner.snapshot()["claude-agent/opus"])
    }

    @Test fun `ignores malformed stored state`() {
        val learner = PromptCacheLearner(PromptCacheLearner.Storage.inMemory(Json.parse(
            """{"histograms":{"claude-agent/opus":[[1,2]]},"watermarks":{"ses_1":"late"}}""",
        )))
        assertEquals(emptyMap<String, LearnedPromptCacheLifetime>(), learner.snapshot())
        assertTrue(learner.record("ses_1", observations("opus", 40, listOf(true))))
        assertNull(PromptCacheLearner(PromptCacheLearner.Storage.inMemory(Json.parse("[]"))).snapshot()["x"])
    }

    @Test fun `keeps the most recently used models and sessions`() {
        val storage = PromptCacheLearner.Storage.inMemory()
        val learner = PromptCacheLearner(storage)
        for (index in 0 until 101) learner.record("ses_$index", observations("model-$index", 40, listOf(true)))
        for (index in 101 until 501) learner.record("ses_$index", observations("model-100", 40, listOf(true)))
        val stored = storage.load()!!.asJsonObject
        assertEquals(100, stored.getAsJsonObject("histograms").size())
        assertFalse(stored.getAsJsonObject("histograms").has("claude-agent/model-0"))
        assertTrue(stored.getAsJsonObject("histograms").has("claude-agent/model-1"))
        assertEquals(500, stored.getAsJsonObject("watermarks").size())
        assertFalse(stored.getAsJsonObject("watermarks").has("ses_0"))
        assertTrue(stored.getAsJsonObject("watermarks").has("ses_500"))
    }

    @Test fun `snapshots are cached until something is recorded`() {
        val learner = PromptCacheLearner()
        learner.record("ses_1", observations("opus", 40, listOf(true, true, true)))
        val first = learner.snapshot()
        assertSame(first, learner.snapshot())
        learner.record("ses_1", observations("opus", 40, listOf(true), 10))
        assertNotSame(first, learner.snapshot())
    }
}
