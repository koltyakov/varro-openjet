package dev.koltyakov.varrojet.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.koltyakov.varrojet.protocol.Json
import dev.koltyakov.varrojet.protocol.asObjectOrNull
import dev.koltyakov.varrojet.protocol.int
import dev.koltyakov.varrojet.protocol.obj
import dev.koltyakov.varrojet.protocol.str
import java.io.BufferedWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Owns a lazy Node helper running the upstream provider service and adapters. */
class ProviderQuotaBackend(
    private val launch: () -> Process,
    private val request: (String, String, JsonElement?, String?) -> JsonElement?,
    private val onUpdate: (JsonObject) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val ids = AtomicInteger()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private var worker: Worker? = null
    private var closed = false
    private var retryAt = 0L
    private var startupError = "Provider quota helper is unavailable"

    private class Worker(val process: Process) {
        val writer: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
        val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        val ready = CompletableFuture<Unit>()
    }

    fun get(providerId: String, modelId: String?, directory: String?): JsonObject {
        var current: Worker? = null
        var id: Int? = null
        return try {
            current = ensureWorker()
            current.ready.get(10, TimeUnit.SECONDS)
            id = ids.incrementAndGet()
            val response = CompletableFuture<JsonObject>()
            synchronized(lock) {
                check(worker === current && !closed) { "Provider quota helper stopped" }
                current.pending[id] = response
                send(current, Json.obj(
                    "type" to "get", "id" to id, "providerID" to providerId,
                    "modelID" to modelId, "directory" to directory,
                ))
            }
            response.get(120, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            current?.let { stop(it) }
            Json.obj(
                "providerID" to providerId, "modelID" to modelId,
                "status" to "error", "source" to "provider",
                "checkedAt" to System.currentTimeMillis(),
                "note" to (if (current == null) startupError else "Provider quota helper stopped or timed out; retrying on the next poll."),
            )
        } finally {
            if (id != null) current?.pending?.remove(id)
        }
    }

    /** Stop in-flight polls as well as cached observations after auth/server changes. */
    fun clearCache() = synchronized(lock) {
        worker?.let(::stop)
        retryAt = 0
    }

    private fun ensureWorker(): Worker = synchronized(lock) {
        check(!closed) { "Provider quota backend is disposed" }
        worker?.let { if (it.process.isAlive) return it else stop(it) }
        check(System.currentTimeMillis() >= retryAt) { startupError }
        val process = try {
            launch()
        } catch (failure: Exception) {
            startupError = failure.message ?: "Unable to launch the provider quota helper"
            retryAt = System.currentTimeMillis() + 60_000
            throw failure
        }
        val next = Worker(process)
        worker = next
        executor.submit {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val message = Json.parseOrNull(line).asObjectOrNull()
                            ?: error("Invalid quota helper response")
                        receive(next, message)
                    }
                }
            } finally {
                stop(next)
            }
        }
        next
    }

    private fun receive(current: Worker, message: JsonObject) {
        when (message.str("type")) {
            "ready" -> current.ready.complete(Unit)
            "result" -> synchronized(lock) {
                if (worker !== current || closed) return
                val id = message.int("id") ?: error("Missing quota request ID")
                val data = message.obj("data") ?: error("Missing quota status")
                current.pending.remove(id)?.complete(data)
            }
            "updated" -> synchronized(lock) {
                if (worker === current && !closed) message.obj("update")?.let(onUpdate)
            }
            "server-request" -> executor.submit {
                val id = message.int("id") ?: return@submit
                val result = try {
                    val method = message.str("method") ?: error("Missing request method")
                    val path = message.str("path") ?: error("Missing request path")
                    check(isQuotaServerRequest(method, path)) { "Unsupported quota server request" }
                    // Skip requests queued by a retired helper.
                    synchronized(lock) { check(worker === current && !closed) }
                    val data = request(method, path, message.get("body"), message.str("directory"))
                    Json.obj("type" to "server-response", "id" to id, "data" to data)
                } catch (_: Exception) {
                    Json.obj("type" to "server-response", "id" to id, "error" to "OpenCode quota request failed")
                }
                synchronized(lock) {
                    if (worker === current && !closed) runCatching { send(current, result) }
                }
            }
            else -> error("Unexpected quota helper response")
        }
    }

    private fun send(current: Worker, message: JsonObject) {
        current.writer.write(Json.stringify(message))
        current.writer.newLine()
        current.writer.flush()
    }

    private fun stop(current: Worker) = synchronized(lock) {
        if (worker === current) worker = null
        val failure = IllegalStateException("Provider quota helper stopped")
        current.ready.completeExceptionally(failure)
        current.pending.values.forEach { it.completeExceptionally(failure) }
        current.pending.clear()
        current.process.destroyForcibly()
    }

    override fun close() = synchronized(lock) {
        closed = true
        worker?.let(::stop)
        executor.shutdownNow()
        Unit
    }

    companion object {
        internal fun isQuotaServerRequest(method: String, path: String): Boolean =
            (method == "GET" && path in setOf("/config/providers", "/experimental/console")) ||
                (method == "PUT" && Regex("^/auth/[A-Za-z0-9_.%~-]+$").matches(path))
    }
}
