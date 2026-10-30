package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.nio.file.Files
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Reading copies only. Tools, reasoning, replay state and media bytes are never persisted. */
internal class TranscriptArchive(private val usage: UsageArchive) {
    private data class Snapshot(val session: CleanupSession, val turns: JsonArray)
    private data class Record(val type: String, val entry: JsonObject? = null, val outcome: String? = null)
    private class ApiRequired : RuntimeException()

    fun archive(sessions: List<CleanupSession>, version: Int, request: (String, String, RequestOptions) -> OpenCodeResponse, check: () -> Unit) {
        val roots = sessions.filter { it.parentID == null }
        if (roots.isEmpty()) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        fun readCheck() { check(); check(System.nanoTime() < deadline) { "Transcript history read timed out after 60 seconds" } }
        val snapshots = mutableListOf<Snapshot>()
        var bytes = 0L
        roots.forEach { session ->
            readCheck()
            val local = if (version == 2 && Files.exists(usage.path)) HistoryDatabase.open(usage.path, checkCancelled = ::readCheck, seconds = 60).use { database ->
                HistoryDatabase.execute(database, "BEGIN")
                try { readV2(database, session, ::readCheck) } catch (_: ApiRequired) { null }
            } else null
            val records = local ?: readApi(session, version, request, deadline, ::readCheck)
            val turns = trim(records)
            bytes += turns.toString().toByteArray().size
            require(bytes <= MAX_BYTES) { "Transcript tree exceeds the 64 MB archive limit. No sessions were deleted." }
            snapshots.add(Snapshot(session, turns))
        }
        check()
        persist(snapshots, check)
    }

    fun archiveLegacy(database: Connection, sessions: List<CleanupSession>, check: () -> Unit) {
        val snapshots = sessions.filter { it.parentID == null }.mapNotNull { session ->
            check()
            val previous = if (Files.exists(usage.transcriptPath)) HistoryDatabase.open(usage.transcriptPath, checkCancelled = check).use {
                require(HistoryDatabase.scalar(it, "PRAGMA user_version") == 1L) { "Unsupported transcript archive schema" }
                HistoryDatabase.scalar(it, "SELECT time_updated FROM transcript_session WHERE id=?", session.id)
            } else null
            if (previous != null && previous >= session.updated) null else Snapshot(session, trim(readV1(database, session, check)))
        }
        require(snapshots.sumOf { it.turns.toString().toByteArray().size.toLong() } <= MAX_BYTES) { "Transcript tree exceeds the 64 MB archive limit" }
        persist(snapshots, check)
    }

