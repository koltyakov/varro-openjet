package varro.host

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import varro.protocol.Json
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Consent-gated public-network images. This client never shares OpenCode credentials or proxies. */
internal class RemoteImages(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val download: (HttpUrl, InetAddress, () -> Unit) -> Download = ::downloadImage,
    private val thumbnails: ImageThumbnails = ImageThumbnails(),
    private val timeoutMs: Long = 15_000,
) : AutoCloseable {
    internal sealed interface Download {
        data class Redirect(val location: String) : Download
        data class Image(val bytes: ByteArray, val format: String) : Download
    }
    private class Pending(val deadline: Long) {
        val cancelled = AtomicBoolean()
        val result = CompletableFuture<JsonObject>()
        var users = 0
        fun check() {
            if (cancelled.get() || System.nanoTime() >= deadline) throw CancellationException("Image download cancelled or timed out")
        }
    }
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
        Thread(task, "varro-remote-image").apply { isDaemon = true }
    })
    private val pending = mutableMapOf<String, Pending>()
    private var closed = false

    fun load(source: String, isCancelled: () -> Boolean = { false }): JsonObject {
        val url = parseUrl(source) ?: error("Only HTTPS image URLs without credentials on port 443 are supported")
        val key = "remote:${url}"
        fun check() { if (isCancelled()) throw CancellationException("Image request cancelled") }
        check()
        val entry = synchronized(pending) {
            check(!closed) { "Remote image loader disposed" }
            thumbnails.peek(key)?.let { return Json.obj("url" to it, "hostname" to url.host) }
            val entry = pending[key] ?: Pending(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)).also { next ->
                check(pending.size < 2) { "Two images are already loading. Try again shortly." }
                pending[key] = next
                try {
                    workers.execute {
                        try { next.result.complete(fetch(url, key, next)) }
                        catch (failure: Throwable) { next.result.completeExceptionally(failure) }
                        finally { synchronized(pending) { if (pending[key] === next) pending.remove(key) } }
                    }
                } catch (failure: RejectedExecutionException) {
                    pending.remove(key)
                    error("Two images are already loading. Try again shortly.")
                }
            }
            entry.users++
            entry
        }
        try {
            while (true) {
                check()
                entry.check()
                try { return entry.result.get(50, TimeUnit.MILLISECONDS).also { check() } }
                catch (_: TimeoutException) { /* Poll caller cancellation while sharing the download. */ }
                catch (failure: ExecutionException) { throw failure.cause ?: failure }
            }
        } finally {
            synchronized(pending) {
                if (--entry.users == 0 && pending[key] === entry) {
                    pending.remove(key)
                    entry.cancelled.set(true)
                }
            }
        }
    }

    private fun fetch(initial: HttpUrl, key: String, entry: Pending): JsonObject {
        var url = initial
        repeat(4) {
            entry.check()
            val addresses = resolve(url.host)
            entry.check()
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "Image URLs must resolve only to public internet addresses" }
            val address = addresses.firstOrNull { it.address.size == 4 } ?: addresses.first()
            when (val result = download(url, address, entry::check)) {
                is Download.Redirect -> url = url.resolve(result.location)?.let { parseUrl(it.toString()) }
                    ?: error("Image redirect is not an allowed HTTPS URL")
                is Download.Image -> {
                    entry.check()
                    require(result.bytes.size <= MAX_BYTES && result.format in FORMATS) { "Image response exceeds the download policy" }
                    val preview = thumbnails.get(key, {
                        "data:image/${result.format};base64," + Base64.getEncoder().encodeToString(result.bytes)
                    }, { entry.cancelled.get() || System.nanoTime() >= entry.deadline })
                        ?: error("Image is invalid, unsupported, or exceeds the pixel limit")
                    entry.check()
                    return Json.obj("url" to preview, "hostname" to url.host)
                }
            }
        }
        error("Image has too many redirects")
    }

    fun cancel() = synchronized(pending) {
        pending.values.forEach { it.cancelled.set(true) }
        pending.clear()
    }

    override fun close() {
        synchronized(pending) { closed = true; cancel() }
        workers.shutdownNow()
        thumbnails.dispose()
    }

    companion object {
        internal const val MAX_BYTES = 8 * 1024 * 1024
        private val FORMATS = setOf("png", "jpeg", "webp", "gif", "avif")

        fun checkMode(mode: String, automatic: Boolean) {
            require(mode != "off") { "Remote image loading is disabled in Varro settings" }
            require(!automatic || mode == "on") { "Automatic remote image loading is disabled" }
        }

        internal fun parseUrl(value: String): HttpUrl? {
            if (value.length > 4096) return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            // OkHttp normalizes mapped IPv6 to IPv4. Reject it before that erases the address family.
            val literal = runCatching { java.net.URI(value.trim()).host }.getOrNull()?.takeIf { it.startsWith('[') }
            if (literal != null && InetAddress.getByName(literal).address.size != 16) return null
            if (url.host.contains(':') && InetAddress.getByName(url.host).address.size != 16) return null
            return url.newBuilder().fragment(null).build().takeIf { it.toString().length <= 4096 && it.host.length <= 253 }
        }

        internal fun isPublicAddress(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) {
                val (a, second, third) = b
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && second in 64..127) || (a == 169 && second == 254) ||
                    (a == 172 && second in 16..31) || (a == 192 && (second == 0 || second == 168 || (second == 88 && third == 99))) ||
                    (a == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) ||
                    (a == 203 && second == 0 && third == 113))
            }
            if (b.size != 16 || address.hostAddress.contains('%')) return false
            val first = (b[0] shl 8) or b[1]
            val second = (b[2] shl 8) or b[3]
            return first in 0x2000 until 0x4000 && first != 0x2002 && first != 0x3fff &&
                !(first == 0x2001 && (second < 0x200 || second == 0xdb8))
        }

        /** The checked address supplies the socket, while the URL still supplies TLS SNI and Host. */
        internal fun imageClient(address: InetAddress): OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { listOf(address) }
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .callTimeout(15, TimeUnit.SECONDS).build()

        internal fun imageRequest(url: HttpUrl): Request = Request.Builder().url(url)
                .header("Accept", "image/png,image/jpeg,image/webp,image/gif,image/avif")
                .header("Accept-Encoding", "identity").build()

        internal fun readResponse(response: Response, check: () -> Unit): Download = response.use {
            check()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                return@use Download.Redirect(response.header("Location") ?: error("Image redirect has no destination"))
            }
            require(response.code == 200) { "Image server returned HTTP ${response.code}" }
            val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val format = type?.removePrefix("image/")?.takeIf { type == "image/$it" && it in FORMATS }
                ?: error("Only raster images are supported")
            val body = response.body
            require(body.contentLength() <= MAX_BYTES && response.header("Content-Encoding").let { it == null || it == "identity" }) {
                "Image response exceeds the download policy"
            }
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_BYTES) { "Image is too large to download" }
                    bytes.write(buffer, 0, count)
                }
            }
            Download.Image(bytes.toByteArray(), format)
        }

        private fun downloadImage(url: HttpUrl, address: InetAddress, check: () -> Unit): Download {
            check()
            val client = imageClient(address)
            val call = client.newCall(imageRequest(url))
            val result = CompletableFuture<Download>()
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { result.completeExceptionally(e) }
                override fun onResponse(call: Call, response: Response) {
                    try { result.complete(readResponse(response, check)) }
                    catch (failure: Throwable) { result.completeExceptionally(failure) }
                }
            })
            try {
                while (true) {
                    check()
                    try { return result.get(50, TimeUnit.MILLISECONDS) }
                    catch (_: TimeoutException) { /* Cancelling closes an in-flight response body too. */ }
                    catch (failure: ExecutionException) { throw failure.cause ?: failure }
                }
            } finally {
                call.cancel()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
