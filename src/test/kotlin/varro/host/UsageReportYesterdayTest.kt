package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import varro.server.OpenCodeResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class UsageReportYesterdayTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `Yesterday uses local calendar boundaries including DST for local and API usage on both backends`() {
        val originalZone = TimeZone.getDefault()
        try {
            for ((date, hours) in listOf(LocalDate.of(2026, 3, 9) to 23L, LocalDate.of(2026, 11, 2) to 25L, LocalDate.of(2026, 11, 22) to 24L)) {
                val zone = ZoneId.of("America/New_York")
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val yesterday = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val now = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                assertEquals(hours, (midnight - yesterday) / 3_600_000)
                for (version in listOf(1, 2)) {
                    val fixture = HistoryFixture(temporary.newFolder().toPath().resolve("history.db"))
                    val session = fixture.session("one", version = version, updated = now)
                    val messages = listOf("before" to yesterday - 1, "start" to yesterday, "end" to midnight - 1, "today" to midnight).mapIndexed { index, (model, completed) ->
                        Json.obj("id" to model, "role" to "assistant", "providerID" to "provider", "modelID" to model,
                            "parentID" to "prompt-$model", "time" to Json.obj("created" to completed - 2000, "completed" to completed),
                            "tokens" to Json.obj("input" to 10, "output" to 5)).also {
                            fixture.message(session, model, "assistant", it, index + 1)
                        }
                    }
                    fun remote(allTime: Boolean) = UsageReport(fixture.path, attachOnly = true, apiVersion = { version }) { path, _ ->
                        OpenCodeResponse(when {
                            path.startsWith("/experimental/session") -> Json.array(listOf(Json.obj("id" to session.id)))
                            path.contains("/log?") -> Json.obj("data" to emptyList<Any>())
                            else -> Json.array(messages.map { Json.obj("info" to it) })
                        })
                    }.build(allTime, now)
                    for (allTime in listOf(false, true)) {
                        val local = UsageReport(fixture.path, readAnnotations = { Json.obj() }, apiVersion = { version }) { _, _ -> error("No API fallback") }.build(allTime, now)
                        assertEquals(remote(allTime), local)
                        val section = local.substringAfter("## Yesterday\n").substringBefore("\n## ")
                        assertTrue(section, section.contains("| provider | start | 1 | 15 | 2s |"))
                        assertTrue(section, section.contains("| provider | end | 1 | 15 | 2s |"))
                        assertTrue(section, section.contains("| **Total** |  | 2 | 30 | 4s |"))
                        assertFalse(section.contains("| before |")); assertFalse(section.contains("| today |"))
                        assertEquals(listOf("Today", "Yesterday", "Last 7 rolling days", "Last 30 rolling days") +
                            if (allTime) listOf("All time") else emptyList<String>(),
                            Regex("^## (.+)$", RegexOption.MULTILINE).findAll(local).map { it.groupValues[1] }.toList())
                    }
                }
            }
        } finally { TimeZone.setDefault(originalZone) }
    }
}
