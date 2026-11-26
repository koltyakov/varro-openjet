package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException

/**
 * Workspace-authorized raster images, plus the user's own files in temporary folders where agents
 * save screenshots and scratch output. No browser file URLs or ambient network requests.
 */
internal class LocalImages(
    private val thumbnails: ImageThumbnails = ImageThumbnails(),
    /** False for the shared application service, which outlives any one view. */
    private val ownsThumbnails: Boolean = true,
    private val temporaryDirectories: List<String> = listOfNotNull(System.getProperty("java.io.tmpdir"),
        "/tmp".takeUnless { com.sun.jna.Platform.isWindows() }),
    /** Shared temp folders accept files from any account; only the user's own are shown. Null skips the check. */
    private val isOwnFile: (Path) -> Boolean = ::ownedByCurrentUser,
) : AutoCloseable {
    @Volatile private var closed = false

    fun load(body: JsonElement?, defaultDirectory: String?, roots: List<String>,
        authorize: (String?, String) -> Boolean, cancelled: () -> Boolean = { false }): JsonObject {
        fun check() { if (closed || cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Local image request cancelled") }
        check()
        val record = body.asObjectOrNull() ?: error("Invalid local image request")
        val source = record.str("path")?.let(::parsePath) ?: error("Invalid local image path")
        val directory = record.text("directory") ?: defaultDirectory ?: error("No open workspace for local image")
        require(!record.has("directory") || record.text("directory") != null) { "Invalid local image directory" }
        require(directory.length <= 4096 && directory.none(Character::isISOControl) && !networkPath(directory) &&
            Path.of(directory).isAbsolute) { "Invalid local image directory" }
        val session = record.text("sessionID")
        require(!record.has("sessionID") || session?.matches(Regex("[A-Za-z0-9_-]{1,512}")) == true) { "Invalid local image session" }
        require(authorize(session, directory)) { "Session workspace folder is not open" }
        require(roots.any { varro.server.WorkspacePaths.isSame(it, directory) }) { "Local image is not in an open workspace" }
        require(source.replace('\\', '/').split('/').none { it == ".." }) { OUTSIDE_ALLOWED_ROOTS }
        check()
        // Resolve symlinks before checking containment. Sibling content roots are allowed,
        // but an absolute path or a symlink cannot escape all authorized roots.
        val orderedRoots = listOf(directory) + roots.filterNot { varro.server.WorkspacePaths.isSame(it, directory) }
        val allowed = orderedRoots.filterNot(::networkPath).mapNotNull { runCatching { Path.of(it).toRealPath() }.getOrNull() }
        val input = Path.of(source)
        val path = if (input.isAbsolute) input.toRealPath() else allowed.firstNotNullOfOrNull { root ->
            runCatching { root.resolve(input).toRealPath().takeIf { it.startsWith(root) && Files.isRegularFile(it) } }.getOrNull()
        } ?: error(OUTSIDE_ALLOWED_ROOTS)
        if (!allowed.any(path::startsWith)) {
            // Only an absolute path whose canonical location stays strictly inside a temp root.
            require(input.isAbsolute && temporaryRoots().any { path != it && path.startsWith(it) } && isOwnFile(path)) { OUTSIDE_ALLOWED_ROOTS }
        }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && attributes.size() in 1..ImageThumbnails.MAX_INPUT_BYTES.toLong()) {
            "Local image exceeds the image size limit or is not a regular file"
        }
        check()
        val bytes = readFile(path, ::check)
        check()
        val format = format(bytes) ?: error("Only raster images are supported")
        val original = "data:image/$format;base64," + Base64.getEncoder().encodeToString(bytes)
        val url = if (record.bool("original") == true) original else {
            val key = "local:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            thumbnails.get(key, { original }, { closed || cancelled() }) ?: error("Could not generate local image preview")
        }
        check()
        return Json.obj("url" to url, "path" to path.toString(), "mime" to url.substringAfter("data:").substringBefore(';'))
    }

    override fun close() { closed = true; if (ownsThumbnails) thumbnails.dispose() }

    /** Canonical temp roots; a filesystem root never counts as a temp folder. */
    private fun temporaryRoots(): List<Path> = temporaryDirectories.mapNotNull { directory ->
        runCatching { Path.of(directory).toRealPath() }.getOrNull()?.takeIf { it.parent != null }
    }.distinct()

    companion object {
        private const val OUTSIDE_ALLOWED_ROOTS = "Local image is not in an open workspace or temporary folder"

        /** Windows has no POSIX owner IDs, and its temp folder is already per-user. */
        internal fun ownedByCurrentUser(path: Path): Boolean {
            if (!com.sun.jna.Platform.isMac() && !com.sun.jna.Platform.isLinux()) return true
            val owner = runCatching { Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as? Int }.getOrNull() ?: return false
            return owner == libc.getuid()
        }

        private interface LibC : com.sun.jna.Library {
            fun getuid(): Int
            fun open(path: String, flags: Int): Int
            fun lseek(fd: Int, offset: Long, whence: Int): Long
            fun read(fd: Int, buffer: ByteArray, size: Long): Long
            fun close(fd: Int): Int
        }
        private val libc: LibC by lazy { com.sun.jna.Native.load(com.sun.jna.Platform.C_LIBRARY_NAME, LibC::class.java) }

        /**
         * `O_NOFOLLOW | O_NONBLOCK` for Linux. The values are per-architecture:
         * x86 uses 0400000 for `O_NOFOLLOW`, while arm, aarch64 and powerpc use
         * 0100000 (0400000 there is `O_LARGEFILE`, which would silently follow links).
         */
        internal fun linuxNoFollowNonBlock(arch: String): Int {
            val noFollow = if (arch.startsWith("aarch64") || arch.startsWith("arm") || arch.startsWith("ppc")) 0x8000 else 0x20000
            return noFollow or 0x800
        }

        internal fun readFile(path: Path, check: () -> Unit): ByteArray {
            check()
            if (com.sun.jna.Platform.isMac() || com.sun.jna.Platform.isLinux()) {
                // A regular file can be replaced with a FIFO after the metadata check.
                // O_NONBLOCK keeps that final open from waiting for another process.
                val flags = if (com.sun.jna.Platform.isMac()) 0x100 or 0x4 else linuxNoFollowNonBlock(com.sun.jna.Platform.ARCH)
                val fd = libc.open(path.toString(), flags)
                require(fd >= 0) { "Could not open local image without following links" }
                try {
                    // Seek the opened descriptor, not the pathname. Pipes/devices that
                    // cannot provide a bounded regular-file read fail before allocation.
                    val size = libc.lseek(fd, 0, 2)
                    require(size in 1..ImageThumbnails.MAX_INPUT_BYTES.toLong() && libc.lseek(fd, 0, 0) == 0L) {
                        "Local image exceeds the image size limit or is not a seekable file"
                    }
                    val bytes = ByteArray(size.toInt())
                    val chunk = ByteArray(8192)
                    var offset = 0
                    while (offset < bytes.size) {
                        check()
                        val count = libc.read(fd, chunk, minOf(chunk.size, bytes.size - offset).toLong())
                        require(count >= 0) { "Could not read local image" }
                        if (count == 0L) break
                        chunk.copyInto(bytes, offset, 0, count.toInt())
                        offset += count.toInt()
                    }
                    return bytes.copyOf(offset)
                } finally { libc.close(fd) }
            }
            return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { file ->
                val size = file.size()
                require(size in 1..ImageThumbnails.MAX_INPUT_BYTES.toLong()) { "Local image exceeds the image size limit" }
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) { check(); if (file.read(buffer) < 0) break }
                buffer.array().copyOf(buffer.position())
            }
        }

        private fun networkPath(path: String) = Regex("^[\\\\/]{2}").containsMatchIn(path.trimStart())
        internal fun parsePath(source: String): String? {
            if (source.isEmpty() || source.length > 4096 || source.startsWith("//")) return null
            fun decode(value: String) = java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
            val path = if (source.startsWith("file://", ignoreCase = true)) {
                try {
                    val uri = URI(source)
                    if (!uri.host.isNullOrEmpty() && !uri.host.equals("localhost", ignoreCase = true)) return null
                    decode(uri.rawPath).replace(Regex("^/([A-Za-z]:/)"), "$1")
                } catch (_: Exception) { return null }
            } else {
                if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(source) &&
                    !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(source) || source.any { it == '?' || it == '#' }) return null
                try { decode(source) } catch (_: IllegalArgumentException) { source }
            }
            return path.takeIf { it.isNotEmpty() && !networkPath(it) && it.none(Character::isISOControl) }
        }

        private fun format(bytes: ByteArray): String? {
            fun ascii(start: Int, end: Int) = if (bytes.size >= end) String(bytes, start, end - start, Charsets.US_ASCII) else ""
            return when {
                bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "png"
                bytes.size >= 3 && bytes.take(3) == listOf(255, 216, 255).map(Int::toByte) -> "jpeg"
                ascii(0, 6) in listOf("GIF87a", "GIF89a") -> "gif"
                ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "webp"
                ascii(4, 8) == "ftyp" && ascii(8, 12) in listOf("avif", "avis") -> "avif"
                else -> null
            }
        }
    }
}
