package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO

class LocalImagesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `paths match the shared local image contract and reject network access before resolution`() {
        for ((source, path) in listOf("artifacts/screenshot.png" to "artifacts/screenshot.png",
            "./screen%20shot.png" to "./screen shot.png", "file:///workspace/screen%23shot.png" to "/workspace/screen#shot.png",
            "file:///C:/workspace/screenshot.png" to "C:/workspace/screenshot.png", "C:\\workspace\\image.png" to "C:\\workspace\\image.png",
            "screen%shot.png" to "screen%shot.png", "a+b.png" to "a+b.png")) assertEquals(path, LocalImages.parsePath(source))
        for (source in listOf("", "https://images.test/image.png", "http://images.test/image.png", "//images.test/image.png",
            "file://images.test/image.png", "javascript:alert(1)", "data:image/png;base64,AAAA", "image.png?query=1", "image.png#fragment",
            "image%00.png", "file:///image%0a.png", "\\\\attacker.example\\share\\image.png", "%5C%5Cattacker.example%5Cimage.png",
            " \\\\attacker.example\\image.png", "%2F%2Fattacker.example/image.png", "\\/attacker.example/image.png",
            "file:////attacker.example/image.png", "\\\\?\\C:\\image.png", "a".repeat(4097))) {
            assertNull(source, LocalImages.parsePath(source))
        }
        LocalImages().use { images ->
            assertThrows(IllegalStateException::class.java) {
                images.load(Json.obj("path" to "\\\\attacker.example\\image.png"), "/missing", emptyList(), { _, _ -> error("Must not authorize network path") })
            }
        }
        assertTrue(ApiRoutes.isAllowed("POST", "/varro/local-image"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/local-image"))
        assertFalse(ApiRoutes.isAllowed("POST", "/varro/local-image?path=image.png"))
    }

    @Test fun `authorized raster reads use bounded thumbnails and explicit originals with sibling roots`() {
        val root = temporary.newFolder("root").toPath()
        val sibling = temporary.newFolder("sibling").toPath()
        val file = sibling.resolve("screen shot.png")
        ImageIO.write(BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB), "png", file.toFile())
        val request = Json.obj("path" to file.toUri().toString(), "directory" to root.toString(), "sessionID" to "ses_one")
        LocalImages().use { images ->
            val roots = listOf(root.toString(), sibling.toString())
            val authorize: (String?, String) -> Boolean = { id, directory -> id == "ses_one" && directory == root.toString() }
            val preview = images.load(request, null, roots, authorize)
            assertEquals(file.toRealPath().toString(), preview.str("path"))
            assertEquals("image/png", preview.str("mime"))
            val bytes = Base64.getDecoder().decode(preview.str("url")!!.substringAfter(','))
            assertTrue(bytes.size <= ImageThumbnails.MAX_OUTPUT_BYTES)
            val decoded = ImageIO.read(bytes.inputStream())
            assertEquals(384, decoded.width)
            assertEquals(preview, images.load(request, null, roots, authorize))
            assertEquals(preview, images.load(request, null, roots + root.resolve("missing-root").toString(), authorize))
            val relative = request.deepCopy().apply { addProperty("path", "screen%20shot.png") }
            assertEquals(preview, images.load(relative, null, roots, authorize))
            request.addProperty("original", true)
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file)),
                images.load(request, null, roots, authorize).str("url"))
        }
    }

    @Test fun `authorization containment symlinks format size cancellation and disposal fail closed`() {
        val root = temporary.newFolder("workspace").toPath()
        val outside = temporary.newFile("outside.png").toPath()
        Files.writeString(outside, "<svg onload='bad()'></svg>")
        val link = root.resolve("escape.png")
        Files.createSymbolicLink(link, outside)
        LocalImages().use { images ->
            fun load(path: String, extra: com.google.gson.JsonObject = Json.obj()) = images.load(extra.apply { addProperty("path", path) },
                root.toString(), listOf(root.toString()), { _, _ -> true })
            for (path in listOf(outside.toString(), link.toString(), "../outside.png")) {
                assertThrows(IllegalArgumentException::class.java) { load(path) }
            }
            val svg = root.resolve("pretend.png")
            Files.writeString(svg, "<svg onload='bad()'></svg>")
            assertThrows(IllegalStateException::class.java) { load("pretend.png", Json.obj("original" to true)) }
            val empty = root.resolve("empty.png")
            Files.createFile(empty)
            assertThrows(IllegalArgumentException::class.java) { load("empty.png") }
            java.io.RandomAccessFile(empty.toFile(), "rw").use { it.setLength(ImageThumbnails.MAX_INPUT_BYTES.toLong() + 1) }
            assertThrows(IllegalArgumentException::class.java) { load("empty.png") }
            assertThrows(IllegalArgumentException::class.java) { load(root.toString()) }
            assertThrows(IllegalArgumentException::class.java) {
                images.load(Json.obj("path" to "pretend.png"), root.toString(), listOf(root.toString()), { _, _ -> false })
            }
            assertThrows(CancellationException::class.java) {
                images.load(Json.obj("path" to "missing.png"), null, emptyList(), { _, _ -> error("Must not resolve") }, { true })
            }
            images.close()
            assertThrows(CancellationException::class.java) { load("pretend.png") }
        }
    }

    @Test(timeout = 5000) fun `a final symlink or FIFO replacement cannot follow links or block the file open`() {
        org.junit.Assume.assumeTrue(com.sun.jna.Platform.isMac() || com.sun.jna.Platform.isLinux())
        val target = temporary.newFile("target.png").toPath()
        Files.write(target, byteArrayOf(1, 2, 3))
        val link = temporary.root.toPath().resolve("replaced.png")
        Files.createSymbolicLink(link, target)
        assertThrows(IllegalArgumentException::class.java) { LocalImages.readFile(link) {} }
        Files.delete(link)
        val process = ProcessBuilder("mkfifo", link.toString()).start()
        assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertThrows(IllegalArgumentException::class.java) { LocalImages.readFile(link) {} }
    }

    @Test fun `linux no-follow flag matches each architecture`() {
        assertEquals(0x20000 or 0x800, LocalImages.linuxNoFollowNonBlock("x86-64"))
        assertEquals(0x8000 or 0x800, LocalImages.linuxNoFollowNonBlock("aarch64"))
        assertEquals(0x8000 or 0x800, LocalImages.linuxNoFollowNonBlock("arm"))
        assertEquals(0x8000 or 0x800, LocalImages.linuxNoFollowNonBlock("ppc64le"))
    }
}
