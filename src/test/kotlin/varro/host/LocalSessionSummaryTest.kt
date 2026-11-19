package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.long
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.Json
import varro.protocol.arr
import varro.protocol.str
import java.sql.DriverManager

class LocalSessionSummaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `SQLite history restores file changes tokens and nested subagents`() {
        val path = temporary.newFile("opencode.db").toPath()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use { sql ->
                sql.execute("CREATE TABLE session (id TEXT PRIMARY KEY, parent_id TEXT, tokens_input INTEGER, tokens_output INTEGER, tokens_reasoning INTEGER, tokens_cache_read INTEGER, tokens_cache_write INTEGER)")
                sql.execute("CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT, time_created INTEGER, data TEXT)")
                sql.execute("CREATE TABLE part (id TEXT PRIMARY KEY, message_id TEXT, session_id TEXT, data TEXT)")
                sql.execute("INSERT INTO session VALUES ('root', NULL, 9999, 0, 0, 0, 0), ('child', 'root', 30, 5, 0, 100, 0), ('grandchild', 'child', 0, 0, 0, 0, 0), ('unrelated', NULL, 9999, 0, 0, 0, 0)")
            }
            database.prepareStatement("INSERT INTO message VALUES (?, ?, ?, ?)").use { statement ->
                val rows = listOf(
                    Triple("user", "root", """{"role":"user","time":{"created":1000}}"""),
                    Triple("assistant", "root", """{"role":"assistant","cost":0.25,"time":{"created":1100,"completed":3000},"tokens":{"input":100,"output":20,"cache":{"read":1000}}}"""),
                    Triple("child-message", "child", """{"role":"assistant","cost":0.125,"tokens":{"input":9000}}"""),
                    Triple("grandchild-message", "grandchild", """{"role":"assistant","cost":0.0625,"tokens":{"input":7}}"""),
                )
                rows.forEachIndexed { index, (id, session, data) ->
                    statement.setString(1, id); statement.setString(2, session)
                    statement.setLong(3, index.toLong()); statement.setString(4, data); statement.executeUpdate()
                }
            }
            database.prepareStatement("INSERT INTO part VALUES (?, ?, ?, ?)").use { statement ->
                listOf(
                    """{"type":"tool","tool":"edit","state":{"input":{"filePath":"src/a.kt"},"metadata":{"linesAdded":12,"linesRemoved":3},"output":"ignored output"}}""",
                    """{"type":"patch","files":["/workspace/src/a.kt","src/b.kt"]}""",
                ).forEachIndexed { index, data ->
                    statement.setString(1, "part-$index"); statement.setString(2, "assistant")
                    statement.setString(3, "root"); statement.setString(4, data); statement.executeUpdate()
                }
            }
        }
        val history = LocalSessionSummary(path).read("root")!!
        val result = SessionSummary.summarize(history)
        assertEquals(2L, result.long("files"))
        assertEquals(12L, result.long("additions"))
        assertEquals(3L, result.long("deletions"))
        assertEquals(162L, result.long("tokens"))
        assertEquals(2000L, result.long("durationMs"))
        assertEquals(2L, result.obj("tokenBreakdown").long("subagentCount"))
        assertEquals(0.25, result.obj("tokenBreakdown").obj("session").num("cost")!!, 0.0)
        assertEquals(0.1875, result.obj("tokenBreakdown").obj("subagents").num("cost")!!, 0.0)
        assertFalse(history.messages.toString().contains("ignored output"))
        assertNull(LocalSessionSummary(path).read("missing"))
    }

    @Test
    fun `missing and incompatible databases allow remote fallback`() {
        assertNull(LocalSessionSummary(temporary.root.toPath().resolve("missing.db")).read("root"))
        assertNull(LocalSessionSummary(temporary.newFile("empty.db").toPath()).read("root"))
    }

    private fun v2Database(): java.nio.file.Path {
        val path = temporary.newFile().toPath()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use { sql ->
                sql.execute("CREATE TABLE session_v2(id TEXT PRIMARY KEY,parent_id TEXT,fork_session_id TEXT,revert TEXT,tokens_input INTEGER,tokens_output INTEGER,tokens_reasoning INTEGER,tokens_cache_read INTEGER,tokens_cache_write INTEGER)")
                sql.execute("CREATE TABLE session_message(id TEXT PRIMARY KEY,session_id TEXT,type TEXT,seq INTEGER,data TEXT)")
                sql.execute("CREATE TABLE session_part(message_id TEXT,session_id TEXT,ordinal INTEGER,type TEXT,data TEXT)")
                sql.execute("INSERT INTO session_v2 VALUES('root',NULL,NULL,NULL,100,20,5,10,2),('child','root',NULL,NULL,30,4,0,0,0)")
            }
        }
        return path
    }

    @Test fun `native V2 inline stored and mixed summaries retain order and stats without retaining tool output`() {
        for (storage in listOf("inline", "stored", "mixed")) {
            val path = v2Database()
            val output = "private output".repeat(1000)
            val tools = listOf(
                Json.obj("type" to "tool", "name" to "edit", "state" to Json.obj("status" to "completed",
                    "input" to Json.obj("filePath" to "src/one.kt"), "metadata" to Json.obj("additions" to 3, "deletions" to 1,
                        "files" to listOf(Json.obj("file" to "src/one.kt", "additions" to 3, "deletions" to 1,
                            "before" to output, "after" to output, "diff" to output))),
                    "content" to listOf(Json.obj("type" to "text", "text" to output)))),
                Json.obj("type" to "tool", "name" to "write", "state" to Json.obj("status" to "completed",
                    "input" to Json.obj("filePath" to "src/two.kt"), "content" to listOf(Json.obj("type" to "text", "text" to output)))),
            )
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                val storedCount = when (storage) { "inline" -> 0; "mixed" -> 1; else -> 2 }
                val messages = listOf(
                    listOf("prompt", "root", "user", 1, Json.obj("text" to "Review", "time" to Json.obj("created" to 1000))),
                    listOf("answer", "root", "assistant", 2, Json.obj("time" to Json.obj("created" to 1100, "completed" to 3000),
                        "model" to Json.obj("providerID" to "fixture", "id" to "model"), "cost" to 0.5,
                        "tokens" to Json.obj("input" to 100, "output" to 20, "reasoning" to 5, "cache" to Json.obj("read" to 10, "write" to 2)),
                        "content" to tools.drop(storedCount))),
                    listOf("child-answer", "child", "assistant", 1, Json.obj("tokens" to Json.obj("input" to 30, "output" to 4))),
                )
                database.prepareStatement("INSERT INTO session_message VALUES(?,?,?,?,?)").use { statement ->
                    messages.forEach { row ->
                        row.forEachIndexed { index, value -> statement.setObject(index + 1, if (value is com.google.gson.JsonElement) value.toString() else value) }
                        statement.executeUpdate()
                    }
                }
                database.prepareStatement("INSERT INTO session_part VALUES(?,?,?,?,?)").use { statement ->
                    // Reverse insertion order must not change hydration order.
                    tools.take(storedCount).withIndex().reversed().forEach { (index, value) ->
                        statement.setString(1, "answer"); statement.setString(2, "root"); statement.setInt(3, index * 2)
                        statement.setString(4, "tool"); statement.setString(5, value.toString()); statement.executeUpdate()
                    }
                }
            }
            val before = java.nio.file.Files.readAllBytes(path)
            val history = LocalSessionSummary(path).read("root", 2)!!
            val result = SessionSummary.summarize(history)
            assertEquals(storage, 2L, result.long("files"))
            assertEquals(3L, result.long("additions"))
            assertEquals(1L, result.long("deletions"))
            assertEquals(161L, result.long("tokens"))
            assertEquals(2000L, result.long("durationMs"))
            assertEquals("model", result.obj("model").str("modelID"))
            assertEquals(0.5, result.obj("tokenBreakdown").obj("session").num("cost")!!, 0.0)
            assertEquals(listOf("edit", "write"), history.messages.last().arr("parts")!!.map { it.asJsonObject.str("tool") })
            assertEquals(6L, history.contextCharacters.long("user"))
            assertEquals(32L + output.length * 2L, history.contextCharacters.long("tool"))
            assertEquals(100.0, history.contextInputTokens!!, 0.0)
            val breakdown = result.arr("nestedContextBreakdown")!!
            assertEquals(130L, breakdown.sumOf { it.asJsonObject.long("tokens")!! })
            assertTrue(breakdown.any { it.asJsonObject.str("key") == "tool" })
            assertFalse(history.toString().contains("private output"))
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(path))
            if (storage == "mixed") assertNull(LocalSessionSummary(path).read("root", 2, mixedV2Content = false))
            assertNull(LocalSessionSummary(path).read("root", 1))
        }
    }

    @Test fun `ambiguous or malformed V2 data falls back without choosing a V1 namesake`() {
        for (scenario in listOf("fork", "revert", "orphan", "owner", "ordinal", "fractional", "duplicate", "type", "text", "tool", "json", "schema", "bytes", "parts")) {
            val path = v2Database()
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { sql ->
                    sql.execute("INSERT INTO session_message VALUES('answer','root','assistant',1,'{\"content\":[]}')")
                    when (scenario) {
                        "fork" -> sql.execute("UPDATE session_v2 SET fork_session_id='source' WHERE id='child'")
                        "revert" -> sql.execute("UPDATE session_v2 SET revert='{}' WHERE id='root'")
                        "schema" -> sql.execute("ALTER TABLE session_part RENAME COLUMN ordinal TO unsupported")
                        "bytes" -> sql.execute("UPDATE session_message SET data=json_object('padding',hex(zeroblob(33554433)))")
                        "parts" -> sql.execute("WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM n WHERE x<250000) INSERT INTO session_part SELECT 'answer','root',x,'text','{\"type\":\"text\",\"text\":\"x\"}' FROM n")
                        else -> {
                            val message = if (scenario == "orphan") "missing" else "answer"
                            val session = if (scenario == "owner") "child" else "root"
                            val ordinal = when (scenario) { "ordinal" -> "-1"; "fractional" -> "0.5"; else -> "0" }
                            val type = if (scenario == "type") "unknown" else if (scenario == "tool") "tool" else "text"
                            val data = if (scenario == "text") "{\"type\":\"text\",\"text\":5}" else if (scenario == "tool") "{\"type\":\"tool\",\"name\":\"edit\",\"state\":[]}" else if (scenario == "json") "[]" else "{\"type\":\"$type\",\"text\":\"x\"}"
                            sql.execute("INSERT INTO session_part VALUES('$message','$session',$ordinal,'$type','$data')")
                            if (scenario == "duplicate") sql.execute("INSERT INTO session_part VALUES('$message','$session',$ordinal,'$type','$data')")
                        }
                    }
                }
            }
            assertNull(scenario, LocalSessionSummary(path).read("root", 2))
        }
        val path = v2Database()
        assertNull(LocalSessionSummary(path).read("missing", 2))
    }
}
