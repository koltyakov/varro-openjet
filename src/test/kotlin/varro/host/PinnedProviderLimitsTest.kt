package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PinnedProviderLimitsTest {
    private val preferences = Json.obj("providerLimitPins" to Json.obj("openai" to "Codex", "anthropic" to ""),
        "providerOrder" to listOf("openai", "anthropic"))
    private val catalog = Json.obj("providers" to listOf(
        Json.obj("id" to "anthropic", "name" to "Anthropic", "models" to Json.obj("claude" to Json.obj())),
        Json.obj("id" to "openai", "name" to "OpenAI", "models" to Json.obj("gpt" to Json.obj())),
    ))
    private fun status(id: String, percent: Double = 20.0) = Json.obj("providerID" to id, "modelID" to null,
        "status" to "available", "windows" to listOf(window("five_hour", "5-hour", percent)))
    private fun window(id: String, label: String, used: Double?) = Json.obj("id" to id, "label" to label,
        "percent" to used, "remaining" to 80, "limit" to 100, "unit" to "percent")

    @Test fun `summary orders periods and shows remaining quota with bounds and unknown units`() {
        val value = status("openai").apply { add("windows", Json.array(listOf(
            window("monthly", "Monthly", 25.0), window("seven_day", "Weekly", 5.0), window("daily", "Daily", null),
            window("five_hour", "5-hour", 110.0), Json.obj("id" to "credits", "label" to "Credits", "remaining" to 7, "unit" to "unknown"),
        ))) }
        assertEquals("5h 0%, d 80%, w 95%, m 75%, Credits 7 left", PinnedProviderLimits.summary(value))
        assertEquals("", PinnedProviderLimits.summary(Json.obj("status" to "error")))
        assertEquals("", PinnedProviderLimits.summary(null))
    }

    @Test fun `polls are provider-wide scoped nonblocking and rotate without resetting on renders`() {
        var now = 0L
        val calls = AtomicInteger()
        PinnedProviderLimits({ directory -> assertEquals("/repo", directory); catalog }, { id, directory ->
            assertEquals("/repo", directory); calls.incrementAndGet(); status(id)
        }, { now }).use { limits ->
            fun tick(mode: String = "rotating", visible: Set<String> = emptySet(), prefs: JsonObject = preferences) =
                limits.tick("server", "/repo", prefs, visible, mode)
            await { tick().text == "Codex: 5h 80%" }
            assertEquals(2, calls.get())
            now = 14_999; assertEquals("Codex: 5h 80%", tick().text)
            // Unrelated model selections must not restart polling or rotation.
            val changed = preferences.deepCopy().apply { add("lastSelectedModel", Json.obj("providerID" to "openai", "modelID" to "other")) }
            now = 15_000; assertEquals("Anthropic: 5h 80%", tick(prefs = changed).text)
            assertEquals(2, calls.get())
            assertEquals("Codex: 5h 80% · Anthropic: 5h 80%", tick("all").text)
            assertEquals("Anthropic: 5h 80%", tick("all", setOf("openai")).text)
            assertEquals("", tick("all", setOf("openai", "anthropic")).text)
            assertTrue(tick().tooltip.contains("Percentages show remaining quota"))
            now = 120_000
            await { tick(); calls.get() == 4 }
        }
    }

    @Test fun `unavailable pins retry while hidden providers sort last and explicit order survives`() {
        val polls = AtomicInteger()
        var now = 0L
        PinnedProviderLimits({ catalog }, { id, _ ->
            if (polls.incrementAndGet() <= 2) Json.obj("providerID" to id, "status" to "unsupported") else status(id)
        }, { now }).use { limits ->
            val prefs = preferences.deepCopy().apply { add("hiddenProviders", Json.array(listOf("openai"))) }
            fun tick() = limits.tick("server", "/repo", prefs, emptySet(), "all")
            await { tick(); polls.get() == 2 }
            assertEquals("", tick().text)
            now = 120_000
            await { tick().text == "Anthropic: 5h 80% · Codex: 5h 80%" }
            assertEquals(4, polls.get())
        }
    }

    @Test fun `updates reject model-specific foreign and unpinned snapshots and disconnect clears values`() {
        PinnedProviderLimits({ catalog }, { id, _ -> status(id) }).use { limits ->
            fun tick(connection: String? = "server") = limits.tick(connection, "/repo", preferences, emptySet(), "all")
            await { tick().text.contains("Codex:") }
            fun update(directory: String, id: String, model: String? = null) = limits.acceptUpdate(Json.obj("directory" to directory,
                "status" to status(id, 90.0).apply { addProperty("modelID", model) }))
            update("/other", "openai"); update("/repo", "openai", "gpt"); update("/repo", "unknown")
            assertTrue(tick().text.contains("Codex: 5h 80%"))
            update("/repo", "openai")
            assertTrue(tick().text.contains("Codex: 5h 10%"))
            assertEquals("", tick(null).text)
            update("/repo", "openai")
            assertEquals("", tick(null).text)
        }
    }

    @Test fun `late catalog and quota completions cannot repopulate a replaced connection or disposed host`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        val limits = PinnedProviderLimits({
            if (count.incrementAndGet() == 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            catalog
        }, { id, _ -> status(id) })
        limits.tick("old", "/repo", preferences, emptySet(), "all")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals("", limits.tick(null, "/repo", preferences, emptySet(), "all").text)
        release.countDown()
        assertEquals("", limits.tick(null, "/repo", preferences, emptySet(), "all").text)
        limits.close()
        assertEquals("", limits.tick("new", "/repo", preferences, emptySet(), "all").text)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) { assertTrue("Pinned quotas did not settle", System.nanoTime() < deadline); Thread.sleep(5) }
    }
}
