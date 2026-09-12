package varro.server

import com.google.gson.JsonElement
import com.intellij.openapi.diagnostic.logger
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.str
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Health probe result. */
data class HealthInfo(val healthy: Boolean, val version: String? = null)

/** A REST response plus the pagination cursor OpenCode returns out of band. */
data class OpenCodeResponse(val data: JsonElement?, val nextCursor: String? = null)

class OpenCodeResponseTooLargeException(maxBytes: Long) :
    RuntimeException("OpenCode response exceeded the $maxBytes-byte safety limit")

/** Options mirroring upstream's `OpenCodeRequestOptions`. */
data class RequestOptions(
    val captureNextCursor: Boolean = false,
    val maxResponseBytes: Long = OpenCodeTransport.RESPONSE_MAX_BYTES,
    /** Skip directory scoping entirely (global endpoints). */
    val unscoped: Boolean = false,
    /** Override the directory this request is scoped to. */
    val directory: String? = null,
    val timeoutMs: Long? = null,
)

/**
 * REST and SSE transport for the OpenCode server.
 *
 * Port of `src/extension/open-code-transport.ts`. The behaviours that matter for
 * fidelity and are preserved here:
 *
 *  - Workspace scoping differs per route. Session *writes* are scoped to the
 *    current workspace; session *reads* are intentionally unscoped because the
 *    webview already filters them and backend scoping has repeatedly regressed
 *    reload and delete flows when directory strings differ in separators or
 *    casing.
 *  - Per-route timeouts: MCP/OAuth authentication gets minutes, async prompts
 *    get a little longer than a normal call, everything else 30s.
 *  - The event stream reconnects with jittered exponential backoff and reports
 *    `degraded` so the UI can show a reconnecting banner while REST still works.
 *  - `Last-Event-ID` is advanced *before* the payload is parsed, so one
 *    malformed poison event is not replayed on every reconnect.
 */
