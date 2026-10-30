package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Exact metadata checks only. Discovery is reserved for deliberate legacy open actions. */
internal class CitationPaths(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val expires: Long, val path: String?)
    private val cache = linkedMapOf<String, Entry>()

    fun validate(body: JsonElement?, defaultDirectory: String?, authorize: (String?, String) -> Boolean,
        cancelled: () -> Boolean = { false }): JsonArray {
        val record = body.asObjectOrNull() ?: error("Invalid citation paths")
        val paths = record.arr("paths") ?: error("Invalid citation paths")
        require(paths.size() <= 64 && paths.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length in 1..4096 }) {
            "Invalid citation paths"
        }
        val session = record.text("sessionID")
        require(!record.has("sessionID") || session != null && session.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid citation session" }
        val directory = record.text("directory") ?: defaultDirectory
        require(!record.has("directory") || record.text("directory") != null && directory != null && directory.length <= 4096) { "Invalid citation directory" }
        if (directory != null) require(Path.of(directory).isAbsolute && authorize(session, directory)) { "Session workspace folder is not open" }
        return Json.array(paths.map { value ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Citation check cancelled")
            val input = runCatching { Path.of(value.asString.trim()) }.getOrNull()
            val root = directory?.let(Path::of)?.normalize()
            val path = input?.let { if (it.isAbsolute) it.normalize() else root?.resolve(it)?.normalize()?.takeIf { target -> target.startsWith(root) } }
            if (path == null || value.asString.isBlank()) null else {
                val key = path.toString()
                val cached = synchronized(cache) { cache[key]?.takeIf { it.expires > now() } }
                if (cached != null) cached.path else {
                    val found = if (Files.isRegularFile(path) || Files.isDirectory(path)) key else null
                    synchronized(cache) {
                        cache.remove(key)
                        cache[key] = Entry(now() + if (found == null) 1_000 else 15_000, found)
                        if (cache.size > 2048) cache.remove(cache.keys.first())
                    }
                    found
                }
            }
        }).also { if (cancelled()) throw CancellationException("Citation check cancelled") }
    }

    companion object {
        fun withGuidance(body: JsonElement?, directory: String?): JsonElement? {
            val record = body.asObjectOrNull() ?: return body
            return record.deepCopy().apply {
                val existing = record.str("system").orEmpty()
                val guidance = "When referencing existing local files or folders, use complete paths relative to the session working directory${directory?.let { " ($it)" }.orEmpty()}. Do not use a basename unless the file is directly in that directory, or paths relative to a deeper subdirectory. For locations outside that directory, use absolute paths. File citations are linked only when the exact path exists. Include line ranges when relevant. Label hypothetical paths and placeholders as examples and keep them in fenced code blocks, not file links."
                addProperty("system", listOf(existing, guidance).filter { it.isNotBlank() }.joinToString("\n\n"))
            }
        }
    }
}