    private fun readV2(database: Connection, session: CleanupSession, check: () -> Unit): List<Record> {
        val columns = HistoryDatabase.query(database, "PRAGMA table_info(session_v2)") { it.getString("name") }.toSet()
        if (!columns.containsAll(listOf("id", "time_updated", "fork_session_id", "revert"))) throw ApiRequired()
        val header = HistoryDatabase.query(database, "SELECT time_updated,fork_session_id,revert FROM session_v2 WHERE id=?", session.id) {
            Triple(it.getLong(1), it.getString(2), it.getString(3))
        }.singleOrNull() ?: error("Transcript session is missing")
        require(header.first == session.updated) { "Transcript session changed" }
        if (header.second != null || header.third != null && header.third != "null") throw ApiRequired()
        val tables = HistoryDatabase.tables(database)
        val known = setOf("user", "assistant", "idle", "synthetic", "system", "skill", "shell", "compaction", "agent-switched", "model-switched", "location-switched")
        var bytes = 0L
        var partCount = 0
        var previousSeq = -1L
        val projected = HistoryDatabase.query(database, "SELECT id,type,seq,json_object('time',json_extract(data,'$.time'),'model',json_extract(data,'$.model')," +
            "'finish',json_extract(data,'$.finish'),'text',json_extract(data,'$.text'),'providerID',json_extract(data,'$.providerID'),'modelID',json_extract(data,'$.modelID'),'outcome',json_extract(data,'$.outcome')) FROM session_message WHERE session_id=? ORDER BY seq LIMIT 100001", session.id) { row ->
            check()
            require(row.getLong(3) > previousSeq) { "Malformed transcript sequence" }; previousSeq = row.getLong(3)
            val message = Json.parse(row.getString(4)).asJsonObject.apply { addProperty("id", row.getString(1)); addProperty("type", row.getString(2)) }
            require(message.str("type") in known) { "Unsupported transcript message type" }
            val id = message.str("id")!!
            if (message.str("type") == "assistant") {
                val stored = if ("session_part" in tables) {
                    val foundColumns = HistoryDatabase.query(database, "PRAGMA table_info(session_part)") { it.getString("name") }.toSet()
                    if (!foundColumns.containsAll(listOf("message_id", "session_id", "ordinal", "type", "data"))) throw ApiRequired()
                    var ordinal = -1L
                    HistoryDatabase.query(database, "SELECT session_id,ordinal,type,json_extract(data,'$.type'),CASE WHEN type='text' THEN json_extract(data,'$.text') END FROM session_part WHERE message_id=? ORDER BY ordinal LIMIT 500001", id) { part ->
                        check(); require(++partCount <= 500_000 && part.getString(1) == session.id && part.getLong(2) > ordinal) { "Malformed or oversized stored transcript parts" }
                        ordinal = part.getLong(2)
                        if (part.getString(3) != part.getString(4)) throw ApiRequired()
                        require(part.getString(3) in setOf("text", "reasoning", "tool")) { "Unsupported transcript part" }
                        if (part.getString(3) == "text") Json.obj("type" to "text", "text" to (part.getString(5) ?: error("Missing transcript text"))) else Json.obj("type" to part.getString(3))
                    }
                } else emptyList()
                val content = if (stored.isNotEmpty()) stored else {
                    require(HistoryDatabase.query(database, "SELECT json_type(data,'$.content') FROM session_message WHERE id=?", id) { it.getString(1) }.single() == "array") { "Missing transcript assistant content" }
                    HistoryDatabase.query(database, "SELECT json_extract(c.value,'$.type'),CASE WHEN json_extract(c.value,'$.type')='text' THEN json_extract(c.value,'$.text') END FROM session_message m,json_each(m.data,'$.content') c WHERE m.id=? ORDER BY c.key", id) { part ->
                        check(); val type = part.getString(1)
                        require(type in setOf("text", "reasoning", "tool")) { "Unsupported transcript part" }
                        Json.obj("type" to type).apply { if (type == "text") addProperty("text", part.getString(2) ?: error("Missing transcript text")) }
                    }
                }
                message.add("content", Json.array(content))
            }
            if (message.str("type") == "user") {
                val filesType = HistoryDatabase.query(database, "SELECT json_type(data,'$.files') FROM session_message WHERE id=?", id) { it.getString(1) }.single()
                require(filesType == null || filesType == "array") { "Malformed transcript files" }
                val files = HistoryDatabase.query(database, "SELECT f.key,json_extract(f.value,'$.mime'),coalesce(json_extract(f.value,'$.name'),json_extract(f.value,'$.filename')) FROM session_message m,json_each(m.data,'$.files') f WHERE m.id=? ORDER BY f.key", id) { file ->
                    val name = file.getString(3) ?: "Attachment"
                    val mime = file.getString(2) ?: error("Missing attachment MIME type")
                    Json.obj("name" to name, "mime" to mime).apply {
                        if (preservedText(name, mime)) {
                            val prefix = "$.files[${file.getInt(1)}]"
                            val values = HistoryDatabase.query(database, "SELECT json_extract(data,?,?,?) FROM session_message WHERE id=?", "$prefix.data", "$prefix.uri", "$prefix.url", id) { Json.parse(it.getString(1)).asJsonArray }.single()
                            when {
                                !values[0].isJsonNull -> add("data", values[0])
                                !values[1].isJsonNull && Regex("media:[a-f0-9]{64}").matches(values[1].asString) -> {
                                    val data = HistoryDatabase.query(database, "SELECT data FROM blob WHERE hash=? AND length(data)<=65536", values[1].asString.removePrefix("media:")) { it.getBytes(1) }.singleOrNull() ?: error("Missing or oversized text attachment blob")
                                    addProperty("data", Base64.getEncoder().encodeToString(data))
                                }
                                !values[2].isJsonNull -> add("url", values[2])
                                else -> error("Text attachment is unavailable")
                            }
                        }
                    }
                }
                message.add("files", Json.array(files))
            }
            bytes += message.toString().toByteArray().size
            require(bytes <= MAX_BYTES) { "Projected transcript exceeds 64 MB" }
            message
        }
        require(projected.size <= 100_000) { "Transcript exceeds the message limit" }
        return decode(projected, true)
    }

