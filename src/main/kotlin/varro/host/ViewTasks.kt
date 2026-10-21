package varro.host

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Slow ordered actions and latest-only searches never occupy the bridge's inbound queue. */
internal class ViewTasks : AutoCloseable {
    private val closed = AtomicBoolean()
    private val revision = AtomicLong()
    private val ordered = Executors.newSingleThreadExecutor { Thread(it, "Varro view actions").apply { isDaemon = true } }
    private val searches = Executors.newVirtualThreadPerTaskExecutor()

    fun submit(latest: Boolean = false, work: (() -> Boolean) -> Unit) {
        if (closed.get()) return
        val generation = if (latest) revision.incrementAndGet() else 0L
        val cancelled = { closed.get() || Thread.currentThread().isInterrupted || (latest && revision.get() != generation) }
        try {
            (if (latest) searches else ordered).execute { if (!cancelled()) work(cancelled) }
        } catch (_: RejectedExecutionException) { /* Disposed between admission and execution. */ }
    }

    override fun close() {
        closed.set(true)
        revision.incrementAndGet()
        ordered.shutdownNow()
        searches.shutdownNow()
    }
}
