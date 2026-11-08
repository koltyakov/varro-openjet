package varro.host

import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Serializes local writers and rejects external edits made while preparing an atomic replacement. */
internal object ProjectConfigEdit {
    private val locks = Array(64) { Any() }

    fun update(file: Path, beforeWrite: () -> Unit = {}, edit: (String) -> String) {
        val path = canonical(file)
        synchronized(locks[(path.hashCode() and Int.MAX_VALUE) % locks.size]) {
            val original = read(path)
            val updated = edit(original.orEmpty())
            if (updated == original.orEmpty()) return
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, ".openjet-config-", ".tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                    val bytes = ByteBuffer.wrap(updated.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                if (Files.exists(path) && Files.getFileStore(path).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path))
                }
                beforeWrite()
                require(read(path) == original) { "OpenCode configuration changed while saving. Try again." }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    private fun read(path: Path): String? {
        if (!Files.exists(path)) return null
        require(Files.size(path) <= 4 * 1024 * 1024) { "OpenCode project configuration is too large" }
        return Files.readString(path)
    }

    internal fun canonical(file: Path): Path {
        val absolute = file.toAbsolutePath().normalize()
        if (Files.exists(absolute)) return absolute.toRealPath()
        val parent = generateSequence(absolute.parent) { it.parent }.firstOrNull(Files::exists) ?: return absolute
        return parent.toRealPath().resolve(parent.relativize(absolute))
    }
}
