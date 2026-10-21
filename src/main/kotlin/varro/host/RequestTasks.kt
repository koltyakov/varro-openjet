package varro.host

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Admission happens on the ordered bridge thread, before a worker can start. */
internal class RequestTasks : AutoCloseable {
    private val tickets = mutableSetOf<Ticket>()
    private val keys = mutableMapOf<String, Ticket>()
    private var closed = false

    class Ticket internal constructor(val key: String?) {
        private val cancelled = AtomicBoolean()
        fun isCancelled(): Boolean = cancelled.get()
        internal fun cancel() { cancelled.set(true) }
    }

    fun submit(key: String?, executor: Executor, work: (Ticket) -> Unit) {
        val ticket = synchronized(this) {
            check(!closed) { "REST proxy disposed" }
            require(key == null || key !in keys) { "Duplicate API request cancellation key" }
            Ticket(key).also { ticket ->
                tickets.add(ticket)
                key?.let { keys[it] = ticket }
            }
        }
        try {
            executor.execute {
                try { if (!ticket.isCancelled()) work(ticket) }
                finally { finish(ticket) }
            }
        } catch (failure: Exception) {
            finish(ticket)
            throw failure
        }
    }

    @Synchronized private fun finish(ticket: Ticket) {
        tickets.remove(ticket)
        ticket.key?.let { keys.remove(it, ticket) }
    }

    @Synchronized fun cancel(key: String) { keys[key]?.cancel() }

    @Synchronized override fun close() {
        closed = true
        tickets.forEach { it.cancel() }
        tickets.clear()
        keys.clear()
    }
}
