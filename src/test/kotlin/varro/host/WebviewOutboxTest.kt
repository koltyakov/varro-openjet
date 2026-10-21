package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebviewOutboxTest {
    private class Fixture(maxCharacters: Int = 1024, maxMessages: Int = 100) {
        val scheduled = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<Int>()
        var calls = 0
        var resyncs = 0
        val outbox = WebviewOutbox({ scheduled.add(it) }, { json ->
            calls++
            Json.parse(json).asJsonArray.forEach { delivered.add(it.asInt) }
        }, { resyncs++ }, maxCharacters, maxMessages)
        fun drain() { while (scheduled.isNotEmpty()) scheduled.removeFirst()() }
    }

    @Test fun `ready and later events share one ordered drain`() {
        val f = Fixture()
        f.outbox.post(WebviewMessage("1"))
        f.outbox.documentLoading()
        f.outbox.markReady()
        f.outbox.post(WebviewMessage("2"))
        f.drain()
        assertEquals(listOf(1, 2), f.delivered)
        assertEquals(1, f.calls)
    }

    @Test fun `snapshots coalesce without crossing event boundaries`() {
        val f = Fixture()
        f.outbox.post(WebviewMessage("1", "context"))
        f.outbox.post(WebviewMessage("2", "context"))
        f.outbox.post(WebviewMessage("3"))
        f.outbox.post(WebviewMessage("4", "context"))
        f.outbox.markReady()
        f.drain()
        assertEquals(listOf(2, 3, 4), f.delivered)
    }

    @Test fun `startup overflow requests resynchronization instead of replaying partial history`() {
        val f = Fixture(maxMessages = 2)
        repeat(4) { f.outbox.post(WebviewMessage("$it")) }
        f.outbox.markReady()
        f.drain()
        assertEquals(1, f.resyncs)
        assertTrue(f.delivered.isEmpty())
        f.outbox.reset()
        f.outbox.post(WebviewMessage("9"))
        f.outbox.markReady()
        f.drain()
        assertEquals(listOf(9), f.delivered)
    }

    @Test fun `reload invalidates old drains and disposal prevents delivery`() {
        val f = Fixture()
        f.outbox.markReady()
        f.outbox.post(WebviewMessage("1"))
        f.outbox.reset()
        f.outbox.markReady()
        f.outbox.post(WebviewMessage("2"))
        f.drain()
        assertEquals(listOf(2), f.delivered)
        f.outbox.post(WebviewMessage("3"))
        f.outbox.close()
        f.drain()
        assertEquals(listOf(2), f.delivered)
    }

    @Test fun `four views deliver ten thousand streamed events each with bounded batching`() {
        val views = List(4) { Fixture(maxCharacters = 2048, maxMessages = 128) }
        views.forEach { it.outbox.markReady() }
        repeat(10_000) { index ->
            val message = WebviewMessage("$index")
            views.forEach { it.outbox.post(message) }
        }
        views.forEach {
            it.drain()
            assertEquals((0 until 10_000).toList(), it.delivered)
            assertTrue("Bridge calls should be batched", it.calls < 200)
        }
    }

    @Test fun `concurrent ready and posts never strand messages`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val delivered = CountDownLatch(1000)
        val outbox = WebviewOutbox({ executor.execute(it) }, { batch ->
            Json.parse(batch).asJsonArray.forEach { delivered.countDown() }
        }, { fail("unexpected overflow") })
        try {
            val writer = Thread { repeat(1000) { outbox.post(WebviewMessage("$it")) } }
            writer.start()
            outbox.markReady()
            writer.join()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
        } finally { outbox.close(); executor.shutdownNow() }
    }
}
