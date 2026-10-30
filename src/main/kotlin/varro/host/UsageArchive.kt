package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

internal data class CleanupSession(
    val id: String, val directory: String, val updated: Long, val version: Int,
    val parentID: String? = null, val title: String = "", val created: Long? = null, val identity: String = id,
) {
    companion object {
        fun decode(value: JsonObject, version: Int): CleanupSession {
            val id = value.text("id")
            val directory = value.text("directory")
            val updated = value.obj("time").num("updated")
            require(id != null && id.matches(Regex("[A-Za-z0-9_-]{1,256}")) && directory != null &&
                Path.of(directory).isAbsolute && updated != null && updated.isFinite() && updated >= 0 && updated == updated.toLong().toDouble() &&
                (!value.hasNonNull("parentID") || value.text("parentID") != null)) { "Malformed session metadata. No further sessions were deleted." }
            return CleanupSession(id, directory, updated.toLong(), version, value.text("parentID"), value.str("title").orEmpty(),
                value.obj("time").long("created"), value.obj("metadata").obj("varroLegacyImport").text("sourceSessionID") ?: id)
        }
    }
}

/** Durable statistics and retryable artifact journal. No replayable message bodies enter this archive. */
internal class UsageArchive(val path: Path, private val readAnnotations: (String) -> JsonObject = varro.server.OpenCodeV2SessionState()::read) {
    val archivePath = HistoryDatabase.sidecar(path, "stats")
    val transcriptPath = HistoryDatabase.sidecar(path, "transcripts")
    private val dataDirectory = path.toAbsolutePath().parent
    data class Pending(val session: CleanupSession, val directories: List<String>, val paths: List<Path>)

    fun inventory(database: Connection, version: Int): List<CleanupSession> {
        val table = if (version == 1) "session" else "session_v2"
        if (table !in HistoryDatabase.tables(database)) return emptyList()
        return HistoryDatabase.query(database, "SELECT id,directory,title,parent_id,time_created,time_updated" +
            (if (version == 2) ",metadata" else "") + " FROM $table ORDER BY time_updated,id") { row ->
            val value = Json.obj("id" to row.getString("id"), "directory" to row.getString("directory"), "title" to row.getString("title"),
                "time" to Json.obj("created" to row.getObject("time_created"), "updated" to row.getObject("time_updated")))
            row.getString("parent_id")?.let { value.addProperty("parentID", it) }
            if (version == 2) row.getString("metadata")?.let { value.add("metadata", Json.parse(it)) }
            CleanupSession.decode(value, version)
        }
    }

    fun legacyInventory(expectedV2: List<CleanupSession>, check: () -> Unit = {}): List<CleanupSession> =
        HistoryDatabase.open(path, checkCancelled = check).use { database ->
            HistoryDatabase.execute(database, "BEGIN")
            val stored = inventory(database, 2).associateBy { it.id }
            require(stored.size == expectedV2.size && expectedV2.all { stored[it.id]?.let { current -> current.updated == it.updated && current.directory == it.directory } == true }) {
                "The local database does not match the connected V2 inventory. Retry after current activity settles."
            }
            inventory(database, 1)
        }

