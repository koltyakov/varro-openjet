package varro.store

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class JsonJournal(private val path: Path) {
    fun read(fallback: JsonObject): JsonObject = if (Files.exists(path)) {
        Json.parseOrNull(Files.readString(path)).asObjectOrNull() ?: error("Invalid journal: $path")
    } else fallback

    @Synchronized fun write(value: JsonObject) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "journal-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
