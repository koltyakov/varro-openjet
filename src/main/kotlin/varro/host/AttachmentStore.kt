package varro.host

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.long
import varro.protocol.str
import varro.server.WorkspacePaths
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/** Durable attachment copies for browser drops that do not expose a local path. */
class AttachmentStore(private val root: Path, private val workspace: () -> String?) {
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
        return describe(target.toString()) ?: error("Could not store attachment")
    }

    companion object { private const val MAX_BYTES = 20L * 1024 * 1024 }
}
