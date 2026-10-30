package varro.host

import java.nio.file.Files
import java.nio.file.Path

/** The caller reserves verified server ownership and stops OpenCode before compacting. */
internal object DatabaseMaintenance {
    data class Space(val bytes: Long, val wal: Long, val reusable: Long, val required: Long, val available: Long)
    fun inspect(path: Path, check: () -> Unit = {}): Space {
        HistoryDatabase.validateFiles(path)
        return HistoryDatabase.open(path, checkCancelled = check, seconds = 10).use { space(it, path) }
    }

    fun compact(path: Path, check: () -> Unit = {}, progress: (String) -> Unit = {}): Space {
        HistoryDatabase.validateFiles(path)
        return HistoryDatabase.open(path, writable = true, checkCancelled = check, seconds = 3600).use { database ->
            val before = space(database, path)
            require(before.available >= before.required) { "Not enough free disk space for database compaction" }
            check()
            HistoryDatabase.execute(database, "PRAGMA locking_mode=EXCLUSIVE")
            HistoryDatabase.execute(database, "BEGIN EXCLUSIVE")
            HistoryDatabase.execute(database, "COMMIT")
            require(HistoryDatabase.scalar(database, "PRAGMA wal_checkpoint(TRUNCATE)") == 0L) { "Database is still in use. Close other OpenCode clients before compaction." }
            progress("Reclaiming unused SQLite pages")
            HistoryDatabase.execute(database, "PRAGMA synchronous=FULL")
            HistoryDatabase.execute(database, "VACUUM")
            HistoryDatabase.execute(database, "PRAGMA wal_checkpoint(TRUNCATE)")
            check()
            progress("Verifying compacted database")
            require(HistoryDatabase.query(database, "PRAGMA quick_check(1)") { it.getString(1) }.single() == "ok") { "Compacted database integrity check failed" }
            space(database, path)
        }
    }

    private fun space(database: java.sql.Connection, path: Path): Space {
        val tables = HistoryDatabase.tables(database)
        require("project" in tables && ("session" in tables || "session_v2" in tables)) { "Unsupported OpenCode database schema" }
        val walPath = Path.of("$path-wal")
        val wal = if (Files.exists(walPath)) Files.size(walPath) else 0L
        val bytes = Math.addExact(Files.size(path), wal)
        val pageSize = HistoryDatabase.scalar(database, "PRAGMA page_size")!!
        val pages = HistoryDatabase.scalar(database, "PRAGMA page_count")!!
        val reusable = Math.multiplyExact(HistoryDatabase.scalar(database, "PRAGMA freelist_count")!!, pageSize)
        val required = Math.addExact(Math.multiplyExact(maxOf(bytes, Math.multiplyExact(pages, pageSize)), 2), 64L * 1024 * 1024)
        return Space(bytes, wal, reusable, required, Files.getFileStore(path).usableSpace)
    }
}