    fun archive(sessions: List<CleanupSession>, check: () -> Unit = {}) {
        require(sessions.isNotEmpty())
        val selected: List<CleanupSession>
        val artifacts: Map<String, List<Path>>
        HistoryDatabase.open(path, checkCancelled = check).use { database ->
            HistoryDatabase.execute(database, "BEGIN")
            val all = inventory(database, 1) + inventory(database, 2)
            sessions.forEach { expected -> require(all.any { it == expected }) { "Session changed or missing from the local database: ${expected.id}" } }
            val identities = all.filter { current -> sessions.any { it.id == current.id && it.version == current.version } }.map { it.identity }.toSet()
            val previous = if (Files.exists(archivePath)) HistoryDatabase.open(archivePath, checkCancelled = check).use { archive ->
                OriginalUsageTiming.checkSchema(archive)
                HistoryDatabase.query(archive, "SELECT id,identity,time_updated,version FROM usage_session") {
                    CleanupSession(it.getString(1), "", it.getLong(3), it.getInt(4), identity = it.getString(2))
                }
            } else emptyList()
            selected = all.filter { it.identity in identities }.groupBy { it.identity }.values.mapNotNull { copies ->
                val live = copies.maxWith(compareBy<CleanupSession> { it.updated }.thenBy { it.version }.thenBy { it.id })
                val saved = previous.firstOrNull { it.identity == live.identity }
                live.takeIf { saved == null || saved.updated < live.updated || saved.updated == live.updated && saved.version <= live.version }
            }
            val tables = HistoryDatabase.tables(database)
            selected.forEach { session ->
                check()
                val table = if (session.version == 1) "message" else "session_message"
                require(HistoryDatabase.scalar(database, "SELECT count(*) FROM $table WHERE session_id=? AND NOT json_valid(data)", session.id) == 0L) {
                    "Cannot safely archive malformed history for ${session.id}"
                }
            }
            artifacts = sessions.associate { it.id to collectArtifacts(database, tables, it, check) }
        }
        val messages = mutableListOf<Pair<String, JsonObject>>()
        var bytes = 0L
        LocalUsageDatabase(path, readAnnotations, sessions.map { it.identity }.toSet()).read(null, check) { id, info ->
            if (selected.any { it.id == id }) {
                bytes += info.toString().toByteArray().size
                check(bytes <= 64L * 1024 * 1024) { "Usage archive tree exceeds the 64 MB limit" }
                messages.add(id to info)
            }
        }
        check()
        writer(check) { archive ->
            selected.forEach { session ->
                HistoryDatabase.execute(archive, "DELETE FROM usage_session WHERE identity=? OR id=?", session.identity, session.id)
                HistoryDatabase.execute(archive, "INSERT INTO usage_session VALUES(?,?,?,?)", session.id, session.identity, session.updated, session.version)
            }
            archive.prepareStatement("INSERT INTO usage_message VALUES(?,?,?)").use { insert ->
                messages.forEach { (id, info) ->
                    check()
                    // Keep only the scalar projection, even if future readers return extra fields.
                    val projected = Json.obj(*listOf("parentID", "cost", "time", "tokens").map { it to info.get(it) }.toTypedArray()).apply {
                        addProperty("providerID", info.str("providerID") ?: info.obj("model").str("providerID"))
                        addProperty("modelID", info.str("modelID") ?: info.obj("model").str("id") ?: info.obj("model").str("modelID"))
                        addProperty("variant", info.obj("model").str("variant") ?: info.str("variant"))
                    }
                    insert.setString(1, info.str("id")); insert.setString(2, id); insert.setString(3, projected.toString()); insert.executeUpdate()
                }
            }
            sessions.forEach { session ->
                check()
                HistoryDatabase.execute(archive, "INSERT OR IGNORE INTO usage_cleanup_directory SELECT id,directory FROM usage_cleanup WHERE id=?", session.id)
                HistoryDatabase.execute(archive, "INSERT OR IGNORE INTO usage_cleanup_directory VALUES(?,?)", session.id, session.directory)
                HistoryDatabase.execute(archive, "INSERT OR REPLACE INTO usage_cleanup VALUES(?,?,?)", session.id, session.directory, session.updated)
                artifacts.getValue(session.id).forEach { artifact -> HistoryDatabase.execute(archive, "INSERT OR IGNORE INTO usage_artifact VALUES(?,?)", session.id, artifact.toString()) }
            }
            check()
        }
    }

    fun preserveTimings(database: Connection, legacyIDs: Set<String>, check: () -> Unit) {
        val tables = HistoryDatabase.tables(database)
        if (!tables.containsAll(listOf("session_v2", "session_message", "message"))) return
        val rows = mutableListOf<JsonObject>()
        var bytes = 0L
        inventory(database, 2).filter { it.identity in legacyIDs }.forEach { session ->
            check()
            database.prepareStatement("SELECT m.id, json_extract(m.data,'$.time.created') AS created, " +
                "coalesce(json_extract(m.data,'$.providerID'),json_extract(m.data,'$.model.providerID')) AS provider, " +
                "coalesce(json_extract(m.data,'$.modelID'),json_extract(m.data,'$.model.id'),json_extract(m.data,'$.model.modelID')) AS model, " +
                "CASE WHEN json_valid(o.data) THEN json_extract(o.data,'$.role','$.time.created','$.providerID','$.modelID','$.time.completed') END AS original " +
                "FROM session_message m JOIN message o ON o.id=m.id AND o.session_id=? WHERE m.session_id=? AND m.type='assistant' LIMIT 100001").use { statement ->
                statement.setString(1, session.identity); statement.setString(2, session.id)
                statement.executeQuery().use { result ->
                    var scanned = 0
                    while (result.next()) {
                        check(); require(++scanned <= 100_000) { "Original timing exceeds the session record limit" }
                        val original = result.getString("original")?.let { Json.parse(it).asArrayOrNull() } ?: continue
                        val created = result.getObject("created") as? Number ?: continue
                        val completed = original.get(4).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
                        if (original.get(0).asString != "assistant" || original.get(1).asDouble != created.toDouble() || completed < created.toDouble() ||
                            original.get(2).asString != result.getString("provider") || original.get(3).asString != result.getString("model")) continue
                        val row = Json.obj("session_id" to session.id, "message_id" to result.getString("id"), "source_session_id" to session.identity,
                            "source_message_id" to result.getString("id"), "time_created" to created.toLong(), "provider_id" to result.getString("provider"),
                            "model_id" to result.getString("model"), "time_completed" to completed.toLong(), "provenance" to "v1-message")
                        OriginalUsageTiming.validate(row)
                        bytes += row.toString().toByteArray().size
                        require(rows.size < 100_000 && bytes <= 64L * 1024 * 1024) { "Original timing exceeds preservation limits" }
                        rows.add(row)
                    }
                }
            }
        }
        if (rows.isNotEmpty()) writer(check) { archive -> rows.forEach { check(); OriginalUsageTiming.insert(archive, it) } }
    }

