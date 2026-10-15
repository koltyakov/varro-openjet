package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import okhttp3.*
import okio.Buffer

class RemoteImagesTest {
    private val public = InetAddress.getByName("8.8.8.8")
    private fun png(width: Int = 2, height: Int = 2) = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()

    @Test fun `settings require explicit automatic consent and never trust invalid values`() {
        val settings = varro.settings.VarroSettings()
        assertEquals("ask", settings.remoteImageMode())
        for (mode in listOf("ask", "bad", "")) {
            settings.chatRemoteImages = mode
            assertEquals("ask", settings.remoteImageMode())
            assertThrows(IllegalArgumentException::class.java) { RemoteImages.checkMode(settings.remoteImageMode(), true) }
            RemoteImages.checkMode(settings.remoteImageMode(), false)
        }
        RemoteImages.checkMode("on", true)
        assertThrows(IllegalArgumentException::class.java) { RemoteImages.checkMode("off", false) }
        assertTrue(ApiRoutes.isAllowed("POST", ApiRoutes.Endpoints.REMOTE_IMAGE))
        assertFalse(ApiRoutes.isAllowed("GET", ApiRoutes.Endpoints.REMOTE_IMAGE))
        assertFalse(ApiRoutes.isAllowed("POST", "${ApiRoutes.Endpoints.REMOTE_IMAGE}?directory=/repo"))
    }

