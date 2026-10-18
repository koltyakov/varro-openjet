package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.host.quota.*
import varro.protocol.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Native provider polling with per-workspace caches and credential-aware invalidation. */
class ProviderQuotaBackend(
    private val credentials: QuotaCredentials,
    private val request: (String, String, JsonElement?, String?) -> JsonElement?,
    private val http: QuotaHttp = IdeQuotaHttp(),
    private val onUpdate: (JsonObject) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val apiVersion: () -> Int = { 1 },
    private val attachOnly: () -> Boolean = { false },
) : AutoCloseable {
    private data class Key(val provider: String, val model: String?, val directory: String?)
    private data class Entry(val identity: String, val status: JsonObject, val expires: Long)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val lastGood = ConcurrentHashMap<Key, Entry>()
    private val backoff = ConcurrentHashMap<Key, Long>()
    private val inFlight = ConcurrentHashMap<Key, CompletableFuture<JsonObject>>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val generation = AtomicLong()
    private val lifecycle = Any()
    @Volatile private var closed = false

    fun get(providerId: String, modelId: String?, directory: String?): JsonObject {
        val key = Key(providerId, modelId, directory)
        if (closed) return status(key, "error", "Provider quota backend is disposed")
        val now = clock()
        cache.entries.removeIf { it.value.expires <= now }
        lastGood.entries.removeIf { it.value.expires <= now }
        val fresh = CompletableFuture<JsonObject>()
        val future = inFlight.putIfAbsent(key, fresh) ?: fresh.also {
            val version = generation.get()
            try {
                executor.execute {
                    val result = try { load(key, version) }
                    catch (_: Exception) { status(key, "error", "Provider quota request failed; retry on the next poll") }
                    finally { inFlight.remove(key, it) }
                    // Retire the poll before waking callers. Their next get must
                    // recheck credentials and expiry instead of joining this result.
                    it.complete(result)
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                inFlight.remove(key, it)
                it.complete(status(key, "error", "Provider quota backend is disposed"))
            }
        }
        return try { future.get(60, TimeUnit.SECONDS).deepCopy() }
        catch (_: java.util.concurrent.TimeoutException) { status(key, "error", "Provider quota request timed out") }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); status(key, "error", "Provider quota request interrupted") }
        catch (_: Exception) { status(key, "error", "Provider quota request was cancelled") }
    }

    private fun load(key: Key, version: Long): JsonObject {
        var identity = ""
        var result = try {
            val attached = attachOnly()
            val v2 = apiVersion() == 2
            if (attached && (!v2 || key.provider != "openai")) return status(key, "unsupported",
                "Provider quota checks need server-side credentials and are not supported in attach-only mode. Check usage with your provider or on the OpenCode server host.")
            val metadata = request("GET", "/config/providers", null, key.directory)
            val providers = metadata.asObjectOrNull().elements("providers").ifEmpty { metadata.asArrayOrNull()?.toList().orEmpty() }
            val provider = providers.firstOrNull { it.asObjectOrNull().str("id") == key.provider }.asObjectOrNull()
                ?: throw QuotaFailure("Provider was not found in OpenCode configuration")
            val auth = if (attached) attachedOpenAI(request("GET", "/api/credential", null, key.directory))
                else if (!QuotaAdapters.requiresOpenCodeAuth(key.provider)) JsonObject()
                else if (v2) credentials.v2AuthStore(provider) else credentials.authStore()
            if (attached && auth.obj("openai").str("type") != "oauth") return status(key, "unsupported",
                "OpenAI subscription limits require an active Codex OAuth connection on the OpenCode server.")
            val token = if (attached) auth.obj("openai").str("access") else credentials.token(provider, auth)
            // Re-read identity before using a quota cache, so logging in with a new
            // account cannot show the previous account's remaining allowance.
            identity = if (attached) QuotaCredentials.fingerprint("attached-v2", Json.stringify(provider), Json.stringify(auth))
                else QuotaCredentials.fingerprint(Json.stringify(provider), Json.stringify(auth),
                Json.stringify(if (key.provider == "anthropic" && auth.obj("anthropic").str("type") != "oauth") credentials.read(credentials.claudePath) else null),
                Json.stringify(if (key.provider == "openai") credentials.codex(auth) else null),
                if (key.provider in setOf("google", "gemini")) credentials.gemini(key.provider, auth).orEmpty() else "",
                token.orEmpty(),
                if (key.provider == "github-copilot" && token == null) credentials.copilotFallback().orEmpty() else "")
            cache[key]?.takeIf { it.identity == identity && it.expires > clock() }?.let { return it.status }
            val writeAuth: ((String, JsonObject) -> Unit)? = if (v2) null else { id, value ->
                check(version == generation.get() && !closed) { "Provider quota poll was retired" }
                if (credentials.authStore().obj(id) != auth.obj(id)) throw QuotaFailure("Provider credentials changed during refresh; retrying with current credentials")
                request("PUT", "/auth/$id", value, key.directory)
            }
            val adapters = QuotaAdapters(http, credentials, writeAuth)
            adapters.poll(provider, auth, key.model, clock()) ?: run {
                var windows = directWindows(provider.obj("models").obj(key.model.orEmpty()), clock())
                    .ifEmpty { directWindows(provider, clock()) }
                if (windows.isEmpty()) {
                    val console = runCatching { request("GET", "/experimental/console", null, key.directory).asObjectOrNull() }.getOrNull()
                    val managed = console.arr("consoleManagedProviders")?.strings().orEmpty()
                    if (managed.isEmpty() || key.provider in managed) windows = directWindows(console, clock())
                }
                if (windows.isNotEmpty()) status(key, "available", "Read from OpenCode metadata", "opencode").apply { add("windows", Json.array(windows)) }
                else status(key, "unsupported", "This provider does not expose quota information")
            }
        } catch (failure: QuotaFailure) {
            status(key, if (failure.code in setOf(401, 403)) "unsupported" else "error", failure.message.orEmpty())
        } catch (_: javax.net.ssl.SSLException) {
            status(key, "error", "Provider TLS connection failed. Check the IDE's certificate settings.")
        } catch (_: java.net.SocketTimeoutException) {
            status(key, "error", "Provider connection timed out. Check the IDE's HTTP proxy settings.")
        } catch (_: Exception) {
            status(key, "error", "Could not poll provider limits. Check the OpenCode connection and IDE HTTP proxy settings.")
        }
        return synchronized(lifecycle) {
            if (closed || generation.get() != version) {
                return@synchronized status(key, "error", "Provider settings changed during the poll; retrying")
            }
            val ttl = when (result.str("status")) {
                "available" -> { backoff.remove(key); 30_000L }
                "unsupported" -> { backoff.remove(key); 60_000L }
                else -> if (result.str("note")?.contains("429") == true) {
                    backoff.compute(key) { _, previous -> ((previous ?: 30_000L) * 2).coerceAtMost(3_600_000L) }!!
                } else { backoff.remove(key); 15_000L }
            }
            if (result.str("status") == "error") {
                lastGood[key]?.takeIf {
                    it.identity == identity && it.expires > clock() && clock() - (it.status.long("checkedAt") ?: 0) < 900_000
                }?.let {
                    result = it.status.deepCopy().apply {
                        addProperty("note", "Last successful quota snapshot. ${result.str("note")}")
                    }
                }
            } else if (result.str("status") == "available") {
                lastGood[key] = Entry(identity, result, clock() + 900_000)
            }
            val expires = if (result.str("status") == "available") {
                minOf(clock() + ttl, (result.long("checkedAt") ?: clock()) + 900_000)
            } else clock() + ttl
            cache[key] = Entry(identity, result, expires)
            onUpdate(Json.obj("directory" to key.directory, "status" to result.deepCopy()))
            result
        }
    }

    /** Credentials stay host-only. No local database, files, inactive account or env fallback. */
    private fun attachedOpenAI(response: JsonElement?): JsonObject {
        val entries = response.asObjectOrNull().arr("data") ?: throw QuotaFailure("Invalid OpenCode V2 credential response")
        val selected = entries.mapNotNull { it.asObjectOrNull() }.firstOrNull {
            it.str("integrationID") == "openai" && it.bool("active") == true
        }.obj("value")
        if (selected.str("type") != "oauth" || selected.text("access") == null) return JsonObject()
        return Json.obj("openai" to selected!!.deepCopy().apply {
            val metadata = selected.obj("metadata")
            (metadata.text("accountID") ?: metadata.text("accountId"))?.let { addProperty("accountId", it) }
        })
    }

    private fun status(key: Key, state: String, note: String, source: String = "provider") = Json.obj(
        "providerID" to key.provider, "modelID" to key.model, "status" to state,
        "source" to source, "checkedAt" to clock(), "note" to note,
    )

    fun clearCache() = synchronized(lifecycle) {
        generation.incrementAndGet()
        inFlight.clear()
        cache.clear()
        lastGood.clear()
        backoff.clear()
    }

    override fun close() = synchronized(lifecycle) {
        closed = true
        inFlight.forEach { (key, future) -> future.complete(status(key, "error", "Provider quota backend is disposed")) }
        clearCache()
        executor.shutdownNow()
        Unit
    }
}