    private fun readV1(database: Connection, session: CleanupSession, check: () -> Unit): List<Record> {
        val columns = HistoryDatabase.query(database, "PRAGMA table_info(session)") { it.getString("name") }.toSet()
        require(HistoryDatabase.scalar(database, "SELECT time_updated FROM session WHERE id=?", session.id) == session.updated) { "Legacy transcript session changed or is missing" }
        if ("revert" in columns) require(HistoryDatabase.query(database, "SELECT revert FROM session WHERE id=?", session.id) { it.getString(1) }.singleOrNull().let { it == null || it == "null" }) { "Cannot preserve reverted legacy history safely" }
        var bytes = 0L
        var parts = 0
        val messages = HistoryDatabase.query(database, "SELECT id,json_object('role',json_extract(data,'$.role'),'time',json_extract(data,'$.time'),'providerID',json_extract(data,'$.providerID'),'modelID',json_extract(data,'$.modelID'),'finish',json_extract(data,'$.finish'),'summary',json_extract(data,'$.summary')) FROM message WHERE session_id=? ORDER BY time_created,id LIMIT 100001", session.id) { row ->
            check()
            val info = Json.parse(row.getString(2)).asJsonObject.apply { addProperty("id", row.getString(1)) }
            info.num("summary")?.let { info.addProperty("summary", it != 0.0) }
            val projected = HistoryDatabase.query(database, "SELECT json_extract(data,'$.type'),json_object('type',json_extract(data,'$.type'),'synthetic',json_extract(data,'$.synthetic'),'ignored',json_extract(data,'$.ignored'),'mime',json_extract(data,'$.mime'),'filename',json_extract(data,'$.filename'),'name',json_extract(data,'$.name'),'text',CASE WHEN json_extract(data,'$.type')='text' THEN json_extract(data,'$.text') END),CASE WHEN json_extract(data,'$.type')='file' AND json_extract(data,'$.mime')='text/plain' THEN json_extract(data,'$.url','$.data') END FROM part WHERE message_id=? AND session_id=? ORDER BY time_created,id LIMIT 500001", info.str("id"), session.id) { part ->
                check(); require(++parts <= 500_000) { "Transcript exceeds the part limit" }
                Json.parse(part.getString(2)).asJsonObject.apply {
                    for (flag in listOf("synthetic", "ignored")) num(flag)?.let { addProperty(flag, it != 0.0) }
                    if (preservedText(str("name") ?: str("filename").orEmpty(), str("mime").orEmpty())) {
                        val attachment = part.getString(3)?.let { Json.parse(it).asJsonArray } ?: error("Missing text attachment")
                        add("url", attachment[0]); add("data", attachment[1])
                    }
                }
            }
            Json.obj("info" to info, "parts" to projected).also {
                bytes += it.toString().toByteArray().size
                require(bytes <= MAX_BYTES) { "Projected transcript exceeds 64 MB" }
            }
        }
        require(messages.size <= 100_000) { "Transcript exceeds the message limit" }
        return decode(messages, false)
    }

