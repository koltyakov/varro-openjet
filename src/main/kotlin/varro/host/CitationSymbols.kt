package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*

/** Exact indexed definitions only. Slow providers share bounded calls across retries. */
internal class CitationSymbols(
    private val lookup: (String, Path) -> List<Definition>,
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = 2_000,
) : AutoCloseable {
    data class Definition(val name: String, val path: Path, val line: Int, val character: Int = 0)
    private class Call(val started: Long, val probe: Boolean) {
        val result = CompletableFuture<JsonElement>()
        var expires = Long.MAX_VALUE
        var overdue = false
    }
    private val lock = Any()
    private val cache = linkedMapOf<Pair<Path, String>, Call>()
    private val running = mutableSetOf<Call>()
    private val abandoned = mutableSetOf<Call>()
    private var reopenAt: Long? = null
    private var cooldown = 60_000L
    private var closed = false
    private val executor = ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, SynchronousQueue(), ThreadFactory { task ->
        Thread(task, "OpenJet citation symbols").apply { isDaemon = true }
    })

    fun find(body: JsonElement?, defaultDirectory: String?, authorize: (String?, String) -> Boolean,
        cancelled: () -> Boolean = { false }): JsonArray {
        val record = body.asObjectOrNull() ?: error("Invalid citation symbols")
        val symbols = record.arr("symbols") ?: error("Invalid citation symbols")
        require(symbols.size() <= 32 && symbols.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length in 1..128 }) { "Invalid citation symbols" }
        val session = record.text("sessionID")
        require(!record.has("sessionID") || session != null && session.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid citation session" }
        val directory = record.text("directory") ?: defaultDirectory
        require(!record.has("directory") || record.text("directory") != null && directory != null && directory.length <= 4096) { "Invalid citation directory" }
        val root = directory?.let { Path.of(it).normalize() }
        if (root != null) require(root.isAbsolute && authorize(session, directory)) { "Session workspace folder is not open" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        // Admit both provider slots before waiting for either, including duplicate coalescing.
        val calls = symbols.map { symbol ->
            checkCancelled(cancelled)
            val name = parse(symbol.asString)
            if (root == null || name == null) null else synchronized(lock) { call(root, name) }
        }
        return Json.array(calls.map { call ->
            if (call == null) JsonNull.INSTANCE else {
                while (!call.result.isDone && System.nanoTime() < deadline) {
                    checkCancelled(cancelled)
                    try { call.result.get(minOf(50, TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0)) + 1), TimeUnit.MILLISECONDS) }
                    catch (_: TimeoutException) { /* Poll caller cancellation without cancelling a shared lookup. */ }
                }
                checkCancelled(cancelled)
                call.result.getNow(JsonPrimitive("pending"))
            }
        }).also { checkCancelled(cancelled) }
    }

    private fun call(root: Path, name: String): Call? {
        if (closed) return null
        running.toList().filter { !it.overdue && now() - it.started >= 20_000 }.forEach { call ->
            call.overdue = true
            if (abandoned.size < 2) { running.remove(call); abandoned.add(call) }
            if (call.probe || call in running && reopenAt == null) {
                reopenAt = now() + cooldown
                cooldown = (cooldown * 2).coerceAtMost(600_000)
            }
        }
        val key = root to name
        cache[key]?.takeIf { it.expires > now() }?.let {
            return if (!it.result.isDone && reopenAt != null && !it.probe) null else it
        }
        val probe = reopenAt != null
        if (probe && (now() < reopenAt!! || running.any { it.probe && !it.overdue })) return null
        if (running.size >= 2) return if (probe) null else Call(now(), false).apply { result.complete(JsonPrimitive("pending")) }
        val call = Call(now(), probe)
        running.add(call)
        cache.remove(key)
        cache[key] = call
        if (cache.size > 256) cache.remove(cache.keys.first())
        try {
            executor.execute {
                val result = try {
                    val definitions = lookup(name, root)
                    val matches = if (definitions.size > 1_000) emptyList() else definitions.filter {
                        it.name == name && it.path.isAbsolute && it.path.normalize().startsWith(root) && it.path.normalize() != root && it.line >= 0 && it.character >= 0
                    }.distinctBy { Triple(it.path.normalize(), it.line, it.character) }
                    matches.singleOrNull()?.takeIf { Files.isRegularFile(it.path) }?.let {
                        Json.obj("path" to it.path.normalize().toString(), "line" to it.line.toLong() + 1)
                    } ?: JsonNull.INSTANCE
                } catch (_: Exception) { JsonNull.INSTANCE }
                synchronized(lock) {
                    running.remove(call); abandoned.remove(call)
                    reopenAt = null; cooldown = 60_000
                    call.expires = now() + if (result.isJsonNull) 1_000 else 15_000
                    call.result.complete(if (closed) JsonNull.INSTANCE else result)
                }
            }
        } catch (_: RejectedExecutionException) {
            running.remove(call); cache.remove(key)
            return null
        }
        return call
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            (running + abandoned).forEach { it.result.complete(JsonNull.INSTANCE) }
            cache.clear()
        }
        executor.shutdownNow()
    }

    companion object {
        internal fun parse(text: String): String? {
            val value = text.trim()
            if (value.length > 128) return null
            return Regex("([A-Za-z_][A-Za-z0-9_]*)\\(\\)").matchEntire(value)?.groupValues?.get(1)
                ?: value.takeIf { Regex("[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+").matches(it) }
        }
        private fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Citation lookup cancelled")
        }
    }
}
