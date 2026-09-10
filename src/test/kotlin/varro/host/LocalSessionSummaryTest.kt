package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.long
import varro.protocol.obj
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
                    Triple("assistant", "root", """{"role":"assistant","time":{"created":1100,"completed":3000},"tokens":{"input":100,"output":20,"cache":{"read":1000}}}"""),
                    Triple("child-message", "child", """{"role":"assistant","tokens":{"input":9000}}"""),
                    Triple("grandchild-message", "grandchild", """{"role":"assistant","tokens":{"input":7}}"""),
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
        assertFalse(history.messages.toString().contains("ignored output"))
        assertNull(LocalSessionSummary(path).read("missing"))
    }

    @Test
    fun `missing and incompatible databases allow remote fallback`() {
        assertNull(LocalSessionSummary(temporary.root.toPath().resolve("missing.db")).read("root"))
        assertNull(LocalSessionSummary(temporary.newFile("empty.db").toPath()).read("root"))
    }
}