    private fun readApi(session: CleanupSession, version: Int, request: (String, String, RequestOptions) -> OpenCodeResponse, deadline: Long, check: () -> Unit): List<Record> {
        val records = mutableListOf<JsonObject>()
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        var bytes = 0L
        do {
            check()
            val query = if (version == 2) "limit=200&" + (cursor?.let { "cursor=${encode(it)}" } ?: "order=desc") else "directory=${encode(session.directory)}"
            val path = "/${if (version == 2) "api/" else ""}session/${encode(session.id)}/message?$query"
            val response = request("GET", path, RequestOptions(unscoped = true, timeoutMs = 30_000, deadlineNanos = deadline,
                maxResponseBytes = MAX_BYTES, cancellableBody = true, isCancelled = { check(); false })).data
            val page = response.asObjectOrNull()
            val data = if (version == 2) page.arr("data") else response.asArrayOrNull()
            require(data != null) { "Malformed transcript history" }
            bytes += data.toString().toByteArray().size
            require(bytes <= MAX_BYTES && records.size + data.size() <= 100_000) { "Transcript history exceeds archive bounds" }
            records.addAll(data.map { it.asObjectOrNull() ?: error("Malformed transcript message") })
            cursor = if (version == 2) {
                val pagination = page.obj("cursor") ?: error("Malformed transcript pagination")
                require(!pagination.hasNonNull("next") || pagination.text("next") != null) { "Malformed transcript cursor" }
                pagination.text("next")
            } else null
            require(cursor == null || !data.isEmpty && cursors.add(cursor)) { "Repeated transcript pagination cursor" }
        } while (cursor != null)
        return decode(if (version == 2) records.reversed() else records, version == 2)
    }

    private fun decode(messages: List<JsonObject>, native: Boolean): List<Record> {
        val ids = mutableSetOf<String>()
        return messages.mapNotNull { message ->
            val info = if (native) message else message.obj("info") ?: error("Malformed legacy transcript")
            require(info.text("id") != null && ids.add(info.str("id")!!)) { "Missing or repeated transcript message identity" }
            val type = if (native) info.str("type") else info.str("role")
            if (type == "idle") {
                require(info.str("outcome") in setOf("succeeded", "failed", "interrupted")) { "Malformed transcript turn outcome" }
                Record("idle", outcome = info.str("outcome"))
            } else if (type in setOf("user", "assistant")) {
                if (!native && info.bool("summary") == true) Record("synthetic") else {
                    val parts = if (native) if (type == "assistant") message.arr("content") else null else message.arr("parts") ?: error("Missing legacy transcript parts")
                    val text = if (native && type == "user") message.str("text") ?: error("Malformed transcript prompt") else {
                        require(parts != null) { "Missing transcript response content" }
                        parts.mapNotNull { value ->
                            val part = value.asObjectOrNull() ?: error("Malformed transcript part")
                            if (native) require(part.str("type") in setOf("text", "reasoning", "tool")) { "Unsupported transcript part" }
                            if (part.str("type") == "text" && part.bool("synthetic") != true && part.bool("ignored") != true) part.str("text") ?: error("Malformed transcript text") else null
                        }.joinToString("\n\n")
                    }
                    val entry = text(info, text)
                    if (type == "user") {
                        val files = if (native) message.arr("files")?.toList().orEmpty() else parts!!.filter { it.asObjectOrNull().str("type") == "file" }
                        entry.add("attachments", Json.array(files.map { attachment(it.asObjectOrNull() ?: error("Malformed transcript attachment")) }))
                    }
                    Record(type!!, entry)
                }
            } else {
                require(type in setOf("synthetic", "system", "skill", "shell", "compaction", "agent-switched", "model-switched", "location-switched")) { "Unsupported transcript message type" }
                if (type == "synthetic") Record("synthetic") else null
            }
        }
    }

    private fun text(info: JsonObject, text: String): JsonObject {
        val created = info.obj("time").num("created")
        val completed = info.obj("time").num("completed")
        require(created != null && created.isFinite() && created >= 0 && (completed == null || completed.isFinite() && completed >= created)) { "Malformed transcript timestamp" }
        return Json.obj("id" to info.str("id"), "created" to created, "text" to text).apply {
            if (completed != null) addProperty("completed", completed)
            info.str("finish")?.let { addProperty("finish", it) }
            val provider = info.obj("model").str("providerID") ?: info.str("providerID")
            val model = info.obj("model").str("id") ?: info.str("modelID")
            if (provider != null && model != null) add("model", Json.obj("providerID" to provider, "id" to model).apply {
                info.obj("model").str("variant")?.let { addProperty("variant", it) }
            })
        }
    }

    private fun attachment(file: JsonObject): JsonObject {
        val mime = file.str("mime") ?: error("Malformed transcript attachment MIME type")
        val name = file.str("name") ?: file.str("filename") ?: "Attachment"
        return Json.obj("name" to name, "mime" to mime, "omitted" to !preservedText(name, mime)).apply {
            if (preservedText(name, mime)) {
                val url = file.str("data")?.let { "data:text/plain;base64,$it" } ?: file.str("url")
                val text = url?.let(TextAttachments::decode) ?: error("Cannot preserve transcript text attachment $name")
                addProperty("text", text)
            }
        }
    }

