package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.WorkspacePaths
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Session-owned citations, with bounded suffix lookup rather than cross-project guesses. */
internal object FileReferences {
    fun open(payload: JsonObject, authorizeDirectory: (String, String) -> Boolean,
        open: (String, Int?, Int?, String?, String?) -> String): String {
        val path = payload.text("path") ?: error("Missing file path")
        require(path.length <= 4096) { "File path is too long" }
        fun parseLine(name: String): Int? {
            val value = payload.get(name) ?: return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid $name" }
            val number = value.asDouble
            require(number.isFinite() && number == number.toInt().toDouble()) { "Invalid $name" }
            return number.toInt()
        }
        val line = parseLine("line")
        val endLine = parseLine("endLine")
        require(endLine == null || line != null && line >= 1 && endLine >= line) { "Invalid file line range" }
        val kind = payload.str("kind")
        require(kind == null || kind in listOf("auto", "file", "directory")) { "Invalid file kind" }
        val id = payload.text("sessionID")
        val directory = payload.text("directory")
        require(!payload.has("sessionID") || id != null && id.length <= 512 && id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session ID" }
        require(!payload.has("directory") || directory != null && directory.length <= 4096 &&
            directory.none(Character::isISOControl) && Path.of(directory).isAbsolute) { "Invalid session directory" }
        val scope = if (id != null && directory != null) {
            require(authorizeDirectory(id, directory)) { "Session workspace folder is not open" }
            directory
        } else null
        return open(path, line, endLine, kind, scope)
    }

    /** Match the current history scope using fresh server records, never browser-supplied ownership. */
    fun authorizeDirectory(id: String, directory: String, roots: List<String>, historyScope: String,
        readSession: () -> JsonObject?, readProject: (String) -> JsonObject?): Boolean {
        if (roots.any { WorkspacePaths.isSame(it, directory) }) return true
        if (historyScope == "directory") return false
        val session = readSession() ?: return false
        if (session.str("id") != id || !WorkspacePaths.isSame(session.str("directory"), directory) ||
            session.obj("time").num("archived") != null) return false
        return roots.any { root ->
            val project = if (historyScope == "project") readProject(root) else null
            if (project.str("vcs") == "git") project.text("id")?.let { it == session.str("projectID") } == true
            else Path.of(directory).normalize().startsWith(Path.of(root).normalize())
        }
    }

    fun resolve(path: String, sessionDirectory: String?, roots: List<String>, cancelled: () -> Boolean = { false }): List<Path> {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("File lookup cancelled")
        val input = path.trim()
        if (input.isEmpty()) return emptyList()
        val parsed = Path.of(input)
        val root = (sessionDirectory ?: roots.firstOrNull())?.let(Path::of)
        val exact = (if (parsed.isAbsolute) parsed else root?.resolve(parsed) ?: parsed).normalize()
        if (Files.exists(exact)) return listOf(exact)
        val suffix = if (parsed.isAbsolute) parsed.fileName?.toString().orEmpty()
            else input.replace('\\', '/').replace(Regex("^(\\./)+"), "")
        if (suffix.matches(Regex("[\\w.@+ -]+(?:/[\\w.@+ -]+)*")) && suffix.split('/').none { it == "." || it == ".." }) {
            val searchRoot = if (parsed.isAbsolute) exact.parent?.takeIf { it.parent != null } else root
            if (searchRoot != null) {
                val matches = find(searchRoot, suffix, cancelled)
                if (matches.isNotEmpty()) return matches
            }
        }
        // Only the session's containing workspace is a valid last resort.
        if (!parsed.isAbsolute && sessionDirectory != null) {
            val workspace = roots.map(Path::of).filter { Path.of(sessionDirectory).normalize().startsWith(it.normalize()) }
                .maxByOrNull { it.nameCount }
            if (workspace != null) {
                val relative = input.removePrefix("./").removePrefix("${workspace.fileName}/")
                val fallback = workspace.resolve(relative).normalize()
                if (Files.exists(fallback)) return listOf(fallback)
            }
        }
        return emptyList()
    }

    private fun find(root: Path, suffix: String, cancelled: () -> Boolean): List<Path> {
        val ignored = setOf(".git", ".next", ".nuxt", ".output", "node_modules", "dist", "build", "coverage", "out")
        val pending = ArrayDeque<Path>().apply { add(root) }
        val matches = mutableListOf<Path>()
        var visited = 0
        while (pending.isNotEmpty()) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("File lookup cancelled")
            val directory = pending.removeFirst()
            try {
                Files.newDirectoryStream(directory).use { entries ->
                    for (entry in entries) {
                        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("File lookup cancelled")
                        if (++visited > 20_000) return emptyList()
                        if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                            if (entry.fileName.toString() !in ignored) pending.add(entry)
                        } else if (entry.toString().replace('\\', '/').endsWith("/$suffix")) {
                            matches.add(entry)
                            if (matches.size == 100) return matches.sorted()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // The local fallback may encounter unreadable or concurrently removed directories.
            }
        }
        return matches.sorted()
    }

    fun lineRange(line: Int, endLine: Int?, lineCount: Int): IntRange {
        val start = (line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        return start..(endLine?.minus(1) ?: start).coerceIn(start, (lineCount - 1).coerceAtLeast(start))
    }
}
