package varro.host

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class RequestTasksTest {
    @Test fun `cancel before worker starts never executes the request`() {
        val waiting = ArrayDeque<Runnable>()
        val tasks = RequestTasks()
        var calls = 0
        tasks.submit("request", Executor { waiting.add(it) }) { calls++ }
        tasks.cancel("request")
        waiting.removeFirst().run()
        assertEquals(0, calls)
        tasks.submit("request", Executor { it.run() }) { calls++ }
        assertEquals(1, calls)
    }

    @Test fun `cancellation keeps a key reserved until the original worker finishes`() {
        val waiting = ArrayDeque<Runnable>()
        val tasks = RequestTasks()
        tasks.submit("key", Executor { waiting.add(it) }) { fail("cancelled") }
        tasks.cancel("key")
        assertThrows(IllegalArgumentException::class.java) { tasks.submit("key", Executor { it.run() }) {} }
        waiting.removeFirst().run()
        tasks.submit("key", Executor { it.run() }) { assertFalse(it.isCancelled()) }
    }

    @Test fun `disposal cancels running and unkeyed queued requests`() {
        val waiting = ArrayDeque<Runnable>()
        val tasks = RequestTasks()
        var running: RequestTasks.Ticket? = null
        tasks.submit("running", Executor { it.run() }) { running = it }
        tasks.submit(null, Executor { waiting.add(it) }) { fail("disposed") }
        tasks.submit("active", Executor { it.run() }) {
            running = it
            tasks.close()
            assertTrue(it.isCancelled())
        }
        waiting.removeFirst().run()
        assertTrue(running!!.isCancelled())
        assertThrows(IllegalStateException::class.java) { tasks.submit(null, Executor { it.run() }) {} }
    }

    @Test fun `executor rejection releases admission`() {
        val tasks = RequestTasks()
        assertThrows(RejectedExecutionException::class.java) {
            tasks.submit("key", Executor { throw RejectedExecutionException() }) {}
        }
        tasks.submit("key", Executor { it.run() }) { assertFalse(it.isCancelled()) }
    }
}
