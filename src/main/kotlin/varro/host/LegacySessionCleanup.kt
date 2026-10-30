package varro.host

/** Invoked only inside the verified stopped-server boundary, with the family cleanup lock held. */
internal class LegacySessionCleanup(private val archive: UsageArchive) {
    fun delete(sessions: List<CleanupSession>, cutoff: Long, transcripts: Boolean, check: () -> Unit, committing: () -> Unit) {
        HistoryDatabase.validateFiles(archive.path)
        HistoryDatabase.open(archive.path, writable = true, checkCancelled = check, seconds = 600).use { database ->
            HistoryDatabase.execute(database, "PRAGMA synchronous=FULL")
            HistoryDatabase.transaction(database) {
                check()
                val current = archive.inventory(database, 1)
                val ids = sessions.map { it.id }.toSet()
                require(ids.size == sessions.size && sessions.isNotEmpty() && sessions.all { expected -> expected.updated < cutoff && current.any { it == expected } } &&
                    current.none { it.parentID in ids && it.id !in ids }) { "Legacy sessions or their child membership changed. No V1 sessions were deleted." }
                val legacyTables = setOf("session", "message", "part", "todo", "session_share")
                require(HistoryDatabase.scalar(database, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND tbl_name IN ('session','message','part','todo','session_share')") == 0L) { "Unsupported legacy deletion triggers" }
                val tables = HistoryDatabase.tables(database)
                tables.forEach { table ->
                    // Identifiers originate in sqlite_master but still require SQL quoting.
                    val quoted = "\"${table.replace("\"", "\"\"")}\""
                    HistoryDatabase.query(database, "PRAGMA foreign_key_list($quoted)") { it.getString("table") }.forEach { target ->
                        require(!(target in setOf("session", "message", "part") && table !in legacyTables) &&
                            !(table in legacyTables && target in setOf("session_v2", "session_message", "session_part"))) { "Unsupported cross-version legacy foreign-key dependency in $table" }
                    }
                }
                archive.preserveTimings(database, ids, check)
                archive.archive(sessions, check)
                if (transcripts) TranscriptArchive(archive).archiveLegacy(database, sessions, check)
                check()
                // Cancellation stops preparation, never an already-dispatched atomic delete.
                org.sqlite.ProgressHandler.clearHandler(database)
                committing()
                sessions.forEach { session ->
                    if ("part" in tables) HistoryDatabase.execute(database, "DELETE FROM part WHERE session_id=? OR message_id IN (SELECT id FROM message WHERE session_id=?)", session.id, session.id)
                    for (table in listOf("message", "todo", "session_share")) if (table in tables) HistoryDatabase.execute(database, "DELETE FROM $table WHERE session_id=?", session.id)
                }
                // Remove children before parents even when foreign keys use RESTRICT rather than CASCADE.
                val pending = sessions.associateBy { it.id }.toMutableMap()
                while (pending.isNotEmpty()) {
                    val leaves = pending.values.filter { candidate -> pending.values.none { it.parentID == candidate.id } }
                    require(leaves.isNotEmpty()) { "Cyclic legacy session tree" }
                    leaves.forEach { session ->
                        require(HistoryDatabase.execute(database, "DELETE FROM session WHERE id=?", session.id) == 1) { "Legacy deletion did not remove the expected session" }
                        pending.remove(session.id)
                    }
                }
                for (table in legacyTables.filter { it in tables }) require(HistoryDatabase.query(database, "PRAGMA foreign_key_check($table)") { true }.isEmpty()) { "Legacy foreign-key validation failed" }
            }
        }
    }
}
