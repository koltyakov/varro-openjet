package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import java.sql.Connection

/** Split parts precede inline content. Ambiguous fork/revert histories stay API-owned. */
internal object LocalV2SessionSummary {
    private data class Stored(val session: String, var ordinal: Long = -1, val parts: MutableList<JsonObject> = mutableListOf())

    fun read(database: Connection, sessionID: String, check: () -> Unit, mixedContent: Boolean): SessionSummary.History? {
        fun columns(table: String) = HistoryDatabase.query(database, "PRAGMA table_info($table)") { it.getString("name") }.toSet()
        if (!columns("session_v2").containsAll(listOf("id", "parent_id", "fork_session_id", "revert", "tokens_input",
                "tokens_output", "tokens_reasoning", "tokens_cache_read", "tokens_cache_write")) ||
            !columns("session_message").containsAll(listOf("id", "session_id", "type", "seq", "data"))) return null
        val sessions = linkedMapOf<String, JsonObject>()
        HistoryDatabase.query(database, "WITH RECURSIVE tree(id) AS (SELECT id FROM session_v2 WHERE id=? " +
            "UNION SELECT s.id FROM session_v2 s JOIN tree ON s.parent_id=tree.id) " +
            "SELECT s.id,s.fork_session_id,s.revert,s.tokens_input,s.tokens_output,s.tokens_reasoning,s.tokens_cache_read,s.tokens_cache_write " +
            "FROM session_v2 s JOIN tree ON s.id=tree.id LIMIT 10001", sessionID) { row ->
            check()
            require(sessions.size < 10_000 && row.getString("fork_session_id") == null &&
                row.getString("revert").let { it == null || it == "null" }) { "Fork or revert history requires the API" }
            sessions[row.getString("id")] = Json.obj("input" to row.getLong("tokens_input"), "output" to row.getLong("tokens_output"),
                "reasoning" to row.getLong("tokens_reasoning"),
                "cache" to Json.obj("read" to row.getLong("tokens_cache_read"), "write" to row.getLong("tokens_cache_write")))
        }
        if (sessions.isEmpty()) return null
        val ids = sessions.keys.toTypedArray()
        val placeholders = ids.joinToString(",") { "?" }
        val contexts = ids.associateWith { Json.obj("system" to 0L, "user" to 0L, "assistant" to 0L, "tool" to 0L) }
        val inputTokens = mutableMapOf<String, Double>()
        fun addCharacters(owner: String, key: String, count: Long) {
            val context = contexts.getValue(owner)
            context.addProperty(key, (context.long(key) ?: 0) + count)
        }
        var bytes = 0L
        var parts = 0
        fun parse(source: String): JsonObject {
            check()
            val size = source.toByteArray(Charsets.UTF_8).size
            bytes += size
            require(size <= 64 * 1024 * 1024 && bytes <= 128 * 1024 * 1024) { "Local summary exceeds the data limit" }
            return Json.parse(source).asJsonObject
        }
        // Retain only file-change inputs and metadata, never text, attachments or tool output.
        fun content(value: JsonObject, owner: String): JsonObject? {
            check()
            require(++parts <= 250_000) { "Local summary exceeds the part limit" }
            if (value.str("type") in setOf("text", "reasoning")) addCharacters(owner, "assistant", value.str("text").orEmpty().length.toLong())
            if (value.str("type") != "tool") return null
            val state = value.obj("state")
            var characters = (state.obj("input")?.size() ?: 0) * 16L
            if (state.str("status") == "streaming") characters += state.str("input").orEmpty().length
            if (state.str("status") == "error") characters += state.obj("error").str("message").orEmpty().length
            characters += state.arr("content")?.sumOf { part ->
                part.asObjectOrNull().takeIf { it.str("type") == "text" }.str("text").orEmpty().length.toLong()
            } ?: 0
            addCharacters(owner, "tool", characters)
            val keys = listOf("relativePath", "file", "path", "filePath", "filepath", "filename",
                "additions", "deletions", "linesAdded", "linesRemoved")
            fun projectFile(source: JsonObject?) = JsonObject().apply {
                keys.forEach { key -> source?.get(key)?.let { add(key, it) } }
            }
            fun project(source: JsonObject?) = projectFile(source).apply {
                source.arr("files")?.let { files -> add("files", Json.array(files.map { projectFile(it.asObjectOrNull()) })) }
            }
            return Json.obj("type" to "tool", "tool" to value.str("name"),
                "state" to Json.obj("input" to project(value.obj("state").obj("input")), "metadata" to project(value.obj("state").obj("metadata"))))
        }
        val stored = linkedMapOf<String, Stored>()
        val partColumns = columns("session_part")
        if (partColumns.isNotEmpty()) {
            require(partColumns.containsAll(listOf("message_id", "session_id", "ordinal", "type", "data"))) { "Unsupported stored-part schema" }
            HistoryDatabase.query(database, "SELECT message_id,session_id,ordinal,type,data FROM session_part WHERE session_id IN ($placeholders) " +
                "ORDER BY message_id,ordinal LIMIT 250001", *ids) { row ->
                val value = parse(row.getString("data"))
                val type = row.getString("type")
                require(value.str("type") == type && type in setOf("text", "reasoning", "tool")) { "Unsupported stored part" }
                require(if (type == "tool") value.text("name") != null && value.obj("state") != null
                    else value.get("text")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true) { "Malformed stored part" }
                val entry = stored.getOrPut(row.getString("message_id")) { Stored(row.getString("session_id")) }
                val ordinal = row.getLong("ordinal")
                require(!row.wasNull() && row.getObject("ordinal") is Number && row.getDouble("ordinal") == ordinal.toDouble() &&
                    ordinal >= 0 && ordinal > entry.ordinal && entry.session == row.getString("session_id")) { "Malformed stored part ownership or ordinal" }
                entry.ordinal = ordinal
                content(value, entry.session)?.takeIf { entry.session == sessionID }?.let(entry.parts::add)
            }
        }
        val bySession = ids.associateWith { mutableListOf<JsonObject>() }
        val parents = mutableMapOf<String, String>()
        var count = 0
        HistoryDatabase.query(database, "SELECT id,session_id,type,data FROM session_message WHERE session_id IN ($placeholders) " +
            "ORDER BY session_id,seq LIMIT 100001", *ids) { row ->
            require(++count <= 100_000) { "Local summary exceeds the message limit" }
            val value = parse(row.getString("data"))
            val id = row.getString("id")
            val owner = row.getString("session_id")
            val type = row.getString("type")
            val split = stored.remove(id)
            require(split == null || type == "assistant" && split.session == owner) { "Stored part has no owning assistant" }
            require(mixedContent || split == null || value.arr("content")?.size().let { it == null || it == 0 }) { "Mixed history on an older server requires the API" }
            val user = type in setOf("user", "synthetic", "compaction")
            val assistant = type in setOf("assistant", "shell", "skill") || type == "idle" && value.str("outcome") in setOf("failed", "interrupted")
            if (user || assistant) {
                if (type == "user") parents[owner] = id
                if (user) addCharacters(owner, "user", value.str("text").orEmpty().length.toLong())
                if (type == "skill") addCharacters(owner, "tool", 16L + value.str("text").orEmpty().length)
                if (type == "shell") addCharacters(owner, "tool", 16L + value.obj("output").str("output").orEmpty().length)
                if (type == "assistant") value.obj("tokens").num("input")?.takeIf { it.isFinite() && it > 0 }?.let { inputTokens[owner] = it }
                val info = Json.obj("id" to id, "sessionID" to owner, "role" to if (user) "user" else "assistant",
                    "parentID" to if (assistant) parents[owner] else null,
                    "providerID" to value.obj("model").str("providerID"), "modelID" to value.obj("model").str("id"),
                    "variant" to value.obj("model")?.get("variant"))
                for (key in listOf("time", "tokens", "cost", "agent", "summary")) value.get(key)?.let { info.add(key, it) }
                if (type == "skill" || type == "idle") info.add("time", Json.obj("created" to value.obj("time")?.get("created"),
                    "completed" to value.obj("time")?.get("created")))
                val projected = split?.parts ?: mutableListOf()
                if (type == "assistant") value.arr("content")?.forEach { part ->
                    content(part.asJsonObject, owner)?.takeIf { owner == sessionID }?.let(projected::add)
                }
                bySession.getValue(owner).add(Json.obj("info" to info, "parts" to projected))
            }
        }
        require(stored.isEmpty()) { "Stored part has no assistant message" }
        return SessionSummary.History(bySession.getValue(sessionID), sessions.filterKeys { it != sessionID }.map { (id, tokens) ->
            SessionSummary.Descendant(tokens, bySession.getValue(id), contexts.getValue(id), inputTokens[id])
        }, contexts.getValue(sessionID), inputTokens[sessionID])
    }
}
