package varro.host

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

internal object AboutDatabase {
    fun markdown(path: Path, attachOnly: Boolean, now: Long = System.currentTimeMillis()): String {
        if (attachOnly) return "## OpenCode database\n\nDatabase details are unavailable for attach-only connections. Run maintenance on the server host."
        return try {
            HistoryDatabase.open(path, checkCancelled = {}, seconds = 3).use { database ->
                HistoryDatabase.execute(database, "BEGIN")
                val tables = HistoryDatabase.tables(database)
                val sessions = listOf("session", "session_v2").filter { it in tables }
                val count = sessions.sumOf { HistoryDatabase.scalar(database, "SELECT count(*) FROM $it") ?: 0 }
                val oldest = sessions.mapNotNull { table ->
                    if (HistoryDatabase.query(database, "PRAGMA table_info($table)") { it.getString("name") }.contains("time_created"))
                        HistoryDatabase.scalar(database, "SELECT min(time_created) FROM $table WHERE time_created>0") else null
                }.minOrNull()
                val pageSize = HistoryDatabase.scalar(database, "PRAGMA page_size") ?: 0
                val reusable = (HistoryDatabase.scalar(database, "PRAGMA freelist_count") ?: 0) * pageSize
                val wal = Path.of("$path-wal").takeIf(Files::exists)?.let(Files::size) ?: 0
                val projects = if ("project" in tables) HistoryDatabase.scalar(database, "SELECT count(*) FROM project")?.toString() ?: "Unknown" else "Unknown"
                """
                ## OpenCode database

                - Location: `${path.toString().replace("`", "\\`")}`
                - Size including WAL: ${size(Files.size(path) + wal)}
                - Reusable space: ${size(reusable)}
                - History age: ${if (count == 0L) "No history" else age(oldest, now)}
                - Projects: $projects
                - Sessions: $count

                Stored V1 and V2 records include copies and child sessions. History age uses the oldest stored session. Reusable pages stay in the file until compaction. Statistics and transcript archives are separate.

                Use Tools | Varro | Compact OpenCode Database for a separate, confirmed offline compaction.
                """.trimIndent()
            }
        } catch (_: Exception) { "## OpenCode database\n\nDatabase details are unavailable. No database was changed." }
    }

    internal fun age(oldest: Long?, now: Long): String {
        if (oldest == null || oldest <= 0 || oldest > now) return "Unknown"
        val start = Instant.ofEpochMilli(oldest).atZone(ZoneOffset.UTC)
        val end = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
        val months = ChronoUnit.MONTHS.between(start, end)
        if (months > 0) return "$months ${if (months == 1L) "month" else "months"}"
        val days = (now - oldest) / 86_400_000
        return if (days == 0L) "<1 day" else "$days ${if (days == 1L) "day" else "days"}"
    }
    private fun size(value: Long) = String.format(Locale.US, "%.1f MB", value / 1_000_000.0)
}