    private fun collectArtifacts(database: Connection, tables: Set<String>, session: CleanupSession, check: () -> Unit): List<Path> {
        val paths = linkedSetOf(Path.of(session.directory, ".opencode", "attachments", session.id), dataDirectory.resolve("storage/session_diff/${session.id}.json"))
        for (table in if (session.version == 1) listOf("message", "part") else listOf("session_message", "session_part")) {
            if (table !in tables) continue
            HistoryDatabase.query(database, "SELECT DISTINCT j.value FROM $table m,json_tree(m.data) j WHERE m.session_id=? AND j.key IN ('outputPath','uri','url') AND j.type='text'", session.id) {
                check()
                val value = it.getString(1)
                if (value.length <= 32768) runCatching { if (value.startsWith("file:")) Path.of(java.net.URI(value)) else Path.of(value).takeIf(Path::isAbsolute) }.getOrNull() else null
            }.filterNotNull().forEach { candidate -> if (ArtifactCleanup.isGeneratedFile(candidate, dataDirectory)) paths.add(candidate.toAbsolutePath().normalize()) }
        }
        return paths.toList()
    }

    fun pending(check: () -> Unit = {}): List<Pending> {
        if (!Files.exists(archivePath)) return emptyList()
        return HistoryDatabase.open(archivePath, checkCancelled = check).use { archive ->
            OriginalUsageTiming.checkSchema(archive)
            val tables = HistoryDatabase.tables(archive)
            HistoryDatabase.query(archive, "SELECT id,directory,time_updated FROM usage_cleanup ORDER BY time_updated,id") { row ->
                check()
                val id = row.getString(1)
                val session = CleanupSession(id, row.getString(2), row.getLong(3), 0)
                require(id.matches(Regex("[A-Za-z0-9_-]{1,256}")) && Path.of(session.directory).isAbsolute) { "Malformed cleanup journal" }
                val directories = listOf(session.directory) + if ("usage_cleanup_directory" in tables)
                    HistoryDatabase.query(archive, "SELECT directory FROM usage_cleanup_directory WHERE session_id=?", id) { it.getString(1) } else emptyList()
                require(directories.all { Path.of(it).isAbsolute }) { "Malformed cleanup directory provenance" }
                Pending(session, directories.distinct(), HistoryDatabase.query(archive, "SELECT path FROM usage_artifact WHERE session_id=?", id) { Path.of(it.getString(1)) })
            }
        }
    }

    fun finish(id: String, paths: List<Path>, check: () -> Unit) = writer(check) { archive ->
        paths.forEach { HistoryDatabase.execute(archive, "DELETE FROM usage_artifact WHERE session_id=? AND path=?", id, it.toString()) }
        HistoryDatabase.execute(archive, "DELETE FROM usage_cleanup WHERE id=? AND NOT EXISTS(SELECT 1 FROM usage_artifact WHERE session_id=?)", id, id)
        HistoryDatabase.execute(archive, "DELETE FROM usage_cleanup_directory WHERE session_id=? AND NOT EXISTS(SELECT 1 FROM usage_cleanup WHERE id=?)", id, id)
    }

    private fun writer(check: () -> Unit, operation: (Connection) -> Unit) {
        if (Files.exists(archivePath)) require(!Files.isSameFile(path, archivePath)) { "Usage archive must not refer to the live database" }
        HistoryDatabase.open(archivePath, writable = true, checkCancelled = check).use { archive ->
            HistoryDatabase.execute(archive, "PRAGMA synchronous=FULL")
            HistoryDatabase.transaction(archive) {
                OriginalUsageTiming.checkSchema(archive)
                for (sql in SCHEMA) HistoryDatabase.execute(archive, sql)
                OriginalUsageTiming.initialize(archive)
                operation(archive)
                check()
            }
        }
    }

    companion object {
        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS usage_session(id TEXT PRIMARY KEY,identity TEXT NOT NULL UNIQUE,time_updated INTEGER NOT NULL,version INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS usage_message(id TEXT NOT NULL,session_id TEXT NOT NULL REFERENCES usage_session(id) ON DELETE CASCADE,data TEXT NOT NULL,PRIMARY KEY(session_id,id))",
            "CREATE INDEX IF NOT EXISTS usage_message_session_idx ON usage_message(session_id)",
            "CREATE TABLE IF NOT EXISTS usage_cleanup(id TEXT PRIMARY KEY,directory TEXT NOT NULL,time_updated INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS usage_cleanup_directory(session_id TEXT NOT NULL,directory TEXT NOT NULL,PRIMARY KEY(session_id,directory))",
            "CREATE TABLE IF NOT EXISTS usage_artifact(session_id TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(session_id,path))",
        )
    }
}
