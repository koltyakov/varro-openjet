package varro.server

import org.sqlite.SQLiteConfig
import varro.host.ProjectPermissionConfig
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.sql.DriverManager

/** Missing or unreadable evidence never authorizes a new external search default. */
internal class WebSearchPreference(
    private val environment: Map<String, String>, private val workspace: () -> String?, private val home: Path,
) {
    fun useRandomDefault(): Boolean = try { !configured() && !stored() } catch (_: Exception) { false }

    private fun configured(): Boolean {
        fun contains(raw: String): Boolean {
            val value = com.google.gson.GsonBuilder().setStrictness(com.google.gson.Strictness.STRICT).create()
                .fromJson(ProjectPermissionConfig.stripJsonComments(raw), com.google.gson.JsonElement::class.java).asObjectOrNull()
            return value == null || value.has("websearch")
        }
        environment["OPENCODE_CONFIG_CONTENT"]?.takeIf(String::isNotBlank)?.let { if (contains(it)) return true }
        val global = (environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of) ?: home.resolve(".config")).resolve("opencode")
        val paths = linkedSetOf(global.resolve("config.json"), global.resolve("opencode.json"), global.resolve("opencode.jsonc"))
        environment["OPENCODE_CONFIG"]?.takeIf(String::isNotBlank)?.let { paths.add(Path.of(it)) }
        environment["OPENCODE_CONFIG_DIR"]?.takeIf(String::isNotBlank)?.let { directory ->
            listOf("opencode.json", "opencode.jsonc").forEach { paths.add(Path.of(directory).resolve(it)) }
        }
        var current = workspace()?.let { Path.of(it).toAbsolutePath().normalize() }
        while (current != null) {
            for (directory in listOf(current, current.resolve(".opencode"))) {
                listOf("opencode.json", "opencode.jsonc").forEach { paths.add(directory.resolve(it)) }
            }
            current = current.parent
        }
        return paths.any { path ->
            try {
                require(Files.size(path) <= 4 * 1024 * 1024) { "Oversized config" }
                contains(Files.readString(path))
            } catch (_: NoSuchFileException) { false }
        }
    }

    private fun stored(): Boolean {
        val configured = environment["OPENCODE_DB"]
        if (configured == ":memory:") return false
        val data = (environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of) ?: home.resolve(".local/share")).resolve("opencode")
        val path = data.resolve(configured ?: "opencode.db").toAbsolutePath()
        try { Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java) }
        catch (_: NoSuchFileException) { return false }
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:file:${path.toUri().rawPath}?mode=ro", SQLiteConfig().apply {
            setReadOnly(true); setBusyTimeout(1000)
        }.toProperties()).use { database ->
            val deadline = System.nanoTime() + 2_000_000_000L
            org.sqlite.ProgressHandler.setHandler(database, 100, object : org.sqlite.ProgressHandler() {
                override fun progress(): Int = if (System.nanoTime() >= deadline) 1 else 0
            })
            database.createStatement().use { statement ->
                val table = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='kv'").use { it.next() }
                if (!table) return false
                return statement.executeQuery("SELECT 1 FROM kv WHERE key='websearch:provider' LIMIT 1").use { it.next() }
            }
        }
    }
}
