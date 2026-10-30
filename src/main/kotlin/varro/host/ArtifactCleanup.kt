package varro.host

import varro.protocol.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Only journaled, generated artifacts. External files and paths appearing in prose are never targets. */
internal object ArtifactCleanup {
    private fun dropRoot(): Path = Path.of(System.getProperty("java.io.tmpdir"), "varro-drops").toAbsolutePath().normalize()

    fun isGeneratedFile(path: Path, dataDirectory: Path): Boolean {
        val target = path.toAbsolutePath().normalize()
        return target.parent == dataDirectory.toAbsolutePath().normalize().resolve("tool-output") &&
            target.fileName.toString().matches(Regex("tool_[A-Za-z0-9._-]+")) ||
            target.parent?.parent == dropRoot() && target.parent.fileName.toString().matches(Regex("drop-[A-Za-z0-9_-]+")) &&
            target.fileName.toString().matches(Regex("[0-9]+-[a-f0-9]{32}-.+"))
    }

    fun clean(archive: UsageArchive, entries: List<UsageArchive.Pending>, check: () -> Unit = {}, progress: (String) -> Unit = {}) {
        if (entries.isEmpty()) return
        val existing = entries.flatMap { it.paths }.filter { Files.exists(it, NOFOLLOW_LINKS) }.toSet()
        val unresolved = existing.map { it.fileName.toString() }.toMutableSet()
        val referenced = mutableSetOf<String>()
        var lastProgress = System.nanoTime()
        fun scanCheck() {
            check()
            check(System.nanoTime() - lastProgress < TimeUnit.SECONDS.toNanos(60)) { "Artifact reference scan made no row progress for 60 seconds" }
        }
        if (unresolved.isNotEmpty() && Files.exists(archive.path)) HistoryDatabase.open(archive.path, checkCancelled = ::scanCheck, seconds = 600).use { database ->
            HistoryDatabase.execute(database, "BEGIN")
            val tables = HistoryDatabase.tables(database)
            var scanned = 0L
            // One compiled rejection pass per row, not one full-table scan per filename.
            fun matcher() = Regex(unresolved.joinToString("|") { Regex.escape(it) })
            var matcher = matcher()
            for (table in listOf("message", "part", "session_message", "session_part", "session_inbox")) {
                if (table !in tables || unresolved.isEmpty()) continue
                progress("Checking shared files in $table")
                database.createStatement().use { statement ->
                    statement.executeQuery("SELECT ${if (table == "session_inbox") "payload" else "data"} FROM $table").use { rows ->
                        while (rows.next()) {
                            scanCheck()
                            val data = rows.getString(1) ?: error("Cannot inspect non-text artifact references in $table")
                            lastProgress = System.nanoTime()
                            scanned++
                            if (matcher.containsMatchIn(data)) {
                                val found = unresolved.filter { data.contains(it) }
                                referenced.addAll(found); unresolved.removeAll(found.toSet())
                                if (unresolved.isEmpty()) break
                                if (found.isNotEmpty()) matcher = matcher()
                            }
                            if (scanned % 1000 == 0L) progress("Checked $scanned history rows for shared files")
                        }
                    }
                }
            }
        }
        // No removal is allowed until every remaining history source has been scanned successfully.
        progress("Cleaning unused files")
        for (entry in entries) {
            check()
            val safe = entry.paths.filter { it !in existing || it.fileName.toString() !in referenced }
            for (path in safe) {
                check()
                remove(entry, path, archive.path.toAbsolutePath().parent, check)
            }
            archive.finish(entry.session.id, safe, check)
        }
    }

    private fun remove(entry: UsageArchive.Pending, path: Path, dataDirectory: Path, check: () -> Unit) {
        val target = path.toAbsolutePath().normalize()
        val session = entry.session
        val workspace = entry.directories.map { Path.of(it).toAbsolutePath().normalize() }
            .firstOrNull { target == it.resolve(".opencode/attachments/${session.id}") }
        val root = workspace ?: when {
            target == dataDirectory.resolve("storage/session_diff/${session.id}.json") -> dataDirectory
            isGeneratedFile(target, dataDirectory) && target.parent?.parent == dropRoot() -> dropRoot().parent
            isGeneratedFile(target, dataDirectory) -> dataDirectory
            else -> error("Refusing to remove a non-session artifact: $path")
        }
        require(target.startsWith(root)) { "Invalid artifact path" }
        var current = root
        require(!Files.isSymbolicLink(current)) { "Refusing artifact symlink: $current" }
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Refusing artifact symlink: $current" }
        }
        if (!Files.exists(target, NOFOLLOW_LINKS)) return
        if (target.parent?.parent == dropRoot()) {
            val markerPath = target.parent.resolve(".varro-owner.json")
            require(Files.isRegularFile(markerPath, NOFOLLOW_LINKS) && Files.size(markerPath) <= 4096) { "Invalid temporary attachment owner marker" }
            val marker = Json.parse(Files.readString(markerPath)).asObjectOrNull()
            require(marker.int("version") == 1 && (marker.long("pid") ?: 0) > 0 && (marker.num("createdAt") ?: -1.0).let { it.isFinite() && it >= 0 }) { "Invalid temporary attachment owner marker" }
        }
        if (workspace == null || !Files.isDirectory(target, NOFOLLOW_LINKS)) Files.deleteIfExists(target)
        else {
            // walk does not follow links. Refuse even nested links instead of unlinking unknown targets.
            Files.walk(target).use { paths ->
                val entries = paths.toList()
                require(entries.none(Files::isSymbolicLink)) { "Refusing a symlink inside session attachments" }
                entries.sortedByDescending { it.nameCount }.forEach { check(); Files.deleteIfExists(it) }
            }
        }
    }
}
