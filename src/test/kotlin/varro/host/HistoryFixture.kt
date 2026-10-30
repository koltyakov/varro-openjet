package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import java.nio.file.Path
import java.sql.DriverManager

/** Only explicit temporary paths. This helper never discovers an installed OpenCode database. */
internal class HistoryFixture(val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
        database { db ->
            for (sql in listOf(
                "CREATE TABLE project(id TEXT PRIMARY KEY)",
                "INSERT INTO project VALUES('project')",
                "CREATE TABLE session(id TEXT PRIMARY KEY,directory TEXT NOT NULL,title TEXT NOT NULL,parent_id TEXT REFERENCES session(id),time_created INTEGER,time_updated INTEGER,revert TEXT)",
                "CREATE TABLE message(id TEXT PRIMARY KEY,session_id TEXT REFERENCES session(id),time_created INTEGER,data TEXT NOT NULL)",
                "CREATE TABLE part(id TEXT PRIMARY KEY,session_id TEXT REFERENCES session(id),message_id TEXT REFERENCES message(id),time_created INTEGER,data TEXT NOT NULL)",
                "CREATE TABLE session_v2(id TEXT PRIMARY KEY,directory TEXT NOT NULL,title TEXT NOT NULL,parent_id TEXT,time_created INTEGER,time_updated INTEGER,metadata TEXT,fork_session_id TEXT,revert TEXT)",
                "CREATE TABLE session_message(id TEXT PRIMARY KEY,session_id TEXT REFERENCES session_v2(id),seq INTEGER,type TEXT,data TEXT NOT NULL)",
                "CREATE TABLE session_part(id TEXT PRIMARY KEY,message_id TEXT,session_id TEXT,ordinal INTEGER,type TEXT,data TEXT NOT NULL)",
                "CREATE TABLE blob(hash TEXT PRIMARY KEY,data BLOB)",
                "CREATE TABLE session_inbox(id TEXT PRIMARY KEY,session_id TEXT,payload TEXT)",
            )) HistoryDatabase.execute(db, sql)
        }
    }
    val archive get() = UsageArchive(path) { Json.obj() }
    fun csv() = UsageCsv.read(path, readAnnotations = { Json.obj() })
    fun <T> database(operation: (java.sql.Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:$path").use(operation)
    fun execute(sql: String, vararg args: Any?) = database { HistoryDatabase.execute(it, sql, *args) }
    fun session(id: String, version: Int = 2, updated: Long = 1000, parent: String? = null, identity: String = id): CleanupSession {
        val directory = path.parent.resolve("workspace").toString()
        val value = CleanupSession(id, directory, updated, version, parent, "Fixture $id", 100, identity)
        if (version == 1) execute("INSERT INTO session VALUES(?,?,?,?,?,?,NULL)", id, directory, value.title, parent, 100, updated)
        else execute("INSERT INTO session_v2 VALUES(?,?,?,?,?,?,?,NULL,NULL)", id, directory, value.title, parent, 100, updated,
            Json.obj("varroLegacyImport" to Json.obj("sourceSessionID" to identity)).toString())
        return value
    }
    fun message(session: CleanupSession, id: String, type: String, data: JsonObject, seq: Int) {
        data.addProperty("id", id)
        if (session.version == 2) {
            data.addProperty("type", type)
            execute("INSERT INTO session_message VALUES(?,?,?,?,?)", id, session.id, seq, type, data.toString())
        } else {
            data.addProperty("role", type)
            execute("INSERT INTO message VALUES(?,?,?,?)", id, session.id, seq, data.toString())
        }
    }
    fun assistant(created: Long = 100, completed: Long = 200, input: Int = 10, variant: String? = "high", text: String = "final answer") = Json.obj(
        "time" to Json.obj("created" to created, "completed" to completed), "providerID" to "provider", "modelID" to "model",
        "model" to Json.obj("providerID" to "provider", "id" to "model", "variant" to variant), "parentID" to "prompt", "finish" to "stop",
        "tokens" to Json.obj("input" to input, "output" to 5), "cost" to 0.25,
        "content" to listOf(Json.obj("type" to "text", "text" to text), Json.obj("type" to "reasoning", "text" to "PRIVATE REASONING"), Json.obj("type" to "tool", "output" to "PRIVATE TOOL BODY")),
    )
    fun count(table: String) = database { HistoryDatabase.scalar(it, "SELECT count(*) FROM $table") }
}