    @Test fun `HTTPS boundary pins DNS and disables ambient cookies authentication proxies and redirects`() {
        val url = RemoteImages.parseUrl("https://images.example/a.png")!!
        val request = RemoteImages.imageRequest(url)
        assertEquals("images.example", request.url.host)
        for (header in listOf("Authorization", "Proxy-Authorization", "Cookie", "Referer")) assertNull(request.header(header))
        assertEquals("identity", request.header("Accept-Encoding"))
        val client = RemoteImages.imageClient(public)
        try {
            assertEquals(listOf(public), client.dns.lookup("images.example"))
            assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
            assertSame(CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(Authenticator.NONE, client.authenticator)
            assertSame(Authenticator.NONE, client.proxyAuthenticator)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(15_000, client.callTimeoutMillis)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun `HTTP responses reject nonraster encoded oversized and unbounded bodies`() {
        fun response(type: String = "image/png", encoding: String? = null, bytes: Int = 2, declared: Long = bytes.toLong(), status: Int = 200): Response {
            val body = object : ResponseBody() {
                private val buffer = Buffer().write(ByteArray(bytes))
                override fun contentType(): MediaType? = null
                override fun contentLength() = declared
                override fun source() = buffer
            }
            return Response.Builder().request(RemoteImages.imageRequest(RemoteImages.parseUrl("https://images.example/a")!!))
                .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body).header("Content-Type", type)
                .apply { encoding?.let { header("Content-Encoding", it) } }.build()
        }
        for (type in listOf("image/svg+xml", "text/html", "application/octet-stream")) {
            assertThrows(IllegalStateException::class.java) { RemoteImages.readResponse(response(type = type)) {} }
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteImages.readResponse(response(encoding = "gzip")) {} }
        assertThrows(IllegalArgumentException::class.java) { RemoteImages.readResponse(response(declared = RemoteImages.MAX_BYTES + 1L)) {} }
        assertThrows(IllegalArgumentException::class.java) { RemoteImages.readResponse(response(bytes = RemoteImages.MAX_BYTES + 1, declared = -1)) {} }
        assertThrows(IllegalArgumentException::class.java) { RemoteImages.readResponse(response(status = 404)) {} }
        assertThrows(IllegalStateException::class.java) { RemoteImages.readResponse(response(status = 302)) {} }
        assertTrue(RemoteImages.readResponse(response(type = "IMAGE/PNG; charset=binary")) {} is RemoteImages.Download.Image)
        assertThrows(CancellationException::class.java) { RemoteImages.readResponse(response()) { throw CancellationException() } }
    }

    @Test fun `URLs are bounded HTTPS without credentials and nonstandard ports`() {
        assertEquals("https://images.example/a.png?size=2", RemoteImages.parseUrl("https://IMAGES.example:443/a.png?size=2#preview").toString())
        for (url in listOf("http://example.com/a.png", "file:///a.png", "data:image/png;base64,AA==", "javascript:alert(1)",
            "//example.com/a.png", "https://user:secret@example.com/a.png", "https://example.com:444/a.png",
            "https://example.com/" + "日".repeat(1000), "https://[::ffff:8.8.8.8]/a.png")) assertNull(url, RemoteImages.parseUrl(url))
    }

    @Test fun `only public unicast addresses are admitted including IPv6 transition exclusions`() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) assertTrue(ip, RemoteImages.isPublicAddress(InetAddress.getByName(ip)))
        for (ip in listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "100.64.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "192.0.2.1",
            "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002::1", "3fff::1")) {
            assertFalse(ip, RemoteImages.isPublicAddress(InetAddress.getByName(ip)))
        }
    }

    @Test fun `downloads use checked addresses and cached sanitized thumbnails need no DNS`() {
        val resolves = AtomicInteger()
        val downloads = AtomicInteger()
        RemoteImages(resolve = { resolves.incrementAndGet(); listOf(public) }, download = { url, address, check ->
            check(); assertEquals(public, address); assertEquals("images.example", url.host)
            downloads.incrementAndGet(); RemoteImages.Download.Image(png(), "png")
        }).use { loader ->
            val result = loader.load("https://images.example/a.png#one")
            assertTrue(result.str("url")!!.startsWith("data:image/png;base64,"))
            assertEquals(result, loader.load("https://images.example/a.png#two"))
            assertEquals(1, resolves.get())
            assertEquals(1, downloads.get())
        }
    }

    @Test fun `mixed DNS answers and private redirects never reach a downloader`() {
        val downloads = AtomicInteger()
        RemoteImages(resolve = { listOf(public, InetAddress.getLoopbackAddress()) }, download = { _, _, _ ->
            downloads.incrementAndGet(); error("Must not connect")
        }).use { loader -> assertThrows(IllegalArgumentException::class.java) { loader.load("https://images.example/a.png") } }
        assertEquals(0, downloads.get())
        RemoteImages(resolve = { if (it == "private.example") listOf(InetAddress.getLoopbackAddress()) else listOf(public) }, download = { _, _, _ ->
            downloads.incrementAndGet(); RemoteImages.Download.Redirect("https://private.example/image.png")
        }).use { loader -> assertThrows(IllegalArgumentException::class.java) { loader.load("https://images.example/a.png") } }
        assertEquals(1, downloads.get())
        RemoteImages(resolve = { listOf(public) }, download = { _, _, _ -> RemoteImages.Download.Redirect("http://public.example/image.png") }).use { loader ->
            assertThrows(IllegalStateException::class.java) { loader.load("https://images.example/a.png") }
        }
    }

    @Test fun `invalid oversized and excessive pixel images stay retryable`() {
        for (image in listOf(RemoteImages.Download.Image(byteArrayOf(1, 2), "png"),
            RemoteImages.Download.Image(ByteArray(RemoteImages.MAX_BYTES + 1), "png"), RemoteImages.Download.Image(png(4097, 4096), "png"))) {
            RemoteImages(resolve = { listOf(public) }, download = { _, _, _ -> image }).use { loader ->
                assertThrows(Exception::class.java) { loader.load("https://images.example/a.png") }
            }
        }
        val count = AtomicInteger()
        RemoteImages(resolve = { listOf(public) }, download = { _, _, _ -> count.incrementAndGet(); RemoteImages.Download.Redirect("/next") }).use { loader ->
            assertThrows(IllegalStateException::class.java) { loader.load("https://images.example/a.png") }
            assertEquals(4, count.get())
        }
    }

    @Test(timeout = 10_000) fun `two downloads deduplicate callers and cancel only after the last waiter leaves`() {
        val started = CountDownLatch(2)
        val stopped = CountDownLatch(2)
        val downloads = AtomicInteger()
        val cancelled = AtomicBoolean()
        val sharedWaiting = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        RemoteImages(resolve = { listOf(public) }, download = { _, _, check ->
            downloads.incrementAndGet(); started.countDown()
            try { while (true) { check(); Thread.sleep(10) }; error("unreachable") }
            finally { stopped.countDown() }
        }).use { loader ->
            try {
                val first = executor.submit { loader.load("https://images.example/one") { cancelled.get() } }
                val second = executor.submit { loader.load("https://images.example/two") }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { loader.load("https://images.example/three") }
                val checks = AtomicInteger()
                val shared = executor.submit { loader.load("https://images.example/one") {
                    if (checks.incrementAndGet() >= 2) sharedWaiting.countDown()
                    false
                } }
                assertTrue(sharedWaiting.await(2, TimeUnit.SECONDS))
                cancelled.set(true)
                assertThrows(ExecutionException::class.java) { first.get(2, TimeUnit.SECONDS) }
                assertFalse(shared.isDone)
                assertEquals(2, downloads.get())
                loader.cancel()
                assertThrows(ExecutionException::class.java) { shared.get(2, TimeUnit.SECONDS) }
                assertThrows(ExecutionException::class.java) { second.get(2, TimeUnit.SECONDS) }
                assertTrue(stopped.await(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test(timeout = 5_000) fun `deadline and disposal stop waiting on unresolved DNS without downloading`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val downloads = AtomicInteger()
        val images = RemoteImages(resolve = { started.countDown(); release.await(3, TimeUnit.SECONDS); listOf(public) },
            download = { _, _, _ -> downloads.incrementAndGet(); error("Must not download") }, timeoutMs = 100)
        try {
            assertThrows(CancellationException::class.java) { images.load("https://images.example/a.png") }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            images.close()
            assertThrows(IllegalStateException::class.java) { images.load("https://images.example/a.png") }
            assertEquals(0, downloads.get())
        } finally { release.countDown(); images.close() }
    }
}
