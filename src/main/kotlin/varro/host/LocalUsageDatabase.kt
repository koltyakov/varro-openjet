package varro.host

import com.google.gson.JsonObject
import org.sqlite.SQLiteConfig
import varro.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** Projects message metadata only. Message parts never cross the database boundary. */
internal class LocalUsageDatabase(private val path: Path) {
    fun read(start: Long?, checkCancelled: () -> Unit, consume: (String, JsonObject) -> Unit): Long? {
        if (!Files.exists(path)) return null
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
            val filter = if (start == null) "" else " WHERE time_updated >= ?"
            val sessionCount = database.prepareStatement("SELECT count(*) FROM session$filter").use { statement ->
                if (start != null) statement.setLong(1, start)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            val scope = if (start == null) "" else "m.session_id IN (SELECT id FROM session$filter) AND "
            val fields = listOf("providerID", "modelID", "model", "parentID", "time", "tokens")
                .joinToString(", ") { "'$it', json_extract(m.data, '$.$it')" }
            val query = "SELECT m.session_id, json_object($fields) FROM message m " +
                "WHERE ${scope}length(m.data) <= 1048576 AND json_extract(m.data, '$.role') = 'assistant' LIMIT 1000001"
            database.prepareStatement(query).use { statement ->
                if (start != null) statement.setLong(1, start)
                statement.executeQuery().use { rows ->
                    var scanned = 0
                    while (rows.next()) {
                        checkProgress()
                        check(++scanned <= 1_000_000) { "Usage report exceeds the 1,000,000-message local scan limit" }
                        consume(rows.getString(1), Json.parse(rows.getString(2)).asJsonObject)
                    }
                }
            }
            return sessionCount
        }
    }

    companion object {
        fun defaultPath(environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): Path =
            Path.of(environment["XDG_DATA_HOME"]?.trim()?.takeIf { it.isNotEmpty() } ?: Path.of(home, ".local", "share").toString())
                .resolve("opencode/opencode.db")
    }
}
