package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.sqlite.SQLiteConfig
import varro.protocol.*
import varro.server.OpenCodeV2Projection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Copy v1 history from a bounded read-only transaction. Import never submits prompts. */
internal class LegacySessionImport(
    private val databasePath: Path,
    private val request: (String, String, JsonElement?) -> JsonElement?,
) {
    data class Choice(val id: String, val title: String, val directory: String) { override fun toString() = title }
    fun list(directory: String): List<Choice> = read { database ->
        rows(database, "SELECT id,title,directory FROM session WHERE directory=? AND parent_id IS NULL ORDER BY time_updated DESC LIMIT 1000", listOf(directory))
            .map { Choice(it.str("id")!!, it.str("title") ?: "Untitled", it.str("directory")!!) }
    }

    fun importCopy(choice: Choice): String {
        val records = read { database ->
            val sessions = rows(database, "WITH RECURSIVE tree(id,depth) AS (SELECT id,0 FROM session WHERE id=? AND directory=? UNION ALL " +
                "SELECT s.id,t.depth+1 FROM session s JOIN tree t ON s.parent_id=t.id WHERE t.depth<32 AND s.directory=?) " +
                "SELECT s.* FROM tree t JOIN session s ON s.id=t.id ORDER BY t.depth LIMIT 101", listOf(choice.id, choice.directory, choice.directory))
            require(sessions.isNotEmpty()) { "The selected v1 session is no longer available" }
            require(sessions.size <= 100) { "The selected session tree is too large to import" }
            var bytes = 0L
            fun parse(row: JsonObject): JsonObject {
                val text = row.str("data") ?: error("Invalid legacy record")
                bytes += text.toByteArray(Charsets.UTF_8).size
                require(bytes <= 32 * 1024 * 1024) { "The selected history exceeds the 32 MiB import limit" }
                return Json.parse(text).asJsonObject
            }
            sessions.map { session ->
                val id = session.str("id")!!
                val messages = rows(database, "SELECT * FROM message WHERE session_id=? ORDER BY time_created,id LIMIT 10001", listOf(id))
                val parts = rows(database, "SELECT * FROM part WHERE session_id=? ORDER BY time_created,id LIMIT 100001", listOf(id))
                require(messages.size <= 10000 && parts.size <= 100000) { "The selected history is too large to import" }
                val byMessage = parts.groupBy { it.str("message_id") }.mapValues { (_, values) -> values.map { row -> parse(row).apply {
                    add("id", row.get("id")); add("messageID", row.get("message_id")); addProperty("sessionID", id)
                } } }
                session to messages.map { row -> Json.obj("info" to parse(row).apply { add("id", row.get("id")); addProperty("sessionID", id) }, "parts" to byMessage[row.str("id")].orEmpty()) }
            }
        }
        val ids = mutableMapOf<String, String>()
        records.forEach { (session, messages) ->
            ids[session.str("id")!!] = newID("ses")
            messages.forEach { ids[it.obj("info").str("id")!!] = newID("msg") }
        }
        val location = request("GET", "/api/location?location%5Bdirectory%5D=${java.net.URLEncoder.encode(choice.directory, Charsets.UTF_8)}", null).asObjectOrNull()
        val projectID = location.obj("project").str("id") ?: error("Could not resolve the destination v2 project")
        val imported = mutableListOf<String>()
        try {
            records.forEach { (session, messages) ->
                val id = ids[session.str("id")]!!
                val now = System.currentTimeMillis()
                val model = session.get("model")?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) Json.parseOrNull(it.asString) else it }
                val payload = Json.obj("location" to Json.obj("directory" to choice.directory), "info" to Json.obj(
                    "id" to id, "parentID" to ids[session.str("parent_id")], "projectID" to projectID,
                    "title" to "${session.str("title") ?: "Untitled"} (v1 copy)", "location" to Json.obj("directory" to choice.directory),
                    "agent" to session.get("agent"), "model" to OpenCodeV2Projection.modelRef(model),
                    "time" to Json.obj("created" to session.get("time_created"), "updated" to now), "cost" to (session.num("cost") ?: 0),
                    "tokens" to Json.obj("input" to (session.num("tokens_input") ?: 0), "output" to (session.num("tokens_output") ?: 0),
                        "reasoning" to (session.num("tokens_reasoning") ?: 0), "cache" to Json.obj("read" to (session.num("tokens_cache_read") ?: 0), "write" to (session.num("tokens_cache_write") ?: 0))),
                    "metadata" to Json.obj("varroLegacyImport" to Json.obj("sourceSessionID" to session.get("id"), "source" to session, "importedAt" to now))),
                    "messages" to messages.map { convert(it, ids) })
                request("POST", "/api/experimental/session/import", remap(payload, ids))
                imported.add(id)
            }
        } catch (failure: Exception) {
            val remaining = imported.asReversed().filter { runCatching { request("DELETE", "/api/session/$it", null) }.isFailure }
            if (remaining.isNotEmpty()) throw IllegalStateException("Import failed; incomplete copies remain: ${remaining.joinToString()}", failure)
            throw failure
        }
        return ids[choice.id]!!
    }

    private fun <T> read(block: (Connection) -> T): T {
        require(Files.exists(databasePath)) { "No local OpenCode v1 database found at $databasePath" }
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply { setReadOnly(true); setBusyTimeout(1000) }
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}", config.toProperties()).use { database ->
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            org.sqlite.ProgressHandler.setHandler(database, 1000, object : org.sqlite.ProgressHandler() {
                override fun progress(): Int = if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted) 1 else 0
            })
            database.autoCommit = false
            try { return block(database) } finally { database.rollback() }
        }
    }

    private fun rows(database: Connection, sql: String, arguments: List<String>): List<JsonObject> = database.prepareStatement(sql).use { statement ->
        arguments.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows -> buildList {
            var bytes = 0L
            while (rows.next()) add(Json.obj().apply {
                for (index in 1..rows.metaData.columnCount) {
                    val value = rows.getObject(index)
                    bytes += value?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
                    require(bytes <= 32 * 1024 * 1024) { "The selected history exceeds the 32 MiB import limit" }
                    add(rows.metaData.getColumnName(index), Json.toElement(value))
                }
            })
        } }
    }

    companion object {
        private fun newID(prefix: String) = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
        private fun remap(value: JsonElement, ids: Map<String, String>): JsonElement = when {
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> Json.toElement(ids[value.asString] ?: value.asString)
            value.isJsonArray -> Json.array(value.asJsonArray.map { remap(it, ids) })
            value.isJsonObject -> Json.obj().apply { value.asJsonObject.entrySet().forEach { (key, item) -> add(key, if (key in setOf("varroLegacy", "varroLegacyImport")) item else remap(item, ids)) } }
            else -> value
        }
        internal fun convert(entry: JsonObject, ids: Map<String, String>): JsonObject {
            val info = entry.obj("info") ?: error("Invalid legacy message")
            val parts = entry.arr("parts")?.mapNotNull { it.asObjectOrNull() } ?: error("Invalid legacy parts")
            val time = info.obj("time") ?: Json.obj()
            val base = Json.obj("id" to ids[info.str("id")], "metadata" to Json.obj("varroLegacy" to entry))
            if (info.str("role") == "user") return base.apply {
                addProperty("type", "user"); add("time", Json.obj("created" to time.get("created")))
                addProperty("text", parts.mapNotNull { part -> when {
                    part.str("type") == "text" -> part.str("text")
                    part.str("type") == "file" && part.str("url")?.startsWith("data:") == false -> "[File attachment: ${part.str("filename") ?: part.str("url")}]"
                    else -> null
                } }.joinToString("\n"))
                add("files", Json.array(parts.filter { it.str("type") == "file" }.mapNotNull { part ->
                    Regex("^data:([^;,]+);base64,([\\s\\S]*)$").matchEntire(part.str("url").orEmpty())?.let { match ->
                        Json.obj("mime" to match.groupValues[1], "data" to match.groupValues[2], "name" to part.get("filename"), "source" to Json.obj("type" to "inline"))
                    }
                }))
            }
            require(info.str("role") == "assistant") { "Unsupported legacy message role" }
            return base.apply {
                addProperty("type", "assistant"); add("time", time.deepCopy().apply { if (!hasNonNull("completed")) add("completed", get("created")) })
                addProperty("agent", info.str("agent") ?: info.str("mode") ?: "build")
                add("model", Json.obj("providerID" to info.get("providerID"), "id" to info.get("modelID")))
                addProperty("finish", if (time.hasNonNull("completed")) info.str("finish") else "error")
                add("cost", info.get("cost")); add("tokens", info.get("tokens"))
                if (info.hasNonNull("error")) add("error", Json.obj("type" to "unknown", "message" to (info.obj("error").obj("data").str("message") ?: "Imported assistant error")))
                add("content", Json.array(parts.mapNotNull { part -> when (part.str("type")) {
                    "text", "reasoning" -> Json.obj("type" to part.get("type"), "text" to part.str("text").orEmpty(), "time" to if (part.str("type") == "reasoning") Json.obj(
                        "created" to (part.obj("time")?.get("start") ?: time.get("created")), "completed" to (part.obj("time")?.get("end") ?: time.get("completed") ?: time.get("created"))) else null)
                    "tool" -> {
                        val state = part.obj("state") ?: Json.obj()
                        val converted = Json.obj("status" to if (state.str("status") == "completed") "completed" else "error", "input" to (state.obj("input") ?: Json.obj()), "metadata" to state.get("metadata"))
                        if (state.str("status") == "completed") converted.add("content", Json.array(listOf(Json.obj("type" to "text", "text" to (state.str("output") ?: state.get("output")?.toString().orEmpty())))))
                        else converted.add("error", Json.obj("type" to "unknown", "message" to (state.str("error") ?: "Tool was unfinished in the imported v1 snapshot")))
                        Json.obj("type" to "tool", "id" to (part.get("callID") ?: part.get("id")), "name" to OpenCodeV2Projection.nativeAction(part.str("tool")),
                            "time" to Json.obj("created" to (state.obj("time")?.get("start") ?: time.get("created")), "ran" to state.obj("time")?.get("start"),
                                "completed" to (state.obj("time")?.get("end") ?: time.get("completed") ?: time.get("created"))), "state" to converted)
                    }
                    else -> null
                } }))
            }
        }
    }
}
