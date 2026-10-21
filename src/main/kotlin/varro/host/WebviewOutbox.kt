package varro.host

import com.google.gson.JsonElement
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.str

/** Immutable envelopes can be shared by all views without serializing their payload again. */
internal data class WebviewMessage(val json: String, val replacementKey: String? = null) {
    companion object {
        fun encode(message: JsonElement): WebviewMessage {
            val type = message.asObjectOrNull().str("type")
            // Only full, replaceable snapshots. Never coalesce events, acknowledgements or commands.
            val key = type?.takeIf { it in setOf("context/update", "config/update", "editor-tabs/state") }
            return WebviewMessage(Json.stringifyMessage(message), key)
        }
    }
}

/** Serializes readiness, enqueueing and delivery. A stale scheduled drain cannot reach a new document. */
internal class WebviewOutbox(
    private val schedule: (() -> Unit) -> Unit,
    private val send: (String) -> Unit,
    private val resynchronize: () -> Unit,
    private val maxCharacters: Int = 4 * 1024 * 1024,
    private val maxMessages: Int = 2048,
    private val batchCharacters: Int = 256 * 1024,
) : AutoCloseable {
    private val queue = ArrayDeque<WebviewMessage>()
    private var characters = 0
    private var ready = false
    private var closed = false
    private var overflow = false
    private var scheduled = false
    private var generation = 0L

    @Synchronized fun post(message: WebviewMessage) {
        if (closed || overflow) return
        // Replacement must not cross an intervening event that observes the older snapshot.
        if (message.replacementKey != null && queue.lastOrNull()?.replacementKey == message.replacementKey) {
            characters -= queue.removeLast().json.length
        }
        if (characters.toLong() + message.json.length > maxCharacters || queue.size >= maxMessages) {
            if (ready) {
                drainAll()
                // A single large API response goes straight to the browser, never into the bounded queue.
                if (message.json.length > maxCharacters) {
                    send("[${message.json}]")
                    return
                }
            } else {
                queue.clear()
                characters = 0
                overflow = true
                return
            }
        }
        queue.addLast(message)
        characters += message.json.length
        scheduleDrain()
    }

    @Synchronized fun markReady() {
        if (closed) return
        ready = true
        if (overflow) {
            ready = false
            resynchronize()
        } else scheduleDrain()
    }

    @Synchronized fun reset() {
        generation++
        ready = false
        scheduled = false
        overflow = false
        queue.clear()
        characters = 0
    }

    @Synchronized fun documentLoading() {
        if (ready) reset() else {
            generation++
            scheduled = false
        }
    }

    private fun scheduleDrain() {
        if (!ready || scheduled || queue.isEmpty()) return
        scheduled = true
        val expected = generation
        schedule {
            synchronized(this) {
                if (closed || generation != expected) return@synchronized
                scheduled = false
                if (ready) drainBatch()
                scheduleDrain()
            }
        }
    }

    private fun drainAll() { while (queue.isNotEmpty()) drainBatch() }

    private fun drainBatch() {
        val batch = ArrayList<String>()
        var size = 0
        while (queue.isNotEmpty() && batch.size < 64 && (batch.isEmpty() || size + queue.first().json.length <= batchCharacters)) {
            val message = queue.removeFirst()
            characters -= message.json.length
            size += message.json.length
            batch.add(message.json)
        }
        if (batch.isNotEmpty()) send(batch.joinToString(",", "[", "]"))
    }

    @Synchronized override fun close() { reset(); closed = true }
}
