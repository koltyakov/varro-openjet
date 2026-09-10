package varro.host

import com.google.gson.JsonObject
import org.sqlite.SQLiteConfig
import org.sqlite.ProgressHandler
import varro.protocol.Json
import varro.protocol.arr
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** Reads the session tree through indexed session_id queries, like Varro's local summary worker. */
internal class LocalSessionSummary(private val path: Path) {
    fun read(sessionId: String): SessionSummary.History? {
        if (!Files.exists(path)) return null
        return runCatching {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            fun checkDeadline() = check(System.nanoTime() < deadline) { "Local session summary timed out" }
            val config = SQLiteConfig().apply { setReadOnly(true); setBusyTimeout(1_000) }
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties()).use { database ->
                ProgressHandler.setHandler(database, 1_000, object : ProgressHandler() {
                    override fun progress(): Int = if (System.nanoTime() < deadline) 0 else 1
                })
                val sessions = linkedMapOf<String, JsonObject>()
                database.prepareStatement(
                    "WITH RECURSIVE tree(id) AS (SELECT id FROM session WHERE id = ? " +
                        "UNION SELECT s.id FROM session s JOIN tree ON s.parent_id = tree.id) " +
                        "SELECT s.id, s.tokens_input, s.tokens_output, s.tokens_reasoning, " +
                        "s.tokens_cache_read, s.tokens_cache_write FROM session s JOIN tree ON s.id = tree.id LIMIT 10001",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            checkDeadline()
                            check(sessions.size < 10_000)
                            sessions[rows.getString(1)] = Json.obj(
                                "input" to rows.getLong(2), "output" to rows.getLong(3), "reasoning" to rows.getLong(4),
                                "cache" to Json.obj("read" to rows.getLong(5), "write" to rows.getLong(6)),
                            )
                        }
                    }
                }
                if (sessions.isEmpty()) return null
                val messages = linkedMapOf<String, JsonObject>()
                val bySession = sessions.keys.associateWith { mutableListOf<JsonObject>() }
                val placeholders = sessions.keys.joinToString(",") { "?" }
                var bytes = 0L
                fun parse(data: String): JsonObject {
                    checkDeadline()
                    val size = data.toByteArray(Charsets.UTF_8).size
                    bytes += size
                    check(size <= 64 * 1024 * 1024 && bytes <= 128 * 1024 * 1024)
                    return Json.parse(data).asJsonObject
                }
                val fields = listOf("role", "parentID", "mode", "providerID", "modelID", "variant", "time", "tokens", "summary")
                    .joinToString(", ") { "'$it', json_extract(data, '$.$it')" }
                database.prepareStatement(
                    "SELECT id, session_id, json_object($fields) FROM message WHERE session_id IN ($placeholders) " +
                        "ORDER BY time_created, id LIMIT 100001",
                ).use { statement ->
                    sessions.keys.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            check(messages.size < 100_000)
                            val message = Json.obj("info" to parse(rows.getString(3)), "parts" to Json.array(emptyList<Any>()))
                            messages[rows.getString(1)] = message
                            bySession.getValue(rows.getString(2)).add(message)
                        }
                    }
                }
                // Only root parts contribute file changes. Omit tool output and file contents in SQL.
                val fileKeys = listOf("relativePath", "file", "path", "filePath", "filepath", "filename",
                    "additions", "deletions", "linesAdded", "linesRemoved", "files")
                fun source(name: String) = fileKeys.joinToString(", ") { "'$it', json_extract(data, '$.state.$name.$it')" }
                val partProjection = "json_object('type', json_extract(data, '$.type'), 'tool', json_extract(data, '$.tool'), " +
                    "'files', json_extract(data, '$.files'), 'state', json_object(" +
                    "'input', json_object(${source("input")}), 'metadata', json_object(${source("metadata")})))"
                database.prepareStatement(
                    "SELECT message_id, $partProjection FROM part WHERE session_id = ? " +
                        "AND json_extract(data, '$.type') IN ('patch', 'tool') ORDER BY message_id, id LIMIT 250001",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        var count = 0
                        while (rows.next()) {
                            check(++count <= 250_000)
                            val part = parse(rows.getString(2))
                            // json_object inserts absent fields as null. Preserve the input-over-metadata merge.
                            part.getAsJsonObject("state").entrySet().forEach { (_, value) ->
                                value.asJsonObject.entrySet().removeIf { it.value.isJsonNull }
                            }
                            messages[rows.getString(1)]?.arr("parts")?.add(part)
                        }
                    }
                }
                SessionSummary.History(bySession.getValue(sessionId), sessions.filterKeys { it != sessionId }.map { (id, tokens) ->
                    SessionSummary.Descendant(tokens, bySession.getValue(id))
                })
            }
        }.getOrNull()
    }
}
