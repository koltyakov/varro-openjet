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
    fun `local and remote v2 usage cap paused responses and retain resumed work`() {
        val path = temporary.newFile("paused.db").toPath()
        val metadata = Json.obj("varro" to Json.obj("pauses" to listOf(Json.obj("messageId" to "m", "pausedAt" to now - 3_600_000))))
        val state = varro.server.OpenCodeV2SessionState(temporary.newFolder("annotations").toPath())
        state.update("s", Json.obj("metadata" to metadata))
        val paused = message().apply { getAsJsonObject("time").addProperty("created", now - 3_610_000) }
        val resumed = message().apply {
            addProperty("id", "resumed"); addProperty("parentID", "new-prompt")
            getAsJsonObject("time").addProperty("created", now - 5_000)
        }
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use {
                it.execute("CREATE TABLE session_v2(id TEXT PRIMARY KEY,metadata TEXT,time_updated INTEGER)")
                it.execute("CREATE TABLE session_message(id TEXT PRIMARY KEY,session_id TEXT,type TEXT,seq INTEGER,data TEXT)")
                it.execute("INSERT INTO session_v2 VALUES('s','{}',$now)")
            }
            database.prepareStatement("INSERT INTO session_message VALUES(?,'s','assistant',?,?)").use { statement ->
                listOf(paused, resumed).forEachIndexed { index, info ->
                    statement.setString(1, info.get("id").asString)
                    statement.setInt(2, index)
                    statement.setString(3, info.toString())
                    statement.executeUpdate()
                }
            }
        }
        val local = UsageReport(path, readAnnotations = state::read) { _, _ -> error("No API requests expected") }.build(true, now)
        val remote = UsageReport(path, attachOnly = true, readAnnotations = { error("No local annotations expected") }) { route, _ ->
            OpenCodeResponse(if (route.startsWith("/experimental")) Json.array(listOf(Json.obj("id" to "s", "metadata" to metadata)))
                else Json.array(listOf(Json.obj("info" to paused), Json.obj("info" to resumed))))
        }.build(true, now)
        assertEquals(remote, local)
        assertTrue(local, local.contains("| provider | model | 2 | 336 | 15s |"))
        assertEquals(now, paused.getAsJsonObject("time").get("completed").asLong)
    }

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
    fun `attach only report ignores unrelated local history`() {
        var starts = 0
        var calls = 0
        val report = UsageReport(fixture(), { starts++ }, attachOnly = true) { route, options ->
            calls++
            assertTrue(route.startsWith("/experimental/session"))
            assertTrue(options.unscoped)
            OpenCodeResponse(Json.array(emptyList<Any>()))
        }.build(true, now)
        assertEquals(1, starts)
        assertEquals(1, calls)
        assertTrue(report.contains("0 sessions scanned"))
        assertFalse(report.contains("| provider | model |"))
    }

    @Test
    fun `attach only report explains API history limit`() {
        val report = UsageReport(fixture(), attachOnly = true) { _, _ ->
            OpenCodeResponse(Json.array((1..251).map { Json.obj("id" to "remote-$it") }))
        }
        val failure = assertThrows(IllegalStateException::class.java) { report.build(true, now) }
        assertTrue(failure.message!!.contains("attach-only mode support up to 250 sessions"))
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
        assertEquals(Path.of("/custom/history.db"), LocalUsageDatabase.defaultPath(mapOf("OPENCODE_DB" to "/custom/history.db"), "/home"))
        assertEquals(Path.of("/data/opencode/opencode.db"), LocalUsageDatabase.defaultPath(mapOf("XDG_DATA_HOME" to " /data "), "/home"))
        assertEquals(Path.of("/home/.local/share/opencode/opencode.db"), LocalUsageDatabase.defaultPath(emptyMap(), "/home"))
    }

    @Test fun `v2 import replaces original usage instead of double counting`() {
        val path = fixture()
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use {
                it.execute("CREATE TABLE session_v2(id TEXT PRIMARY KEY,metadata TEXT,time_updated INTEGER)")
                it.execute("CREATE TABLE session_message(id TEXT PRIMARY KEY,session_id TEXT,type TEXT,seq INTEGER,data TEXT)")
                it.execute("INSERT INTO session_v2 VALUES('copy','{\"varroLegacyImport\":{\"sourceSessionID\":\"s\"}}',$now)")
                it.execute("INSERT INTO session_message VALUES('user','copy','user',1,'{}')")
            }
            val native = message().apply {
                remove("providerID"); remove("modelID"); remove("parentID"); remove("role")
                add("model", Json.obj("providerID" to "native", "id" to "v2-model"))
            }
            database.prepareStatement("INSERT INTO session_message VALUES('assistant','copy','assistant',2,?)").use {
                it.setString(1, native.toString()); it.executeUpdate()
            }
        }
        val report = UsageReport(path) { _, _ -> error("No REST requests expected") }.build(true, now)
        assertTrue(report.contains("1 sessions scanned"))
        assertTrue(report.contains("| native | v2-model | 1 | 168 |"))
        assertFalse(report.contains("| provider | model |"))
    }

    @Test fun `migrated completion time uses matching original identity without reverting token usage`() {
        val path = fixture()
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use {
                it.execute("CREATE TABLE session_v2(id TEXT PRIMARY KEY,metadata TEXT,time_updated INTEGER)")
                it.execute("CREATE TABLE session_message(id TEXT PRIMARY KEY,session_id TEXT,type TEXT,seq INTEGER,data TEXT)")
                it.execute("INSERT INTO session_v2 VALUES('copy','{\"varroLegacyImport\":{\"sourceSessionID\":\"s\"}}',$now)")
            }
            val native = message().apply {
                remove("providerID"); remove("modelID")
                add("model", Json.obj("providerID" to "provider", "id" to "model"))
                getAsJsonObject("time").addProperty("completed", now + 86_400_000)
                getAsJsonObject("tokens").addProperty("input", 200)
            }
            database.prepareStatement("INSERT INTO session_message VALUES('m','copy','assistant',1,?)").use {
                it.setString(1, native.toString()); it.executeUpdate()
            }
            val report = UsageReport(path) { _, _ -> error("No REST requests expected") }.build(true, now)
            assertTrue(report, report.contains("| provider | model | 1 | 268 | 1s | 200 |"))
            // An unrelated message with the same ID must not supply the completion time.
            database.createStatement().use {
                it.execute("UPDATE message SET data=json_set(data,'$.providerID','different')")
            }
            val records = mutableListOf<com.google.gson.JsonObject>()
            LocalUsageDatabase(path).read(null, {}) { _, value -> records.add(value) }
            assertEquals(now + 86_400_000, records.single().getAsJsonObject("time").get("completed").asLong)
        }
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
