package varro.host

import com.google.gson.JsonObject
import org.sqlite.SQLiteConfig
import varro.protocol.*
import varro.server.OpenCodeV2SessionState
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** Projects message metadata only. Message parts never cross the database boundary. */
internal class LocalUsageDatabase(
    private val path: Path,
    private val readAnnotations: (String) -> JsonObject = OpenCodeV2SessionState()::read,
) {
    fun read(start: Long?, checkCancelled: () -> Unit, consume: (String, JsonObject) -> Unit): Long? {
        if (!Files.exists(path)) return null
        val pauses = mutableMapOf<String, Map<String, Long>>()
        checkCancelled()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun checkProgress() {
            checkCancelled()
            check(System.nanoTime() < deadline) { "Local OpenCode usage query timed out after 30 seconds" }
        }
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(1_000)
        }
        // Explicit loading also works under the IDE plugin classloader.
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties()).use { database ->
            org.sqlite.ProgressHandler.setHandler(database, 1_000, object : org.sqlite.ProgressHandler() {
                override fun progress(): Int { checkProgress(); return 0 }
            })
            database.autoCommit = false
            val tables = database.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            } }
            val sources = mutableListOf<String>()
            val messages = mutableListOf<String>()
            if (tables.containsAll(listOf("session", "message"))) {
                sources.add("SELECT id,id AS identity,time_updated,1 AS version FROM session")
                messages.add("SELECT m.id,1 AS version,m.session_id,m.data,NULL AS originalCompleted,json_extract(m.data,'$.parentID') AS parentID FROM selected s CROSS JOIN message m ON s.id=m.session_id " +
                    "WHERE s.version=1 AND length(m.data)<=1048576 AND json_extract(m.data,'$.role')='assistant'")
            }
            if (tables.containsAll(listOf("session_v2", "session_message"))) {
                sources.add("SELECT id,coalesce(json_extract(metadata,'$.varroLegacyImport.sourceSessionID'),id) AS identity,time_updated,2 AS version FROM session_v2")
                // Imports can replace completion times. Recover timing only when the original message identity matches.
                val originalCompleted = if ("message" in tables) "(SELECT CASE WHEN json_valid(original.data) THEN CASE WHEN " +
                    "json_extract(original.data,'$.role')='assistant' AND " +
                    "json_extract(original.data,'$.time.created')=json_extract(m.data,'$.time.created') AND " +
                    "json_extract(original.data,'$.providerID')=coalesce(json_extract(m.data,'$.providerID'),json_extract(m.data,'$.model.providerID')) AND " +
                    "json_extract(original.data,'$.modelID')=coalesce(json_extract(m.data,'$.modelID'),json_extract(m.data,'$.model.id'),json_extract(m.data,'$.model.modelID')) AND " +
                    "json_type(original.data,'$.time.completed') IN ('integer','real') AND " +
                    "json_extract(original.data,'$.time.completed')>=json_extract(original.data,'$.time.created') " +
                    "THEN json_extract(original.data,'$.time.completed') END END FROM message original WHERE original.id=m.id AND original.session_id=s.identity)"
                    else "NULL"
                messages.add("SELECT m.id,2 AS version,m.session_id,m.data,$originalCompleted AS originalCompleted,coalesce(json_extract(m.data,'$.parentID'),(SELECT u.id FROM session_message u WHERE u.session_id=m.session_id " +
                    "AND u.type='user' AND u.seq<m.seq ORDER BY u.seq DESC LIMIT 1)) AS parentID FROM selected s CROSS JOIN session_message m ON s.id=m.session_id " +
                    "WHERE s.version=2 AND m.type='assistant' AND length(m.data)<=1048576")
            }
            if (sources.isEmpty()) throw java.sql.SQLException("No supported OpenCode usage tables found")
            val selected = "WITH ranked AS (SELECT *,row_number() OVER (PARTITION BY identity ORDER BY time_updated DESC,version DESC,id DESC) AS rank FROM (" +
                sources.joinToString(" UNION ALL ") + ")), selected AS (SELECT * FROM ranked WHERE rank=1" + (if (start == null) "" else " AND time_updated>=?") + ") "
            val sessionCount = database.prepareStatement(selected + "SELECT count(*) FROM selected").use { statement ->
                if (start != null) statement.setLong(1, start)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            val fields = listOf("providerID", "modelID", "model", "tokens")
                .joinToString(", ") { "'$it', json_extract(m.data, '$.$it')" }
            val time = "json_object('created',json_extract(m.data,'$.time.created'),'completed',coalesce(m.originalCompleted,json_extract(m.data,'$.time.completed')))"
            val query = selected + "SELECT m.session_id, json_object($fields, 'id',m.id, 'time',$time, 'parentID',m.parentID), m.version FROM (" + messages.joinToString(" UNION ALL ") + ") m LIMIT 1000001"
            database.prepareStatement(query).use { statement ->
                if (start != null) statement.setLong(1, start)
                statement.executeQuery().use { rows ->
                    var scanned = 0
                    while (rows.next()) {
                        checkProgress()
                        check(++scanned <= 1_000_000) { "Usage report exceeds the 1,000,000-message local scan limit" }
                        val sessionId = rows.getString(1)
                        val info = Json.parse(rows.getString(2)).asJsonObject
                        val boundaries = if (rows.getInt(3) == 2) pauses.getOrPut(sessionId) {
                            SessionPauses.read(readAnnotations(sessionId).obj("metadata"))
                        } else emptyMap()
                        consume(sessionId, SessionPauses.capUsage(info, boundaries))
                    }
                }
            }
            database.rollback()
            return sessionCount
        }
    }

    companion object {
        fun defaultPath(environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): Path =
            environment["OPENCODE_DB"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(environment["XDG_DATA_HOME"]?.trim()?.takeIf { it.isNotEmpty() } ?: Path.of(home, ".local", "share").toString())
                .resolve("opencode/opencode.db")
    }
}
