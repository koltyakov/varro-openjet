package varro.host.quota

import com.google.gson.JsonArray
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Called only with paths under JUnit TemporaryFolder, never a production database. */
internal fun writeQuotaCredentials(path: Path, data: JsonArray) {
    Files.createDirectories(path.parent)
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { database ->
        database.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS credential (id TEXT, integration_id TEXT, value TEXT, active INTEGER, time_created INTEGER)")
            it.execute("DELETE FROM credential")
        }
        database.prepareStatement("INSERT INTO credential VALUES (?,?,?,?,?)").use { statement ->
            data.forEachIndexed { index, raw ->
                val entry = raw.asJsonObject
                statement.setString(1, entry.str("id") ?: "credential-$index")
                statement.setString(2, entry.str("integrationID"))
                statement.setString(3, entry.str("rawValue") ?: Json.stringify(entry.get("value")))
                if (entry.bool("active") == null) statement.setNull(4, java.sql.Types.INTEGER)
                else statement.setInt(4, if (entry.bool("active") == true) 1 else 0)
                statement.setLong(5, entry.long("created") ?: index.toLong())
                statement.executeUpdate()
            }
        }
    }
}
