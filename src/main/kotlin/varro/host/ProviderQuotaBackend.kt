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
    private val requestUsageLimits: (String?) -> JsonElement? = { directory ->
        request("GET", "/api/experimental/usage/limits", null, directory)
    },
    private val lookupTimeoutMs: Long = 12_000,
) : AutoCloseable {
    private data class Key(val provider: String, val model: String?, val directory: String?)
    private data class Entry(val identity: String, val status: JsonObject, val expires: Long)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val lastGood = ConcurrentHashMap<Key, Entry>()
    private val backoff = ConcurrentHashMap<Key, Long>()
    private data class AuthFailure(val identity: String, val note: String, val retryAt: Long, val backoff: Long)
    private val authFailures = ConcurrentHashMap<String, AuthFailure>()
    private val inFlight = ConcurrentHashMap<Key, CompletableFuture<JsonObject>>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val generation = AtomicLong()
    private val lifecycle = Any()
    private class Poll {
        @Volatile var identity = ""
        @Volatile var retired = false
        var authFailure: AuthFailure? = null
    }
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
                    val poll = Poll()
                    val work = executor.submit<JsonObject> { load(key, version, poll) }
                    val result = try { work.get(lookupTimeoutMs, TimeUnit.MILLISECONDS) }
                    catch (_: Exception) {
                        synchronized(lifecycle) {
                            poll.retired = true
                            work.cancel(true)
                            finish(key, version, poll, status(key, "error", "Provider quota lookup failed or timed out; retry on the next poll"))
                        }
                    }
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

    private fun load(key: Key, version: Long, poll: Poll): JsonObject {
        var identity = ""
        var rejectedCredentials = false
        var result = try {
            val attached = attachOnly()
            val v2 = apiVersion() == 2
            if (attached && !v2) return status(key, "unsupported",
                "Provider quota checks need server-side credentials and are not supported in attach-only mode. Check usage with your provider or on the OpenCode server host.")
            val metadata = request("GET", "/config/providers", null, key.directory)
            val providers = metadata.asObjectOrNull().elements("providers").ifEmpty { metadata.asArrayOrNull()?.toList().orEmpty() }
            val provider = providers.firstOrNull { it.asObjectOrNull().str("id") == key.provider }.asObjectOrNull()
            val native = if (v2 && (key.provider in setOf("opencode", "opencode-go") || provider.str("integrationID") == "opencode")) {
                try {
                    val response = requestUsageLimits(key.directory).asObjectOrNull()
                    val data = response?.get("data")
                    identity = "server-console:${data.asObjectOrNull().obj("workspace").str("id").orEmpty()}"
                    if (data?.isJsonNull == true) status(key, "unsupported", "This OpenCode location is not signed in to a Console workspace.", "opencode")
                    else OpenCodeUsageLimits.project(data, key.provider, key.model, clock())
                } catch (failure: Exception) {
                    // A location 404 must not switch credential owners. Only the generic route 404 can.
                    if (failure is varro.server.OpenCodeRequestException && failure.message.equals("404 Not found", true)) null
                    else {
                        identity = lastGood[key]?.identity?.takeIf { it.startsWith("server-console:") }.orEmpty()
                        status(key, "error", "OpenCode Console limits failed: ${failure.message}", "opencode")
                    }
                }
            } else null
            native ?: run {
                if (attached && key.provider != "openai") return status(key, "unsupported",
                    "Provider quota checks are not supported in attach-only mode without a server-owned limits endpoint.")
                if (provider == null) throw QuotaFailure("Provider was not found in OpenCode configuration")
                val auth = if (attached) attachedOpenAI(request("GET", "/api/credential", null, key.directory))
                    else if (!QuotaAdapters.requiresOpenCodeAuth(key.provider)) JsonObject()
                    else if (v2) credentials.v2AuthStore(provider) else credentials.authStore()
                if (attached && auth.obj("openai").str("type") != "oauth") return status(key, "unsupported",
                    "OpenAI subscription limits require an active Codex OAuth connection on the OpenCode server.")
                val token = if (attached) auth.obj("openai").str("access") else credentials.token(provider, auth)
                val claudeAgentLogin = if (key.provider == "claude-agent") credentials.claudeAgentLogin() else null
                // The plan travels with the cached status, so a subscription change invalidates it.
                val claudeAgentToken = claudeAgentLogin?.let { login ->
                    listOf("accessToken", "subscriptionType", "rateLimitTier").joinToString("\u0000") { login.str(it).orEmpty() }
                }
                // Re-read identity before using a quota cache, so logging in with a new
                // account cannot show the previous account's remaining allowance.
                identity = if (attached) QuotaCredentials.fingerprint("attached-v2", Json.stringify(provider), Json.stringify(auth))
                    else QuotaCredentials.fingerprint(Json.stringify(provider), Json.stringify(auth),
                    Json.stringify(if (key.provider == "anthropic" && auth.obj("anthropic").str("type") != "oauth") credentials.read(credentials.claudePath) else null),
                    Json.stringify(if (key.provider == "openai") credentials.codex(auth) else null),
                    if (key.provider in setOf("google", "gemini")) credentials.gemini(key.provider, auth).orEmpty() else "",
                    token.orEmpty(), claudeAgentToken.orEmpty(),
                    if (key.provider == "github-copilot" && token == null) credentials.copilotFallback().orEmpty() else "")
                poll.identity = identity
                poll.authFailure = authFailures[key.provider]
                authFailures[key.provider]?.let { failure ->
                    if (failure.identity != identity) authFailures.remove(key.provider, failure)
                    else if (failure.retryAt > clock()) return status(key, "unsupported", failure.note).apply {
                        if (isZai(key)) { addProperty("failureKind", "authentication"); addProperty("retryAt", failure.retryAt) }
                    }
                }
                cache[key]?.takeIf { it.identity == identity && it.expires > clock() }?.let { return it.status }
                val writeAuth: ((String, JsonObject) -> Unit)? = if (v2) null else { id, value ->
                    check(version == generation.get() && !closed && !poll.retired) { "Provider quota poll was retired" }
                    if (credentials.authStore().obj(id) != auth.obj(id)) throw QuotaFailure("Provider credentials changed during refresh; retrying with current credentials")
                    request("PUT", "/auth/$id", value, key.directory)
                }
                val adapters = QuotaAdapters(http, credentials, writeAuth)
                adapters.poll(provider, auth, key.model, clock(), claudeAgentLogin) ?: run {
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
            }
        } catch (failure: QuotaFailure) {
            rejectedCredentials = failure.code in setOf(401, 403)
            status(key, if (failure.code in setOf(401, 403)) "unsupported" else "error", failure.message.orEmpty())
        } catch (_: javax.net.ssl.SSLException) {
            status(key, "error", "Provider TLS connection failed. Check the IDE's certificate settings.")
        } catch (_: java.net.SocketTimeoutException) {
            status(key, "error", "Provider connection timed out. Check the IDE's HTTP proxy settings.")
        } catch (_: Exception) {
            status(key, "error", "Could not poll provider limits. Check the OpenCode connection and IDE HTTP proxy settings.")
        }
        result.obj("codexLoginRequired")?.let { warning ->
            // Keep stable credential identities host-only, including in cached and pushed status.
            warning.text("connectionID")?.let { warning.addProperty("connectionID", QuotaCredentials.fingerprint(it)) }
        }
        poll.identity = identity
        return synchronized(lifecycle) {
            if (poll.retired) return@synchronized result
            finish(key, version, poll, result, rejectedCredentials)
        }
    }

    private fun finish(key: Key, version: Long, poll: Poll, initial: JsonObject, rejectedCredentials: Boolean = false): JsonObject {
        var result = initial
        val identity = poll.identity
        if (closed || generation.get() != version) return status(key, "error", "Provider settings changed during the poll; retrying")
        val currentAuth = authFailures[key.provider]
        if (isZai(key) && result.str("status") == "available" && currentAuth != null &&
            currentAuth !== poll.authFailure && currentAuth.identity == identity) return status(key, "unsupported", currentAuth.note).apply {
            addProperty("failureKind", "authentication"); addProperty("retryAt", currentAuth.retryAt)
        }
        if (rejectedCredentials && identity.isNotEmpty()) {
            val previous = authFailures[key.provider]
            val delay = if (previous?.identity == identity) minOf(previous.backoff * 2, 3_600_000L) else 300_000L
            authFailures[key.provider] = AuthFailure(identity, result.str("note").orEmpty(), clock() + delay, delay)
            if (isZai(key)) {
                cache.keys.filter { it.provider == key.provider }.forEach(cache::remove)
                lastGood.keys.filter { it.provider == key.provider }.forEach(lastGood::remove)
                result.addProperty("failureKind", "authentication")
            }
        } else if (result.str("status") == "available") authFailures.remove(key.provider)
        val ttl = when (result.str("status")) {
            "available" -> { backoff.remove(key); 30_000L }
            "unsupported" -> { backoff.remove(key); 60_000L }
            else -> if (result.str("note")?.contains("429") == true) {
                backoff.compute(key) { _, previous -> ((previous ?: 30_000L) * 2).coerceAtMost(3_600_000L) }!!
            } else { backoff.remove(key); 15_000L }
        }
        val previous = lastGood[key]?.takeIf { it.identity == identity && identity.isNotEmpty() }
        val resets = previous?.status.obj("usageLimitResets")?.takeIf {
            clock() - (it.long("checkedAt") ?: previous?.status.long("checkedAt") ?: 0) < 900_000
        }?.deepCopy()?.apply {
            if (!has("checkedAt")) addProperty("checkedAt", previous!!.status.long("checkedAt"))
            addProperty("stale", true)
        }
        if (result.str("status") == "error") {
            previous?.takeIf { it.expires > clock() && clock() - (it.status.long("checkedAt") ?: 0) < 900_000 }?.let {
                result = it.status.deepCopy().apply {
                    addProperty("note", "Last successful quota snapshot. ${result.str("note")}")
                    if (isZai(key)) addProperty("stale", true)
                    remove("usageLimitResets")
                    resets?.let { retained -> add("usageLimitResets", retained) }
                }
            }
        } else if (result.str("status") == "available") {
            if (result.bool("usageLimitResetsUnavailable") == true && resets != null) result.add("usageLimitResets", resets)
            lastGood[key] = Entry(identity, result.deepCopy(), clock() + 900_000)
        } else {
            lastGood.remove(key)
        }
        var expires = if (result.str("status") == "available") minOf(clock() + ttl, (result.long("checkedAt") ?: clock()) + 900_000)
            else clock() + ttl
        result.obj("usageLimitResets")?.takeIf { it.bool("stale") == true }?.long("checkedAt")?.let { expires = minOf(expires, it + 900_000) }
        if (isZai(key) && (result.str("status") != "available" || result.bool("stale") == true)) {
            result.addProperty("retryAt", if (result.str("failureKind") == "authentication") authFailures[key.provider]?.retryAt ?: expires else expires)
        }
        cache[key] = Entry(identity, result, expires)
        onUpdate(Json.obj("directory" to key.directory, "status" to result.deepCopy()))
        return result
    }

    private fun isZai(key: Key) = key.provider in setOf("zai", "zai-coding-plan")

    /** Credentials stay host-only. No local database, files, inactive account or env fallback. */
    private fun attachedOpenAI(response: JsonElement?): JsonObject {
        val entries = response.asObjectOrNull().arr("data") ?: throw QuotaFailure("Invalid OpenCode V2 credential response")
        val active = entries.mapNotNull { it.asObjectOrNull() }.firstOrNull {
            it.str("integrationID") == "openai" && it.bool("active") == true
        }
        val selected = active.obj("value")
        if (selected.str("type") != "oauth" || selected.text("access") == null) return JsonObject()
        return Json.obj("openai" to selected!!.deepCopy().apply {
            active.text("id")?.let { addProperty("credentialID", it) }
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
        authFailures.clear()
    }

    override fun close() = synchronized(lifecycle) {
        closed = true
        inFlight.forEach { (key, future) -> future.complete(status(key, "error", "Provider quota backend is disposed")) }
        clearCache()
        executor.shutdownNow()
        Unit
    }
}
