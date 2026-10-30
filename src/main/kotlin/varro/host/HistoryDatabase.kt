package varro.host

import org.sqlite.SQLiteConfig
import org.sqlite.ProgressHandler
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.TimeUnit

/** SQLite work remains on the caller's background thread and closes before restart or retry. */
internal object HistoryDatabase {
    fun sidecar(path: Path, suffix: String): Path = path.resolveSibling(path.fileName.toString().substringBeforeLast('.', path.fileName.toString()) + "-$suffix.db")

    fun validateFile(path: Path) {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Database must be a regular file without symlinks: $path" }
        if (path.fileSystem.supportedFileAttributeViews().contains("unix")) {
            require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1) { "Database must not have hard links: $path" }
        }
    }

    fun validateFiles(path: Path) {
        validateFile(path)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sibling = Path.of("$path$suffix")
            if (Files.exists(sibling, NOFOLLOW_LINKS)) validateFile(sibling)
        }
    }

    fun open(path: Path?, writable: Boolean = false, checkCancelled: () -> Unit = {}, seconds: Long = 30): Connection {
        checkCancelled()
        if (path != null && writable) {
            Files.createDirectories(path.toAbsolutePath().parent)
            if (!Files.exists(path, NOFOLLOW_LINKS)) {
                val attrs = if (path.fileSystem.supportedFileAttributeViews().contains("posix"))
                    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
                try { Files.createFile(path, *attrs) } catch (_: java.nio.file.FileAlreadyExistsException) { }
            }
            validateFiles(path)
        }
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply { setReadOnly(path != null && !writable); setBusyTimeout(1_000); enforceForeignKeys(true) }
        val database = DriverManager.getConnection(if (path == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        ProgressHandler.setHandler(database, 1_000, object : ProgressHandler() {
            override fun progress(): Int {
                checkCancelled()
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Database operation interrupted")
                check(System.nanoTime() < deadline) { "OpenCode database operation timed out after $seconds seconds" }
                return 0
            }
        })
        return database
    }

    fun tables(database: Connection, schema: String = "main"): Set<String> = query(database,
        "SELECT name FROM $schema.sqlite_master WHERE type='table'") { it.getString(1) }.toSet()

    fun execute(database: Connection, sql: String, vararg args: Any?): Int = database.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.execute()
        statement.updateCount.coerceAtLeast(0)
    }

    fun <T> query(database: Connection, sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = database.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
    }

    fun scalar(database: Connection, sql: String, vararg args: Any?): Long? = query(database, sql, *args) {
        it.getLong(1).let { value -> if (it.wasNull()) null else value }
    }.firstOrNull()

    fun <T> transaction(database: Connection, operation: () -> T): T {
        execute(database, "BEGIN IMMEDIATE")
        try {
            val result = operation()
            execute(database, "COMMIT")
            return result
        } catch (failure: Throwable) {
            runCatching { execute(database, "ROLLBACK") }.onFailure(failure::addSuppressed)
            throw failure
        }
    }
}