    private fun trim(records: List<Record>): JsonArray {
        val turns = JsonArray()
        var prompts = JsonArray()
        var assistant: JsonObject? = null
        var lastText: JsonObject? = null
        fun flush(outcome: String, boundary: String) {
            val partial = outcome in setOf("failed", "interrupted")
            val candidate = if (partial) lastText else assistant
            val response = candidate?.takeIf { !it.str("text").isNullOrEmpty() && (partial || it.hasNonNull("completed") && it.str("finish") != "tool-calls") }
            if (!prompts.isEmpty || response != null) turns.add(Json.obj("prompts" to prompts, "outcome" to outcome, "boundary" to boundary).apply { if (response != null) add("response", response) })
            prompts = JsonArray(); assistant = null; lastText = null
        }
        records.forEach { record -> when (record.type) {
            "idle" -> flush(record.outcome!!, "idle")
            "user" -> {
                if (assistant?.hasNonNull("completed") == true && assistant.str("finish") != "tool-calls") flush("unknown", "inferred")
                prompts.add(record.entry)
            }
            "assistant" -> { assistant = record.entry; if (!record.entry.str("text").isNullOrEmpty()) lastText = record.entry }
            else -> if (assistant.str("finish") == "stop") flush("unknown", "inferred")
        } }
        flush("unknown", "inferred")
        return turns
    }

    private fun persist(snapshots: List<Snapshot>, check: () -> Unit) {
        if (snapshots.isEmpty()) return
        if (Files.exists(usage.transcriptPath)) require(!Files.isSameFile(usage.path, usage.transcriptPath)) { "Transcript archive must not be the live database" }
        HistoryDatabase.open(usage.transcriptPath, writable = true, checkCancelled = check).use { database ->
            require(HistoryDatabase.scalar(database, "PRAGMA user_version") in setOf(0L, 1L)) { "Unsupported transcript archive schema" }
            HistoryDatabase.execute(database, "PRAGMA synchronous=FULL")
            HistoryDatabase.transaction(database) {
                HistoryDatabase.execute(database, "CREATE TABLE IF NOT EXISTS transcript_session(id TEXT PRIMARY KEY,identity TEXT NOT NULL,parent_id TEXT,title TEXT NOT NULL,directory TEXT NOT NULL,time_created INTEGER,time_updated INTEGER NOT NULL,source_version INTEGER NOT NULL,time_archived INTEGER NOT NULL)")
                HistoryDatabase.execute(database, "CREATE TABLE IF NOT EXISTS transcript_turn(session_id TEXT NOT NULL REFERENCES transcript_session(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(session_id,ordinal))")
                HistoryDatabase.execute(database, "CREATE INDEX IF NOT EXISTS transcript_session_identity_idx ON transcript_session(identity)")
                HistoryDatabase.execute(database, "PRAGMA user_version=1")
                for ((session, turns) in snapshots) {
                    check()
                    val previous = HistoryDatabase.scalar(database, "SELECT time_updated FROM transcript_session WHERE id=?", session.id)
                    if (session.version == 1 && previous != null && previous >= session.updated) continue
                    require(previous == null || previous <= session.updated) { "Refusing to overwrite a newer transcript snapshot" }
                    HistoryDatabase.execute(database, "DELETE FROM transcript_session WHERE id=?", session.id)
                    HistoryDatabase.execute(database, "INSERT INTO transcript_session VALUES(?,?,?,?,?,?,?,?,?)", session.id, session.identity, session.parentID, session.title, session.directory, session.created, session.updated, session.version, System.currentTimeMillis())
                    turns.forEachIndexed { index, turn -> check(); HistoryDatabase.execute(database, "INSERT INTO transcript_turn VALUES(?,?,?)", session.id, index, turn.toString()) }
                }
                check()
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        private fun preservedText(name: String, mime: String) = mime == "text/plain" && Regex("(?:pasted-text|annotation)-[a-f0-9]{8}\\.txt").matches(name)
        private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
