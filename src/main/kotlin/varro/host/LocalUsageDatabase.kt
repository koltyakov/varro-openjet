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
    private val identities: Set<String>? = null,
    private val includeArchive: Boolean = true,
    private val boundedBodies: Boolean = true,
) {
    fun read(start: Long?, checkCancelled: () -> Unit, consume: (String, JsonObject) -> Unit): Long? {
        val archivePath = HistoryDatabase.sidecar(path, "stats")
        if (!Files.exists(path) && (!includeArchive || !Files.exists(archivePath))) return null
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
        DriverManager.getConnection(if (Files.exists(path)) "jdbc:sqlite:${path.toAbsolutePath()}" else "jdbc:sqlite::memory:",
            if (Files.exists(path)) config.toProperties() else java.util.Properties()).use { database ->
            org.sqlite.ProgressHandler.setHandler(database, 1_000, object : org.sqlite.ProgressHandler() {
                override fun progress(): Int { checkProgress(); return 0 }
            })
            var timings = emptyMap<Pair<String, String>, JsonObject>()
            val hasArchive = includeArchive && Files.exists(archivePath)
            if (hasArchive) {
                database.prepareStatement("ATTACH DATABASE ? AS stats").use { it.setString(1, "file:${archivePath.toAbsolutePath().toUri().rawPath}?mode=ro"); it.execute() }
                timings = OriginalUsageTiming.read(database, "stats")
            }
            database.autoCommit = false
            val tables = database.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            } }
            val sources = mutableListOf<String>()
            val messages = mutableListOf<String>()
            val bodyLimit = if (identities == null && boundedBodies) " AND length(m.data)<=1048576" else ""
            if (tables.containsAll(listOf("session", "message"))) {
                sources.add("SELECT id,id AS identity,time_updated,1 AS version,0 AS archived FROM session")
                messages.add("SELECT m.id,1 AS version,m.session_id,m.data,NULL AS source,NULL AS originalCompleted,json_extract(m.data,'$.parentID') AS parentID FROM selected s CROSS JOIN message m ON s.id=m.session_id " +
                    "WHERE s.version=1 AND s.archived=0$bodyLimit AND json_extract(m.data,'$.role')='assistant'")
            }
            if (tables.containsAll(listOf("session_v2", "session_message"))) {
                sources.add("SELECT id,coalesce(json_extract(metadata,'$.varroLegacyImport.sourceSessionID'),id) AS identity,time_updated,2 AS version,0 AS archived FROM session_v2")
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
                messages.add("SELECT m.id,2 AS version,m.session_id,m.data,NULL AS source,$originalCompleted AS originalCompleted,coalesce(json_extract(m.data,'$.parentID'),(SELECT u.id FROM session_message u WHERE u.session_id=m.session_id " +
                    "AND u.type='user' AND u.seq<m.seq ORDER BY u.seq DESC LIMIT 1)) AS parentID FROM selected s CROSS JOIN session_message m ON s.id=m.session_id " +
                    "WHERE s.version=2 AND s.archived=0 AND m.type='assistant'$bodyLimit")
            }
            if (hasArchive) {
                sources.add("SELECT id,identity,time_updated,version,1 AS archived FROM stats.usage_session")
                messages.add("SELECT m.id,0 AS version,m.session_id,m.data,NULL AS source,NULL AS originalCompleted,json_extract(m.data,'$.parentID') AS parentID FROM selected s CROSS JOIN stats.usage_message m ON s.id=m.session_id WHERE s.archived=1")
            }
            val archivedAuxiliary = hasArchive && database.createStatement().use { statement ->
                statement.executeQuery("SELECT 1 FROM stats.sqlite_master WHERE type='table' AND name='usage_auxiliary'").use { it.next() }
            }
            if ("event" in tables) messages.add("SELECT m.id,2 AS version,m.aggregate_id AS session_id,json_set(m.data,'$.time',json_object('created',m.created)) AS data," +
                "CASE WHEN json_extract(m.data,'$.sessionID')=s.id THEN coalesce(json_extract(m.data,'$.source'),'invalid') ELSE 'invalid' END AS source,NULL AS originalCompleted,NULL AS parentID " +
                "FROM selected s CROSS JOIN event m ON s.id=m.aggregate_id WHERE s.version=2 AND s.archived=0 AND m.type='session.usage.recorded.1'$bodyLimit")
            if (archivedAuxiliary) messages.add("SELECT m.id,0 AS version,m.session_id,m.data,coalesce(json_extract(m.data,'$.source'),'invalid') AS source,NULL AS originalCompleted,NULL AS parentID " +
                "FROM selected s CROSS JOIN stats.usage_auxiliary m ON s.id=m.session_id WHERE s.archived=1$bodyLimit")
            if (sources.isEmpty()) throw java.sql.SQLException("No supported OpenCode usage tables found")
            if (identities != null) {
                HistoryDatabase.execute(database, "CREATE TEMP TABLE requested_identity(identity TEXT PRIMARY KEY)")
                identities.forEach { HistoryDatabase.execute(database, "INSERT INTO requested_identity VALUES(?)", it) }
            }
            val recent = listOfNotNull(
                if ("event" in tables) "(version=2 AND archived=0 AND EXISTS(SELECT 1 FROM event e WHERE e.aggregate_id=ranked.id AND e.type='session.usage.recorded.1' AND e.created>=?1))" else null,
                if (archivedAuxiliary) "(archived=1 AND EXISTS(SELECT 1 FROM stats.usage_auxiliary a WHERE a.session_id=ranked.id AND json_extract(a.data,'$.time.created')>=?1))" else null,
            )
            val selected = "WITH ranked AS (SELECT *,row_number() OVER (PARTITION BY identity ORDER BY time_updated DESC,version DESC,archived ASC,id DESC) AS rank FROM (" +
                sources.joinToString(" UNION ALL ") + ")" + (if (identities == null) "" else " WHERE identity IN (SELECT identity FROM requested_identity)") + "), selected AS (SELECT * FROM ranked WHERE rank=1" +
                (if (start == null) "" else " AND (time_updated>=?1" + recent.joinToString("") { " OR $it" } + ")") + ") "
            val sessionCount = database.prepareStatement(selected + "SELECT count(*) FROM selected").use { statement ->
                if (start != null) statement.setLong(1, start)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            val fields = listOf("providerID", "modelID", "model", "variant", "tokens", "cost")
                .joinToString(", ") { "'$it', json_extract(m.data, '$.$it')" }
            val time = "json_object('created',json_extract(m.data,'$.time.created'),'completed',coalesce(m.originalCompleted,json_extract(m.data,'$.time.completed')))"
            val native = mutableMapOf<String, AuxiliaryUsage.Remainder>()
            val columns = if ("session_v2" in tables) HistoryDatabase.query(database, "PRAGMA table_info(session_v2)") { it.getString("name") }.toSet() else emptySet()
            if (columns.containsAll(listOf("cost", "tokens_input", "tokens_output", "tokens_reasoning", "tokens_cache_read", "tokens_cache_write"))) {
                database.prepareStatement(selected + "SELECT n.* FROM selected s CROSS JOIN session_v2 n ON n.id=s.id WHERE s.version=2 AND s.archived=0").use { statement ->
                    if (start != null) statement.setLong(1, start)
                    statement.executeQuery().use { rows -> while (rows.next()) {
                        checkProgress()
                        val tokens = Json.obj("input" to rows.getObject("tokens_input"), "output" to rows.getObject("tokens_output"), "reasoning" to rows.getObject("tokens_reasoning"),
                            "cache" to Json.obj("read" to rows.getObject("tokens_cache_read"), "write" to rows.getObject("tokens_cache_write")))
                        AuxiliaryUsage.validate(Json.obj("source" to "unrecorded", "tokens" to tokens))
                        val cost = (rows.getObject("cost") as? Number)?.toDouble()
                        require(cost != null && cost.isFinite() && cost >= 0) { "Malformed native session cost" }
                        native[rows.getString("id")] = AuxiliaryUsage.Remainder(tokens, cost)
                    } }
                }
            }
            val query = selected + "SELECT m.session_id, json_object($fields, 'id',m.id, 'source',m.source, 'time',$time, 'parentID',m.parentID), m.version, s.identity, m.originalCompleted FROM (" + messages.joinToString(" UNION ALL ") + ") m JOIN selected s ON s.id=m.session_id LIMIT 1000001"
            database.prepareStatement(query).use { statement ->
                if (start != null) statement.setLong(1, start)
                statement.executeQuery().use { rows ->
                    var scanned = 0
                    while (rows.next()) {
                        checkProgress()
                        check(++scanned <= 1_000_000) { "Usage report exceeds the 1,000,000-message local scan limit" }
                        val sessionId = rows.getString(1)
                        var info = Json.parse(rows.getString(2)).asJsonObject
                        val auxiliary = info.hasNonNull("source")
                        if (auxiliary) info = AuxiliaryUsage.validate(info) else info.remove("source")
                        if (rows.getInt(3) == 2 && info.str("source") != "unrecorded") native[sessionId]?.subtract(info)
                        if (rows.getInt(3) == 2 && !auxiliary) {
                            val preserved = OriginalUsageTiming.completed(timings[sessionId to info.str("id")], rows.getString(4), info)
                            val original = rows.getObject(5)?.let { (it as Number).toLong() }
                            check(original == null || preserved == null || original == preserved) { "Conflicting original usage timing for ${info.str("id")}" }
                            if (original == null && preserved != null) info.obj("time")!!.addProperty("completed", preserved)
                        }
                        val boundaries = if (rows.getInt(3) == 2 && !auxiliary) pauses.getOrPut(sessionId) {
                            SessionPauses.read(readAnnotations(sessionId).obj("metadata"))
                        } else emptyMap()
                        consume(sessionId, SessionPauses.capUsage(info, boundaries))
                    }
                }
            }
            native.forEach { (id, remaining) -> checkProgress(); remaining.info()?.let { consume(id, it) } }
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
