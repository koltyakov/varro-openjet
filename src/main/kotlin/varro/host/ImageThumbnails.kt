package varro.host

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.ImageReader
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Kotlin/JVM counterpart of Varro's WASM thumbnail worker. Shared by all IDE views. */
@Service(Service.Level.APP)
class ImageThumbnails(
    private val now: () -> Long = System::currentTimeMillis,
) : Disposable {
    private data class Entry(val url: String, val expires: Long)
    private val cache = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val references = LinkedHashMap<String, String>(64, 0.75f, true)
    private var cacheBytes = 0
    private val conversion = ReentrantLock(true)
    @Volatile private var disposed = false
    private val sweeper = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "varro-thumbnail-cache").apply { isDaemon = true }
    }.apply { scheduleWithFixedDelay({ synchronized(cache) { expire() } }, 1, 1, TimeUnit.MINUTES) }

    /** Acquire the decoder before fetching an original, so waiting views retain no image bodies. */
    fun get(reference: String, source: () -> String?, isCancelled: () -> Boolean = { false }): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        fun checkCancelled() {
            if (disposed || isCancelled()) throw CancellationException("Thumbnail request cancelled")
        }
        while (true) {
            checkCancelled()
            if (conversion.tryLock(50, TimeUnit.MILLISECONDS)) break
            if (System.nanoTime() >= deadline) return null
        }
        try {
            checkCancelled()
            synchronized(cache) {
                expire()
                references[reference]?.let { key -> cache[key]?.let { return it.url } }
            }
            val url = source() ?: return null
            checkCancelled()
            if (url.length > MAX_INPUT_BYTES * 4 / 3 + 64) return null
            val header = DATA_URL.find(url) ?: return null
            val encoded = url.substring(header.value.length)
            if (encoded.isEmpty() || encoded.length > MAX_INPUT_BYTES * 4 / 3) return null
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
            if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return null
            val key = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            synchronized(cache) {
                expire()
                cache[key]?.let { rememberReference(reference, key); return it.url }
            }
            val decodeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            val result = try {
                convert(bytes, header.groupValues[1].lowercase()) {
                    disposed || isCancelled() || System.nanoTime() >= decodeDeadline
                }
            } catch (_: Exception) {
                // Unsupported or damaged images keep the placeholder and remain retryable.
                null
            }
            checkCancelled()
            if (result != null && System.nanoTime() < decodeDeadline) synchronized(cache) {
                if (!disposed) {
                    cache[key] = Entry(result, now() + CACHE_TTL_MS)
                    cacheBytes += result.length * 2
                    rememberReference(reference, key)
                    while (cache.size > 64 || cacheBytes > 4 * 1024 * 1024) remove(cache.keys.first())
                }
            }
            return result
        } finally {
            conversion.unlock()
        }
    }

    private fun convert(bytes: ByteArray, format: String, cancelled: () -> Boolean): String? {
        // Instantiate the bundled plugin directly. Registering it in ImageIO's global registry
        // would retain the plugin classloader after an IDE plugin reload.
        val reader = if (format == "webp") WebPImageReaderSpi().createReaderInstance()
            else ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull() ?: return null
        try {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                reader.setInput(input, true, false)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width !in 1..16_384 || height !in 1..16_384 || width.toLong() * height > 16_000_000) return null
                if (cancelled()) return null
                reader.addIIOReadProgressListener(object : IIOReadProgressListener {
                    override fun imageProgress(source: ImageReader, percentageDone: Float) { if (cancelled()) source.abort() }
                    override fun imageStarted(source: ImageReader, imageIndex: Int) = Unit
                    override fun imageComplete(source: ImageReader) = Unit
                    override fun sequenceStarted(source: ImageReader, minIndex: Int) = Unit
                    override fun sequenceComplete(source: ImageReader) = Unit
                    override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) = Unit
                    override fun thumbnailProgress(source: ImageReader, percentageDone: Float) = Unit
                    override fun thumbnailComplete(source: ImageReader) = Unit
                    override fun readAborted(source: ImageReader) = Unit
                })
                val parameters = reader.defaultReadParam
                if (format == "jpeg") {
                    val sample = (maxOf(width, height) / (MAX_EDGE * 2)).coerceAtLeast(1)
                    parameters.setSourceSubsampling(sample, sample, 0, 0)
                }
                val decoded = reader.read(0, parameters) ?: return null
                try {
                    if (cancelled()) return null
                    val orientation = runCatching {
                        ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
                            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
                    }.getOrNull() ?: 1
                    val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(decoded.width, decoded.height))
                    var preview = resize(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()))
                    try {
                        if (orientation in 2..8) {
                            val oriented = orient(preview, orientation)
                            preview.flush()
                            preview = oriented
                        }
                        repeat(3) {
                            if (cancelled()) return null
                            val output = ByteArrayOutputStream()
                            // PNG is portable on all supported JVMs and preserves alpha. A fresh raster
                            // converts to sRGB and prevents EXIF, comments or other source metadata leaking.
                            MemoryCacheImageOutputStream(output).use { stream ->
                                if (!ImageIO.write(preview, "png", stream)) return null
                            }
                            if (output.size() <= MAX_OUTPUT_BYTES) return "data:image/png;base64," +
                                Base64.getEncoder().encodeToString(output.toByteArray())
                            val smaller = resize(preview, maxOf(1, preview.width / 2), maxOf(1, preview.height / 2))
                            preview.flush()
                            preview = smaller
                        }
                    } finally { preview.flush() }
                } finally { decoded.flush() }
            }
        } finally { reader.dispose() }
        return null
    }

    private fun resize(source: BufferedImage, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(source, 0, 0, width, height, null)
            } finally { graphics.dispose() }
        }

    private fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        val w = source.width
        val h = source.height
        return BufferedImage(if (orientation >= 5) h else w, if (orientation >= 5) w else h, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until h) for (x in 0 until w) {
                val (dx, dy) = when (orientation) {
                    2 -> w - 1 - x to y
                    3 -> w - 1 - x to h - 1 - y
                    4 -> x to h - 1 - y
                    5 -> y to x
                    6 -> h - 1 - y to x
                    7 -> h - 1 - y to w - 1 - x
                    else -> y to w - 1 - x
                }
                setRGB(dx, dy, source.getRGB(x, y))
            }
        }
    }

    private fun expire() { cache.filterValues { it.expires <= now() }.keys.forEach(::remove) }
    private fun remove(key: String) {
        cache.remove(key)?.let { cacheBytes -= it.url.length * 2 }
        references.entries.removeIf { it.value == key }
    }
    private fun rememberReference(reference: String, key: String) {
        references[reference] = key
        while (references.size > 64) references.remove(references.keys.first())
    }

    override fun dispose() {
        disposed = true
        sweeper.shutdownNow()
        synchronized(cache) { cache.clear(); references.clear(); cacheBytes = 0 }
    }

    companion object {
        const val MAX_INPUT_BYTES = 24 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 256 * 1024
        const val MAX_EDGE = 384
        private const val CACHE_TTL_MS = 5 * 60_000L
        private val DATA_URL = Regex("^data:image/(png|jpeg|webp|gif|avif);base64,", RegexOption.IGNORE_CASE)
    }
}
