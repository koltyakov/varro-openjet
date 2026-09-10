package varro.host

import com.intellij.openapi.diagnostic.logger
import org.cef.callback.CefCallback
import org.cef.callback.CefResourceReadCallback
import org.cef.callback.CefResourceSkipCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.LongRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI

/**
 * Serves the webview bundle to JCEF.
 *
 * The bundle is a real ES-module graph (a ~900 KB entry plus lazily imported
 * chunks for mermaid, katex and syntax highlighting), so inlining it into
 * `loadHTML` is not an option: dynamic `import()` needs a resolvable base URL,
 * and a single 5 MB data document would also defeat JCEF's cache entirely.
 *
 * Instead the browser loads a synthetic origin and a request handler answers
 * from plugin resources. A stable synthetic origin additionally gives the page a
 * durable `localStorage` partition, which is what upstream's `BrowserPersistence`
 * stores composer drafts and UI preferences in.
 */
object WebviewAssets {

    private val log = logger<WebviewAssets>()

    /** Synthetic origin. `.localhost` is guaranteed not to resolve on a real network. */
    const val ORIGIN: String = "http://varro.localhost"
    const val INDEX_URL: String = "$ORIGIN/index.html"

    /** Classpath root the Vite build emits into. */
    private const val RESOURCE_ROOT = "/webview"

    /** Cache key from `webview.version`, appended to asset URLs by the page shell. */
    val assetVersion: String by lazy {
        readResource("$RESOURCE_ROOT/webview.version")?.decodeToString()?.trim().orEmpty()
    }

    /** True when the bundle is present, i.e. the npm build actually ran. */
    fun isBundlePresent(): Boolean =
        WebviewAssets::class.java.getResource("$RESOURCE_ROOT/webview.mjs") != null

    fun isOwnedUrl(url: String?): Boolean = url != null && url.startsWith(ORIGIN)

    /**
     * Resolves a request URL to a response.
     *
     * @param generateIndex produces the page shell; called lazily so the host can
     *   inline a fresh boot snapshot on every (re)load.
     */
    fun resolve(url: String, generateIndex: () -> String): Resolved? {
        val path = runCatching { URI(url).path }.getOrNull()?.ifEmpty { "/" } ?: return null

        if (path == "/" || path == "/index.html") {
            return Resolved(generateIndex().toByteArray(), "text/html")
        }

        // Reject traversal before touching the classpath: the path comes from page
        // content, and `getResourceAsStream` would happily resolve `..` out of the
        // bundle and into the rest of the plugin jar.
        val normalized = normalize(path) ?: return null
        val bytes = readResource("$RESOURCE_ROOT/$normalized") ?: return null
        return Resolved(bytes, mimeType(normalized))
    }

    data class Resolved(val bytes: ByteArray, val mimeType: String)

    /**
     * Collapses `.` and rejects any segment that escapes the bundle root or looks
     * like a Windows drive or absolute path.
     */
    private fun normalize(path: String): String? {
        val segments = path.trimStart('/').split('/')
        val resolved = ArrayList<String>(segments.size)
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> continue
                segment == ".." -> return null
                segment.contains('\\') || segment.contains(':') -> return null
                else -> resolved.add(segment)
            }
        }
        return resolved.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun readResource(name: String): ByteArray? =
        WebviewAssets::class.java.getResourceAsStream(name)?.use { it.readBytes() }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "")) {
        "mjs", "js" -> "text/javascript"
        "css" -> "text/css"
        "html" -> "text/html"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    /**
     * A [CefResourceHandler] over a fully buffered response.
     *
     * The bytes are resolved once up front rather than streamed: the largest
     * asset is under a megabyte, CEF may ask for the body in many small slices
     * from an IO thread, and buffering keeps the handler free of partial-read
     * state that a cancelled navigation could strand.
     *
     * CEF has two generations of this interface. `open`/`read`/`skip` are the
     * current ones; `processRequest`/`readResponse` are the deprecated pair that
     * older builds still call. Both are implemented and share the same cursor, so
     * whichever the runtime picks behaves identically.
     */
    class Handler(private val resolved: Resolved?) : CefResourceHandler {
        private var stream: InputStream? = null

        // --- Current API ------------------------------------------------------

        override fun open(request: CefRequest?, handleRequest: BoolRef?, callback: CefCallback?): Boolean {
            if (resolved == null) {
                // Handling it with a 404 beats letting CEF fall through to the
                // network, which would try to resolve the synthetic host for real.
                handleRequest?.set(true)
                return true
            }
            stream = ByteArrayInputStream(resolved.bytes)
            // `true` means the response is ready now; no async continuation needed.
            handleRequest?.set(true)
            return true
        }

        override fun read(
            dataOut: ByteArray?,
            bytesToRead: Int,
            bytesRead: IntRef?,
            callback: CefResourceReadCallback?,
        ): Boolean = readInto(dataOut, bytesToRead, bytesRead)

        override fun skip(
            bytesToSkip: Long,
            bytesSkipped: LongRef?,
            callback: CefResourceSkipCallback?,
        ): Boolean {
            val source = stream ?: return false
            val skipped = runCatching { source.skip(bytesToSkip) }.getOrDefault(0L)
            bytesSkipped?.set(skipped)
            return skipped > 0
        }

        // --- Deprecated API, kept for older CEF builds ------------------------

        @Deprecated("Superseded by open(); kept for CEF runtimes that still call it.")
        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            if (resolved == null) {
                callback?.cancel()
                return false
            }
            stream = ByteArrayInputStream(resolved.bytes)
            callback?.Continue()
            return true
        }

        @Deprecated("Superseded by read(); kept for CEF runtimes that still call it.")
        override fun readResponse(
            dataOut: ByteArray?,
            bytesToRead: Int,
            bytesRead: IntRef?,
            callback: CefCallback?,
        ): Boolean = readInto(dataOut, bytesToRead, bytesRead)

        // --- Shared -----------------------------------------------------------

        override fun getResponseHeaders(
            response: CefResponse?,
            responseLength: IntRef?,
            redirectUrl: StringRef?,
        ) {
            val payload = resolved ?: run {
                response?.status = 404
                responseLength?.set(0)
                return
            }
            response?.apply {
                mimeType = payload.mimeType
                status = 200
                // The page shell already carries a `?v=` cache key, so assets can
                // be cached hard; the shell itself must never be.
                setHeaderByName("Cache-Control", "no-cache", true)
                // Module scripts and fonts are fetched as CORS requests from the
                // synthetic origin, which CEF treats as cross-origin unless the
                // response opts in.
                setHeaderByName("Access-Control-Allow-Origin", "*", true)
            }
            responseLength?.set(payload.bytes.size)
        }

        /**
         * Copies the next slice. Returning `false` with `bytesRead` at 0 is how
         * both APIs signal end of stream; a non-zero count with `true` means more
         * may follow.
         */
        private fun readInto(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?): Boolean {
            val source = stream
            if (source == null || dataOut == null) {
                bytesRead?.set(0)
                return false
            }
            val read = runCatching { source.read(dataOut, 0, bytesToRead) }.getOrElse { failure ->
                log.warn("Failed to read webview asset", failure)
                -1
            }
            if (read <= 0) {
                bytesRead?.set(0)
                return false
            }
            bytesRead?.set(read)
            return true
        }

        override fun cancel() {
            runCatching { stream?.close() }
            stream = null
        }
    }
}
