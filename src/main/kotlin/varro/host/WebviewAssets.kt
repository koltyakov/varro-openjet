package varro.host

import com.intellij.openapi.diagnostic.logger
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
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
    const val EMPTY_STATE_LOGO_URL: String = "$ORIGIN/varro-logo.svg"

    /** Classpath root the Vite build emits into. */
    private const val RESOURCE_ROOT = "/webview"

    /** Cache key from `webview.version`, appended to asset URLs by the page shell. */
    val assetVersion: String by lazy {
        readResource("$RESOURCE_ROOT/webview.version")?.decodeToString()?.trim().orEmpty()
    }
    val staticImports: List<String> by lazy {
        runCatching {
            val raw = readResource("$RESOURCE_ROOT/webview.imports.json")?.decodeToString() ?: "[]"
            validatedImports(varro.protocol.Json.parse(raw))
        }.getOrDefault(emptyList())
    }

    internal fun validatedImports(value: com.google.gson.JsonElement): List<String> =
        value.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { Regex("chunks/[A-Za-z0-9_-]+-[A-Za-z0-9_-]+\\.js").matches(it) }
        }?.distinct()?.take(128).orEmpty()

    /** True when the bundle is present, i.e. the npm build actually ran. */
    fun isBundlePresent(): Boolean =
        WebviewAssets::class.java.getResource("$RESOURCE_ROOT/webview.mjs") != null

    fun isOwnedUrl(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "http" && uri.host == "varro.localhost" && uri.port == -1 && uri.rawUserInfo == null
    }.getOrDefault(false)

    /**
     * Resolves a request URL to a response.
     *
     * @param generateIndex produces the page shell; called lazily so the host can
     *   inline a fresh boot snapshot on every (re)load.
     */
    fun resolve(url: String, generateIndex: () -> String): Resolved? {
        if (!isOwnedUrl(url)) return null
        val path = runCatching { URI(url).path }.getOrNull()?.ifEmpty { "/" } ?: return null

        if (path == "/" || path == "/index.html") {
            return Resolved(generateIndex().toByteArray(), "text/html")
        }

        // Reject traversal before touching the classpath: the path comes from page
        // content, and `getResourceAsStream` would happily resolve `..` out of the
        // bundle and into the rest of the plugin jar.
        val normalized = normalize(path) ?: return null
        val resource = if (normalized == "varro-logo.svg") {
            "/META-INF/pluginIcon.svg"
        } else {
            "$RESOURCE_ROOT/$normalized"
        }
        val bytes = readResource(resource) ?: return null
        return Resolved(bytes, mimeType(normalized), assetCacheControl(url))
    }

    data class Resolved(val bytes: ByteArray, val mimeType: String, val cacheControl: String = "no-store")

    internal fun assetCacheControl(url: String): String {
        val uri = URI(url)
        val versioned = uri.path in setOf("/webview.mjs", "/webview.css") &&
            uri.rawQuery?.matches(Regex("v=[a-f0-9]{16}")) == true
        val hashed = uri.path.matches(Regex("/(chunks|assets)/[A-Za-z0-9_.-]+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+"))
        return if (versioned || hashed) "public, max-age=31536000, immutable" else "no-cache"
    }

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
     * Use the adapter's fallback to `processRequest`/`readResponse`. Its current
     * `open`/`read` defaults tell CEF to call this older pair. Implementing the
     * newer methods here would reference callback classes absent before 2026.2.
     */
    class Handler(private val resolved: Resolved?) : CefResourceHandlerAdapter() {
        private var stream: InputStream? = null

        @Deprecated("CEF compatibility fallback, also used by the current adapter.")
        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            // Missing resources still get a 404 from getResponseHeaders.
            stream = resolved?.let { ByteArrayInputStream(it.bytes) }
            callback?.Continue()
            return true
        }

        @Deprecated("CEF compatibility fallback, also used by the current adapter.")
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
                setHeaderByName("Cache-Control", payload.cacheControl, true)
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
