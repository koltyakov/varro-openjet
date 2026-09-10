package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.Json
import varro.server.OpenCodeResponse
import java.nio.file.Path
import java.sql.DriverManager

class UsageReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L

    @Test
    fun `totals use recorded token total and sum durations while deduplicating prompts`() {
        val first = message().apply {
            getAsJsonObject("tokens").addProperty("total", 12_345)
            getAsJsonObject("time").addProperty("created", now - 3_660_000)
        }
        val total = UsageReport.Total().apply {
            add("s", first)
            merge(UsageReport.Total().apply { add("s", message()) })
        }
        assertEquals("| provider | model | 1 | 12,513 | 1h 1m | 200 | 40 | 6 | 80 | 10 |", total.row("provider", "model"))
    }

    @Test
    fun `benchmark explicitly selected local database read only`() {
        val selected = System.getenv("VARRO_USAGE_BENCHMARK_DB")
        org.junit.Assume.assumeTrue("Set VARRO_USAGE_BENCHMARK_DB to run the read-only benchmark", selected != null)
        val report = UsageReport(Path.of(selected!!), { error("Server must not start") }) { _, _ -> error("API must not be called") }
        for (allTime in listOf(false, true)) {
            val start = System.nanoTime()
            val content = report.build(allTime)
            println("Local usage includeAllTime=$allTime: ${(System.nanoTime() - start) / 1_000_000} ms; ${content.lineSequence().first { it.startsWith("Generated") }}")
        }
    }

    @Test
    fun `local report matches REST without starting server or requesting history`() {
        val path = fixture()
        val local = UsageReport(path, { error("Server must not start") }) { _, _ -> error("API must not be called") }
            .build(true, now)
        var calls = 0
        var starts = 0
        val remote = UsageReport(temporary.root.toPath().resolve("missing.db"), { starts++ }) { route, _ ->
            calls++
            OpenCodeResponse(if (route.startsWith("/experimental")) Json.array(listOf(Json.obj("id" to "s", "directory" to "/test")))
                else Json.array(listOf(Json.obj("info" to message()))))
        }.build(true, now)
        assertEquals(remote, local)
        assertEquals(1, starts)
        assertEquals(2, calls)
        assertTrue(local.contains("| provider | model | 1 | 168 | 1s | 100 | 20 | 3 | 40 | 5 |"))
    }

    @Test
    fun `recent report excludes stale sessions and all time includes them`() {
        val path = fixture(now - 40 * 86_400_000L)
        val report = UsageReport(path) { _, _ -> error("API must not be called") }
        assertTrue(report.build(false, now).contains("0 sessions scanned"))
        assertTrue(report.build(true, now).contains("1 sessions scanned"))
    }

    @Test
    fun `database errors do not trigger expensive API fallback`() {
        val path = temporary.newFile("broken.db").toPath()
        assertThrows(java.sql.SQLException::class.java) {
            UsageReport(path) { _, _ -> error("API must not be called") }.build(false, now)
        }
    }

    @Test
    fun `local scan honors cancellation`() {
        val path = fixture()
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            UsageReport(path) { _, _ -> error("API must not be called") }.build(false, now) {
                throw com.intellij.openapi.progress.ProcessCanceledException()
            }
        }
    }

    @Test
    fun `missing database fallback refuses bulk history downloads`() {
        var calls = 0
        val report = UsageReport(temporary.root.toPath().resolve("missing.db")) { _, _ ->
            calls++
            OpenCodeResponse(Json.array((1..251).map { Json.obj("id" to "s$it", "directory" to "/test") }))
        }
        assertThrows(IllegalStateException::class.java) { report.build(false, now) }
        assertEquals(1, calls)
    }

    @Test
    fun `SQLite scan can be cancelled before it returns matching messages`() {
        val path = fixture()
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use {
                it.execute("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10000) " +
                    "INSERT INTO message SELECT 'user' || x, 's', '{\"role\":\"user\"}' FROM n")
            }
        }
        var checks = 0
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            UsageReport(path) { _, _ -> error("API must not be called") }.build(false, now) {
                if (++checks >= 4) throw com.intellij.openapi.progress.ProcessCanceledException()
            }
        }
    }

    @Test
    fun `data directory follows XDG and default on all platforms`() {
        assertEquals(Path.of("/data/opencode/opencode.db"), LocalUsageDatabase.defaultPath(mapOf("XDG_DATA_HOME" to " /data "), "/home"))
        assertEquals(Path.of("/home/.local/share/opencode/opencode.db"), LocalUsageDatabase.defaultPath(emptyMap(), "/home"))
    }

    private fun message() = Json.obj(
        "id" to "m", "role" to "assistant", "providerID" to "provider", "modelID" to "model", "parentID" to "p",
        "time" to Json.obj("created" to now - 1000, "completed" to now),
        "tokens" to Json.obj("input" to 100, "output" to 20, "reasoning" to 3, "cache" to Json.obj("read" to 40, "write" to 5)),
        "cost" to 0.25,
    )

    private fun fixture(updated: Long = now): Path {
        val path = temporary.root.toPath().resolve("usage.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use {
                it.execute("CREATE TABLE session (id TEXT PRIMARY KEY, time_updated INTEGER)")
                it.execute("CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT, data TEXT)")
                it.execute("INSERT INTO session VALUES ('s', $updated)")
            }
            database.prepareStatement("INSERT INTO message VALUES ('m', 's', ?)").use {
                it.setString(1, message().toString())
                it.executeUpdate()
            }
        }
        return path
    }
}
