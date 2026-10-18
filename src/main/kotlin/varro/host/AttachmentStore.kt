package varro.host

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.long
import varro.protocol.str
import varro.protocol.bool
import varro.protocol.int
import varro.server.WorkspacePaths
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/** Durable attachment copies for browser drops that do not expose a local path. */
class AttachmentStore(private val root: Path, private val workspace: () -> String?) {
    /** Adjacent batches preserve the user's mixed PDF/file selection order. */
    fun pick(paths: List<String>, post: (String, com.google.gson.JsonElement) -> Unit): Boolean {
        var total = 0L
        var rejected = false
        var groupType: String? = null
        val group = mutableListOf<JsonObject>()
        fun flush() {
            groupType?.let { if (group.isNotEmpty()) post(it, Json.array(group)) }
            group.clear()
        }
        for (raw in paths.distinct().take(100)) {
            val pdf = raw.lowercase().endsWith(".pdf")
            try {
                val description = describe(raw) ?: error("File unavailable")
                val path = Path.of(description.str("path")!!)
                val item = if (pdf && !Files.isDirectory(path)) {
                    val size = Files.size(path)
                    require(size > 0 && total + size <= varro.server.NativePdfInput.MAX_BYTES && path.fileName.toString().length <= varro.server.NativePdfInput.MAX_FILENAME)
                    val bytes = Files.newInputStream(path).use { it.readNBytes(size.toInt() + 1) }
                    require(bytes.size.toLong() == size && varro.server.NativePdfInput.isPdf(bytes))
                    total += size
                    Json.obj("id" to UUID.randomUUID().toString(), "filename" to path.fileName.toString(), "size" to size,
                        "mime" to varro.server.NativePdfInput.MIME, "url" to "data:application/pdf;base64,${Base64.getEncoder().encodeToString(bytes)}", "contextFile" to description)
                } else description
                val type = if (item.has("url")) "pdfs/picked" else "files/dropped"
                if (type != groupType) { flush(); groupType = type }
                group += item
            } catch (_: Exception) { if (pdf) rejected = true }
        }
        flush()
        return rejected
    }

    fun describe(raw: String): JsonObject? {
        val path = if (raw.startsWith("file:")) Path.of(URI(raw)) else Path.of(raw).let {
            if (it.isAbsolute) it else workspace()?.let { directory -> Path.of(directory).resolve(it) } ?: it
        }
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.exists(normalized)) return null
        return Json.obj(
            "path" to normalized.toString(),
            "relativePath" to (WorkspacePaths.relativeWithin(normalized.toString(), workspace()) ?: normalized.fileName.toString()),
            "type" to if (Files.isDirectory(normalized)) "directory" else "file",
        )
    }

    fun store(payload: JsonObject): JsonObject {
        val size = payload.long("size") ?: error("Attachment size is missing")
        require(size in 0..MAX_BYTES) { "Attachments must be at most 20 MiB" }
        val encoded = payload.str("content") ?: error("Attachment content is missing")
        require(encoded.length <= (MAX_BYTES * 4 / 3 + 4)) { "Attachment is too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size.toLong() == size) { "Attachment size does not match its content" }
        val name = payload.str("name").orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(160).takeIf { it != "." && it != ".." && it.isNotBlank() } ?: "attachment"
        val directory = root.resolve(UUID.randomUUID().toString())
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        Files.write(target, bytes)
        return (describe(target.toString()) ?: error("Could not store attachment")).apply {
            payload.get("database")?.takeIf { it.isJsonObject }?.let { add("database", it.deepCopy()) }
        }
    }

    companion object {
        private const val MAX_BYTES = 20L * 1024 * 1024

        fun databaseContent(snapshot: JsonObject): JsonObject {
            val bytes = snapshot.toString().toByteArray(Charsets.UTF_8)
            return Json.obj(
                "name" to "${snapshot.str("name") ?: "database"}-context.json",
                "size" to bytes.size,
                "content" to Base64.getEncoder().encodeToString(bytes),
                "database" to Json.obj(
                    "name" to (snapshot.str("name") ?: "database"),
                    "dataSource" to snapshot.str("dataSource"),
                    "scope" to (snapshot.str("scope") ?: "table"),
                    "rowCount" to (snapshot.getAsJsonArray("rows")?.size() ?: 0),
                    "selectedRowCount" to (snapshot.int("selectedRowCount") ?: 0),
                    "truncated" to (snapshot.bool("truncated") ?: false),
                    "pendingChanges" to (snapshot.bool("pendingChanges") ?: false),
                    "cellEditing" to (snapshot.bool("cellEditing") ?: false),
                ),
            )
        }
    }
}
