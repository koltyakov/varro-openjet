package varro.host

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory

class ImageThumbnailsTest {
    private var time = 0L
    private val thumbnails = ImageThumbnails { time }
    @After fun close() = thumbnails.dispose()

    @Test fun `large previews are bounded and small transparent images are not upscaled`() {
        val large = thumbnails.get("large", { image(1600, 900) })!!
        assertEquals(384, decode(large).width)
        assertEquals(216, decode(large).height)
        assertTrue(bytes(large).size <= ImageThumbnails.MAX_OUTPUT_BYTES)
        val small = decode(thumbnails.get("small", { image(20, 10, Color(255, 0, 0, 100)) })!!)
        assertEquals(20, small.width)
        assertEquals(10, small.height)
        assertEquals(100, small.getRGB(0, 0).ushr(24))
    }

    @Test fun `jpeg orientation is applied and source metadata is stripped`() {
        val jpeg = bytes(image(40, 20, format = "jpeg"))
        val exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("Exif\u0000\u0000".toByteArray()); putShort(0x4949); putShort(42); putInt(8)
            putShort(1); putShort(0x112); putShort(3); putInt(1); putShort(6); putShort(0); putInt(0)
        }.array()
        val oriented = jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) + exif + jpeg.drop(2).toByteArray()
        val preview = thumbnails.get("oriented", { url(oriented, "jpeg") })!!
        assertEquals(20, decode(preview).width)
        assertEquals(40, decode(preview).height)
        assertNull(ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes(preview))).getFirstDirectoryOfType(ExifIFD0Directory::class.java))
    }

    @Test fun `animated gif uses only the first frame and bundled webp decoding works`() {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                for (color in listOf(Color.RED, Color.BLUE)) {
                    writer.writeToSequence(IIOImage(decode(image(20, 10, color)), null, null), writer.defaultWriteParam)
                }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
        val poster = decode(thumbnails.get("gif", { url(output.toByteArray(), "gif") })!!)
        assertEquals(Color.RED.rgb, poster.getRGB(0, 0))
        val webp = "data:image/webp;base64,UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAEAcQERGIiP4HAA=="
        assertEquals(1, decode(thumbnails.get("webp", { webp })!!).width)
    }

    @Test fun `unsupported damaged oversized and pixel bomb inputs keep placeholders and failures can retry`() {
        for (value in listOf("https://example.com/image.png", "data:image/svg+xml;base64,AAAA", "data:image/png;base64,AAAA", "data:image/png;base64,%%%")) {
            assertNull(thumbnails.get("bad", { value }))
        }
        assertNotNull(thumbnails.get("bad", { image(1, 1) }))
        val bomb = bytes(image(1, 1))
        ByteBuffer.wrap(bomb).putInt(16, 5000).putInt(20, 5000)
        val crc = CRC32().apply { update(bomb, 12, 17) }
        ByteBuffer.wrap(bomb).putInt(29, crc.value.toInt())
        assertNull(thumbnails.get("bomb", { url(bomb, "png") }))
        assertNull(thumbnails.get("oversized", { "data:image/png;base64," + "A".repeat(ImageThumbnails.MAX_INPUT_BYTES * 4 / 3 + 1) }))
    }

    @Test fun `cache hits do not fetch originals or extend expiry and eviction regenerates`() {
        val source = image(10, 10)
        var reads = 0
        fun get(key: String) = thumbnails.get(key, { reads++; source })
        val first = get("first")
        time = 299_999
        assertEquals(first, get("first"))
        assertEquals(1, reads)
        time = 300_000
        assertEquals(first, get("first"))
        assertEquals(2, reads)
        repeat(65) { get("reference-$it") }
        val before = reads
        get("first")
        assertEquals(before + 1, reads)
    }

    @Test fun `cancelling a waiting view never fetches its original or cancels another view`() {
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val active = executor.submit<String?> { thumbnails.get("active", {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); image(2, 2)
            }) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val waiting = executor.submit<Boolean> {
                try { thumbnails.get("waiting", { error("Cancelled caller fetched an image") }, cancelled::get); false }
                catch (_: CancellationException) { true }
            }
            cancelled.set(true)
            assertTrue(waiting.get(5, TimeUnit.SECONDS))
            release.countDown()
            assertNotNull(active.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
        thumbnails.dispose()
        assertThrows(CancellationException::class.java) { thumbnails.get("active", { image(1, 1) }) }
    }

    companion object {
        fun image(width: Int, height: Int, color: Color = Color.RED, format: String = "png"): String {
            val image = BufferedImage(width, height, if (format == "jpeg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { graphics.color = color; graphics.fillRect(0, 0, width, height) } finally { graphics.dispose() }
            val output = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(output).use { assertTrue(ImageIO.write(image, format, it)) }
            image.flush()
            return url(output.toByteArray(), format)
        }
        private fun url(bytes: ByteArray, format: String) = "data:image/$format;base64," + Base64.getEncoder().encodeToString(bytes)
        private fun bytes(url: String) = Base64.getDecoder().decode(url.substringAfter(','))
        private fun decode(url: String): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes(url)))
    }
}