class OpenCodeTransport(
    private val getUrl: () -> String,
    private val getWorkspaceCwd: () -> String?,
    private val getStatus: () -> ServerStatus,
    private val isDisposing: () -> Boolean,
    private val updateEventStreamState: (EventStreamState) -> Unit,
    private val emitEvent: (JsonElement) -> Unit,
) {
    private val log = logger<OpenCodeTransport>()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        // HTTP/1.1 is not a preference, it is required. Java's HttpClient defaults
        // to HTTP/2, which it negotiates by sending `Connection: Upgrade` on a
        // 1.1 request. OpenCode's server mishandles that on some routes: the
        // request simply never completes. `GET /model/default` times out after
        // 10s under HTTP/2 and answers in 2ms under HTTP/1.1, and under
        // concurrency the same fault surfaces as `IOException: closed` on
        // unrelated requests.
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** SSE reads block, so the stream gets its own thread rather than the common pool. */
    private val streamExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "varro-opencode-events").apply { isDaemon = true }
    }

    private val requestWorkspaceDirectory = AtomicReference<String?>(getWorkspaceCwd())
    private val eventStreamDirectory = AtomicReference<String?>(null)
    private val eventStreamServerUrl = AtomicReference<String?>(null)
    private val lastEventId = AtomicReference("")
    private val eventStreamGeneration = AtomicInteger(0)
    private val eventReconnectDelayMs = AtomicLong(1_000)
    private val eventReconnectCount = AtomicInteger(0)
    private val activeStream = AtomicReference<StreamHandle?>(null)
    private val disposed = AtomicBoolean(false)

    /** Request ids currently in flight, so a restart can abort them. */
    private val inFlight = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val activeRequestCount = AtomicInteger(0)
    private val lastMutationAt = AtomicLong(System.currentTimeMillis())

    fun isQuiet(): Boolean = activeRequestCount.get() == 0 && pendingAttentionRequests.isEmpty() &&
        System.currentTimeMillis() - lastMutationAt.get() >= 60_000

    fun attentionCount(): Int = pendingAttentionRequests.values.toSet().size

    /** Attention requests (permissions/questions) awaiting a reply, mapped to their session. */
    private val pendingAttentionRequests = ConcurrentHashMap<String, String>()

    /** Directory each observed session belongs to, learned from the event stream. */
    private val observedSessionDirectories = ConcurrentHashMap<String, String>()

    var onSessionObserved: (com.google.gson.JsonObject) -> Unit = {}

    private class StreamHandle(val generation: Int) {
        @Volatile var cancelled: Boolean = false
        @Volatile var body: InputStream? = null

        fun cancel() {
            cancelled = true
            runCatching { body?.close() }
        }
    }

    // --- REST -----------------------------------------------------------------

    fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        options: RequestOptions = RequestOptions(),
    ): OpenCodeResponse {
        activeRequestCount.incrementAndGet()
        if (method.uppercase() !in setOf("GET", "HEAD")) lastMutationAt.set(System.currentTimeMillis())
        try { return performRequest(method, path, body, options) }
        finally { activeRequestCount.decrementAndGet() }
    }

    private fun performRequest(method: String, path: String, body: JsonElement?, options: RequestOptions): OpenCodeResponse {
        val directory = if (options.unscoped) {
            null
        } else {
            options.directory ?: workspaceDirectoryForRequest(method, path)
        }
        val scoped = OpenCodeRequestScope.scope(getUrl(), path, directory)

        val normalizedMethod = method.uppercase()
        val builder = HttpRequest.newBuilder(URI.create(scoped.url))
            .timeout(Duration.ofMillis(options.timeoutMs ?: requestTimeoutMs(normalizedMethod, path)))

        OpenCodeRequestScope.directoryHeaders(scoped.directory).forEach(builder::header)

        val publisher = if (body != null && normalizedMethod != "GET" && normalizedMethod != "HEAD") {
            builder.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(Json.stringify(body))
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        builder.method(normalizedMethod, publisher)

        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        inFlight.add(future)
        val response = try {
            future.join()
        } catch (failure: Exception) {
            val cause = failure.cause ?: failure
            log.warn("OpenCode request failed: $normalizedMethod ${diagnosticRoute(path)}: ${cause.message}")
            if (cause is HttpTimeoutException) {
                throw OpenCodeRequestException("OpenCode request timed out: $normalizedMethod $path", cause)
            }
            throw OpenCodeRequestException(cause.message ?: "OpenCode request failed", cause)
        } finally {
            inFlight.remove(future)
        }

        val text = response.body().use { readBounded(it, options.maxResponseBytes) }
        val data = Json.parseOrNull(text)

        if (response.statusCode() !in 200..299) {
            throw OpenCodeRequestException(
                "${response.statusCode()} ${errorMessage(data, response.statusCode().toString())}",
            )
        }

        // OpenCode serves its own web UI, so an unknown API path returns the HTML
        // shell with a 200 rather than a 404. Treating that as an error is what
        // lets callers distinguish "this server has no such endpoint" from "the
        // endpoint answered null" — upstream's optional endpoints are written
        // against exactly that distinction.
        if (data == null && text.isNotBlank()) {
            throw OpenCodeRequestException(
                "OpenCode returned a non-JSON response for $normalizedMethod ${diagnosticRoute(path)}; " +
                    "this build likely does not implement that endpoint",
            )
        }

        val cursor = if (options.captureNextCursor) {
            response.headers().firstValue("x-next-cursor").orElse(null)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val pathname = path.substringBefore('?')
        if (pathname == "/session" || SESSION_BY_ID_ROUTE.matches(pathname) ||
            (normalizedMethod == "POST" && pathname.matches(Regex("/session/[^/]+/fork")))) {
            val sessions = if (data?.isJsonArray == true) data.asJsonArray.toList() else listOfNotNull(data)
            sessions.forEach { value -> value.asObjectOrNull()?.let { session ->
                val id = session.str("id")
                if (id != null) {
                    session.str("directory")?.let { observedSessionDirectories[id] = it }
                    onSessionObserved(session)
                }
            } }
        }
        return OpenCodeResponse(data, cursor)
    }

    /**
     * Per-route timeouts. MCP and provider OAuth callbacks wait on a human in a
     * browser, so they get minutes rather than the default 30 seconds.
     */
    private fun requestTimeoutMs(method: String, path: String): Long {
        val pathname = path.substringBefore('?')
        if (method == "POST" &&
            (MCP_AUTH_ROUTE.matches(pathname) || PROVIDER_OAUTH_ROUTE.matches(pathname))
        ) {
            return MCP_AUTH_TIMEOUT_MS
        }
        if (method == "POST" && ASYNC_SESSION_ROUTE.matches(pathname)) return ASYNC_REQUEST_TIMEOUT_MS
        return REQUEST_TIMEOUT_MS
    }

    /**
     * Decides which workspace a request is scoped to.
     *
     * Scoping stays on writes that create or continue work in the current
     * workspace. Session reads are intentionally unscoped: the webview already
     * filters them by workspace, and re-adding backend scoping has repeatedly
     * regressed reload and delete flows when directory strings differ in
     * separators, casing, or other formatting.
     */
    private fun workspaceDirectoryForRequest(method: String, path: String): String? {
        val normalizedMethod = method.uppercase()
        val pathname = path.substringBefore('?')
        val current = requestWorkspaceDirectory.get()

        if (normalizedMethod == "POST" && pathname == "/session") return current
        if (normalizedMethod == "POST" && PROMPT_ASYNC_ROUTE.matches(pathname)) return current
        if (pathname == "/session") return current
        if (normalizedMethod == "GET" &&
            (SESSION_BY_ID_ROUTE.matches(pathname) || SESSION_MESSAGES_ROUTE.matches(pathname))
        ) {
            return current
        }
        if (pathname == "/session/status" || pathname.startsWith("/session/")) return null
        return current
    }

    fun readHealthInfo(): HealthInfo {
        val request = HttpRequest.newBuilder(URI.create("${getUrl()}$HEALTH_PATH"))
            .timeout(Duration.ofMillis(HEALTH_TIMEOUT_MS))
            .GET()
            .build()
        return runCatching {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching HealthInfo(false)
            val record = Json.parseOrNull(response.body()).asObjectOrNull()
            val healthy = record.bool("healthy") ?: return@runCatching HealthInfo(false)
            HealthInfo(healthy, record.str("version"))
        }.getOrElse { HealthInfo(false) }
    }

    fun checkHealth(): Boolean = readHealthInfo().healthy

    // --- Event stream ---------------------------------------------------------

    /**
     * Opens the global SSE stream. The stream is global rather than per-workspace
     * because it survives per-workspace instance disposal; each envelope carries
     * its own directory so events can still be routed to the owning workspace.
     */
    fun startEventStream(
        directory: String? = requestWorkspaceDirectory.get(),
        promoteDirectoryImmediately: Boolean = true,
    ) {
        if (disposed.get()) return

        val serverUrl = getUrl()
        if (eventStreamServerUrl.getAndSet(serverUrl) != serverUrl) lastEventId.set("")
        resetEventStream()
        eventStreamDirectory.set(directory)
        if (promoteDirectoryImmediately) requestWorkspaceDirectory.set(directory)

        val handle = StreamHandle(eventStreamGeneration.incrementAndGet())
        activeStream.set(handle)
        streamExecutor.execute { runEventStream(handle, serverUrl) }
    }

    private fun runEventStream(handle: StreamHandle, serverUrl: String) {
        var shouldReconnect = false
        var continuityEstablished = false
        val connectedAt: Long

        try {
            val scoped = OpenCodeRequestScope.scope(serverUrl, EVENT_STREAM_PATH, null)
            val builder = HttpRequest.newBuilder(URI.create(scoped.url))
                .header("Accept", "text/event-stream")
                // No read timeout: an idle SSE connection is normal. Stalls are
                // caught by the heartbeat check in the read loop below.
                .timeout(Duration.ofMillis(EVENT_CONNECT_TIMEOUT_MS))
                .GET()
            OpenCodeRequestScope.directoryHeaders(scoped.directory).forEach(builder::header)
            lastEventId.get().takeIf { it.isNotEmpty() }?.let { builder.header("Last-Event-ID", it) }

            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            if (!isCurrent(handle)) {
                runCatching { response.body().close() }
                return
            }
            if (response.statusCode() !in 200..299) {
                throw OpenCodeRequestException("Failed to open event stream: ${response.statusCode()}")
            }

            handle.body = response.body()
            continuityEstablished = true
            connectedAt = System.currentTimeMillis()
            updateEventStreamState(EventStreamState.HEALTHY)

            var backoffReset = false
            val reader = response.body().bufferedReader()
            val chunk = StringBuilder()
            while (true) {
                val line = reader.readLine()
                if (!isCurrent(handle)) return
                if (line == null) {
                    // SSE dispatches only records terminated by a blank line.
                    // Dropping an unterminated tail is safer than acknowledging a
                    // partially received id.
                    log.info("OpenCode event stream closed; reconnecting")
                    shouldReconnect = true
                    break
                }

                if (!backoffReset && System.currentTimeMillis() - connectedAt >= EVENT_STABILITY_WINDOW_MS) {
                    eventReconnectDelayMs.set(1_000)
                    eventReconnectCount.set(0)
                    backoffReset = true
                }

                if (line.isEmpty()) {
                    if (chunk.isNotEmpty()) {
                        processSseChunk(chunk.toString(), handle)
                        chunk.setLength(0)
                    }
                    continue
                }

                if (chunk.length > EVENT_MAX_BUFFER_CHARS) {
                    log.warn("OpenCode event stream record exceeded safety limit; reconnecting")
                    shouldReconnect = true
                    break
                }
                chunk.append(line).append('\n')
            }
        } catch (failure: Exception) {
            if (handle.cancelled) return
            log.warn("OpenCode event stream error: ${failure.message}")
            shouldReconnect = true
        } finally {
            runCatching { handle.body?.close() }
        }

        if (!shouldReconnect || !isCurrent(handle)) return
        if (getStatus() !is ServerStatus.Running || isDisposing()) return

        // A stream that connected and then dropped may have missed replies, so
        // the attention cache is cleared rather than left to go stale.
        if (continuityEstablished) pendingAttentionRequests.clear()
        updateEventStreamState(EventStreamState.DEGRADED)

        val attempt = eventReconnectCount.incrementAndGet()
        if (attempt == EVENT_RECONNECT_WARNING_THRESHOLD) {
            log.warn(
                "OpenCode event stream reconnect attempts reached $EVENT_RECONNECT_WARNING_THRESHOLD; " +
                    "continuing background retries while keeping REST requests available",
            )
        }

        val delay = nextReconnectDelay()
        streamExecutor.execute {
            Thread.sleep(delay)
            if (isDisposing() || getStatus() !is ServerStatus.Running) return@execute
            if (!isCurrent(handle)) return@execute
            startEventStream(eventStreamDirectory.get(), promoteDirectoryImmediately = false)
        }
    }

    /**
     * Parses one SSE record. Only `id:` and `data:` matter; OpenCode does not use
     * named events on this stream.
     */
    private fun processSseChunk(chunk: String, handle: StreamHandle) {
        var data = StringBuilder()
        var eventId: String? = null

        for (line in chunk.split('\n')) {
            when {
                line == "id" -> eventId = ""
                line.startsWith("id:") -> {
                    val value = line.substring(3).removePrefix(" ")
                    if (!value.contains(' ')) {
                        eventId = if (value.length <= MAX_SERVER_EVENT_ID_LENGTH) value else ""
                    }
                }
                line.startsWith("data:") -> {
                    val value = line.substring(5).trimStart()
                    if (data.isEmpty()) data = StringBuilder(value) else data.append('\n').append(value)
                }
            }
        }

        if (data.isEmpty()) return
        if (!isCurrent(handle)) return

        // Advance before parsing so a malformed poison event is not replayed on
        // every reconnect.
        eventId?.let { lastEventId.set(it) }

        if (data.length > EVENT_MAX_PAYLOAD_CHARS) {
            log.warn("Ignoring oversized event stream payload (${data.length} chars)")
            return
        }

        val parsed = runCatching { Json.parse(data.toString()) }.getOrElse {
            log.warn("Ignoring malformed event stream payload: ${it.message}")
            return
        }

        runCatching { ServerEvents.observe(parsed, pendingAttentionRequests, observedSessionDirectories) }
            .onFailure { log.warn("Event observation threw: ${it.message}") }
        runCatching { emitEvent(parsed) }
            .onFailure { log.warn("Event listener threw: ${it.message}") }
    }

    fun stopEventStream() {
        resetEventStream()
        eventStreamServerUrl.set(null)
        lastEventId.set("")
    }

    private fun resetEventStream() {
        eventStreamGeneration.incrementAndGet()
        activeStream.getAndSet(null)?.cancel()
    }

    private fun isCurrent(handle: StreamHandle): Boolean =
        !handle.cancelled && activeStream.get() === handle

    /** Exponential backoff with +/-20% jitter, so reconnects do not synchronize. */
    private fun nextReconnectDelay(): Long {
        val delay = eventReconnectDelayMs.get()
        eventReconnectDelayMs.set(minOf(delay * 2, MAX_EVENT_RECONNECT_DELAY_MS))
        val min = (delay * 0.8).toLong()
        val max = minOf((delay * 1.2).toLong(), MAX_EVENT_RECONNECT_DELAY_MS)
        return if (max <= min) min else ThreadLocalRandom.current().nextLong(min, max + 1)
    }

    // --- Scope and lifecycle --------------------------------------------------

    /** Repoints both REST scoping and the event stream at another workspace root. */
    fun rescope(directory: String?): Boolean {
        if (requestWorkspaceDirectory.get() == directory && eventStreamDirectory.get() == directory) {
            return false
        }
        requestWorkspaceDirectory.set(directory)
        eventStreamDirectory.set(directory)
        return true
    }

    fun workspaceDirectory(): String? = requestWorkspaceDirectory.get()

    fun clearPendingAttentionRequests() = pendingAttentionRequests.clear()

    fun hasPendingAttentionRequests(): Boolean = pendingAttentionRequests.isNotEmpty()

    fun pendingAttentionSessionIds(): List<String> = pendingAttentionRequests.values.distinct()

    fun pendingAttentionDirectory(requestId: String): String? =
        pendingAttentionRequests[requestId]?.let { observedSessionDirectories[it] }

    fun observedSessionDirectories(): Map<String, String> = observedSessionDirectories.toMap()

    fun abortRequests() {
        inFlight.forEach { it.cancel(true) }
        inFlight.clear()
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        stopEventStream()
        abortRequests()
        streamExecutor.shutdownNow()
    }

    private fun readBounded(stream: InputStream, maxBytes: Long): String {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw OpenCodeResponseTooLargeException(maxBytes)
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun errorMessage(data: JsonElement?, fallback: String): String {
        val record = data.asObjectOrNull()
        record.str("message")?.let { return it }
        record.str("detail")?.let { return it }
        record.str("error")?.let { return it }
        val nested = record?.get("data").asObjectOrNull()
        nested.str("message")?.let { return it }
        nested.str("detail")?.let { return it }
        return fallback
    }

    /** Strips query strings from log output so paths never carry workspace data. */
    private fun diagnosticRoute(path: String): String = path.substringBefore('?')

    companion object {
        const val HEALTH_PATH = "/global/health"
        const val EVENT_STREAM_PATH = "/global/event"

        const val RESPONSE_MAX_BYTES = 16L * 1024 * 1024
        const val MAX_SERVER_EVENT_ID_LENGTH = 512

        private const val HEALTH_TIMEOUT_MS = 2_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val ASYNC_REQUEST_TIMEOUT_MS = 35_000L
        private const val MCP_AUTH_TIMEOUT_MS = 5 * 60_000L + 10_000L
        private const val EVENT_CONNECT_TIMEOUT_MS = 10_000L
        private const val EVENT_STABILITY_WINDOW_MS = 15_000L
        private const val EVENT_MAX_BUFFER_CHARS = 8_000_000
        private const val EVENT_MAX_PAYLOAD_CHARS = 8_000_000
        private const val EVENT_RECONNECT_WARNING_THRESHOLD = 10
        private const val MAX_EVENT_RECONNECT_DELAY_MS = 30_000L

        private val MCP_AUTH_ROUTE = Regex("""^/mcp/[^/]+/auth/authenticate$""")
        private val PROVIDER_OAUTH_ROUTE = Regex("""^/provider/[^/]+/oauth/callback$""")
        private val ASYNC_SESSION_ROUTE = Regex("""^/session/[^/]+/(?:prompt_async|summarize)$""")
        private val PROMPT_ASYNC_ROUTE = Regex("""^/session/[^/]+/prompt_async$""")
        private val SESSION_BY_ID_ROUTE = Regex("""^/session/[^/]+$""")
        private val SESSION_MESSAGES_ROUTE = Regex("""^/session/[^/]+/message$""")
    }
}

class OpenCodeRequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
