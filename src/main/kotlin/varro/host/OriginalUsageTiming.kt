package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import java.sql.Connection

/** Independent scalar provenance, compatible with Varro's version-one statistics sidecar. */
internal object OriginalUsageTiming {
    val columns = listOf("session_id", "message_id", "source_session_id", "source_message_id", "time_created", "provider_id", "model_id", "time_completed", "provenance")

    fun checkSchema(database: Connection, schema: String = "main"): Boolean {
        val version = HistoryDatabase.scalar(database, "PRAGMA $schema.user_version")
        require(version == 0L || version == 1L) { "Unsupported usage archive schema version: $version" }
        val hasTiming = "usage_original_timing" in HistoryDatabase.tables(database, schema)
        require(hasTiming == (version == 1L)) { "Incomplete original usage timing schema. Restore the statistics archive before cleanup." }
        if (hasTiming) {
            val actual = HistoryDatabase.query(database, "PRAGMA $schema.table_info(usage_original_timing)") { it.getString("name") to it.getInt("pk") }
            require(actual == columns.mapIndexed { index, name -> name to if (index < 2) index + 1 else 0 } &&
                HistoryDatabase.query(database, "PRAGMA $schema.foreign_key_list(usage_original_timing)") { true }.isEmpty()) { "Unsupported original usage timing table layout" }
        }
        return hasTiming
    }

    fun initialize(database: Connection) {
        checkSchema(database)
        HistoryDatabase.execute(database, "CREATE TABLE IF NOT EXISTS usage_original_timing (session_id TEXT NOT NULL, message_id TEXT NOT NULL, source_session_id TEXT NOT NULL, source_message_id TEXT NOT NULL, time_created INTEGER NOT NULL, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, time_completed INTEGER NOT NULL, provenance TEXT NOT NULL CHECK(provenance='v1-message'), PRIMARY KEY(session_id,message_id))")
        HistoryDatabase.execute(database, "PRAGMA user_version=1")
    }

    fun validate(row: JsonObject) {
        require(columns.filter { it !in listOf("time_created", "time_completed") }.all { row.text(it)?.length in 1..512 } &&
            row.str("provenance") == "v1-message" && row.str("source_message_id") == row.str("message_id")) { "Invalid preserved original usage timing" }
        val created = row.num("time_created")
        val completed = row.num("time_completed")
        require(created != null && completed != null && created.isFinite() && completed.isFinite() && created >= 0 && completed >= created && completed <= 9_007_199_254_740_991.0 &&
            created == created.toLong().toDouble() && completed == completed.toLong().toDouble()) { "Invalid preserved original usage timing" }
    }

    fun read(database: Connection, schema: String = "main"): Map<Pair<String, String>, JsonObject> {
        if (!checkSchema(database, schema)) return emptyMap()
        var bytes = 0
        val rows = HistoryDatabase.query(database, "SELECT ${columns.joinToString(",")} FROM $schema.usage_original_timing LIMIT 100001") { row ->
            Json.obj(*columns.map { name -> name to row.getObject(name) }.toTypedArray()).also {
                validate(it)
                bytes += it.toString().toByteArray().size
                check(bytes <= 64 * 1024 * 1024) { "Original usage timing exceeds the 64 MB read limit" }
            }
        }
        check(rows.size <= 100_000) { "Original usage timing exceeds the 100,000-record read limit" }
        return rows.associateBy { it.str("session_id")!! to it.str("message_id")!! }
    }

    fun completed(saved: JsonObject?, identity: String, info: JsonObject): Long? {
        if (saved == null) return null
        validate(saved)
        return saved.long("time_completed")?.takeIf {
            saved.str("source_session_id") == identity && saved.long("time_created") == info.obj("time").long("created") &&
                saved.str("provider_id") == (info.str("providerID") ?: info.obj("model").str("providerID")) &&
                saved.str("model_id") == (info.str("modelID") ?: info.obj("model").str("id") ?: info.obj("model").str("modelID"))
        }
    }

    fun insert(database: Connection, row: JsonObject) {
        validate(row)
        val previous = HistoryDatabase.query(database, "SELECT ${columns.joinToString(",")} FROM usage_original_timing WHERE session_id=? AND message_id=?",
            row.str("session_id"), row.str("message_id")) { result -> Json.obj(*columns.map { it to result.getObject(it) }.toTypedArray()) }.singleOrNull()
        require(previous == null || previous == row) { "Conflicting original usage timing for ${row.str("message_id")}" }
        if (previous == null) HistoryDatabase.execute(database, "INSERT INTO usage_original_timing VALUES (?,?,?,?,?,?,?,?,?)",
            *columns.map<String, Any?> { if (it.startsWith("time_")) row.long(it) else row.str(it) }.toTypedArray())
    }
}
