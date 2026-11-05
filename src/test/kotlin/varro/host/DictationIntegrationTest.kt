package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.host.HandyIntegration.Action
import varro.host.HandyIntegration.Status
import varro.protocol.*
import java.util.concurrent.CompletableFuture

class DictationIntegrationTest {
    @Test fun `remote host blocks both providers and synchronous discovery rejection releases the guard`() {
        val handy = Provider(running = true); val hex = Provider()
        DictationIntegration(handy::request, hex::request, localHost = { false }).apply {
            assertFalse(request(Action.START).join().available)
            dispose()
        }
        assertTrue(handy.calls.isEmpty()); assertTrue(hex.calls.isEmpty())
        var reject = true
        val integration = DictationIntegration(handy::request, { action, listener ->
            if (reject) throw java.util.concurrent.RejectedExecutionException("executor stopped")
            hex.request(action, listener)
        })
        assertTrue(integration.request(Action.START).join().error!!.contains("executor stopped"))
        assertFalse(Action.START in handy.calls)
        reject = false
        assertEquals("recording", integration.request(Action.START).join().state)
    }
    private class Provider(var available: Boolean = true, var running: Boolean = false) {
        val calls = mutableListOf<Action>()
        var revision = 0L
        var listener: ((Status) -> Unit)? = null
        var state = "idle"
        var pending: CompletableFuture<Status>? = null
        fun request(action: Action, onState: ((Status) -> Unit)?): CompletableFuture<Status> {
            calls += action
            if (action != Action.STATUS) { state = if (action == Action.START) "recording" else "idle"; listener = onState }
            return pending ?: CompletableFuture.completedFuture(Status(available, running, state = state, revision = ++revision))
        }
        fun publish(state: String, revision: Long = ++this.revision) { listener?.invoke(Status(available, running, state = state, revision = revision)) }
    }

    @Test fun `provider selection prefers running Hex then running Handy then installed Hex`() {
        for ((h, x, expected) in listOf(Triple(true, true, "hex"), Triple(true, false, "handy"), Triple(false, false, "hex"))) {
            val handy = Provider(running = h); val hex = Provider(running = x)
            val integration = DictationIntegration(handy::request, hex::request)
            assertEquals(expected, integration.request(Action.STATUS).join().provider)
            // A stale UI provider cannot override discovery when starting a fresh capture.
            assertEquals(expected, integration.request(Action.START, provider = if (expected == "hex") "handy" else "hex").join().provider)
            integration.dispose()
        }
    }

    @Test fun `capture owner retains finish and cancel routing and observations have monotonic revisions`() {
        val handy = Provider(running = true); val hex = Provider()
        val integration = DictationIntegration(handy::request, hex::request)
        val updates = mutableListOf<Status>()
        val start = integration.request(Action.START, updates::add).join()
        hex.running = true
        handy.publish("processing")
        val processing = updates.single()
        handy.publish("recording", 0)
        assertEquals(processing, updates.last())
        assertTrue(processing.revision > start.revision)
        assertEquals("handy", integration.request(Action.CANCEL, provider = "hex").join().provider)
        assertEquals(Action.CANCEL, handy.calls.last())
        assertFalse(Action.CANCEL in hex.calls)
        assertEquals("hex", integration.request(Action.START).join().provider)
        val count = updates.size
        handy.publish("processing")
        assertEquals(count, updates.size)
    }

    @Test fun `pending status defers one click and overlapping mutations cannot replay commands`() {
        val handy = Provider(running = true)
        val hex = Provider().apply { pending = CompletableFuture() }
        val integration = DictationIntegration(handy::request, hex::request)
        val status = integration.request(Action.STATUS)
        val start = integration.request(Action.START)
        hex.pending!!.complete(Status(true, false))
        hex.pending = null
        assertEquals("handy", status.join().provider)
        assertEquals("recording", start.join().state)
        handy.pending = CompletableFuture()
        val stop = integration.request(Action.STOP)
        val duplicate = integration.request(Action.CANCEL)
        handy.pending!!.complete(Status(true, true, revision = 100))
        assertEquals("idle", stop.join().state)
        assertTrue(duplicate.join().error!!.contains("pending"))
        assertFalse(Action.CANCEL in handy.calls)
    }

    @Test fun `wire validates provider and uses the new correlated protocol and disposal cancels discovery`() {
        val handy = Provider(running = true); val hex = Provider()
        var closed = false
        val integration = DictationIntegration(handy::request, hex::request, { closed = true })
        val replies = mutableListOf<com.google.gson.JsonObject>()
        for (provider in listOf(Json.obj("provider" to "other"), Json.obj("provider" to 1), Json.obj("provider" to null))) {
            provider.addProperty("requestId", "id"); provider.addProperty("action", "start")
            integration.handleRequest(provider, replies::add)
        }
        assertTrue(handy.calls.isEmpty()); assertTrue(hex.calls.isEmpty())
        integration.handleRequest(Json.obj("requestId" to "id", "action" to "start"), replies::add)
        assertEquals("dictation/result", replies.single().str("type"))
        assertEquals("id", replies.single().obj("payload").str("requestId"))
        assertEquals("handy", replies.single().obj("payload").str("provider"))
        integration.dispose(); assertTrue(closed)
        assertFalse(integration.request(Action.START).join().available)

        val pendingHex = Provider().apply { pending = CompletableFuture() }
        val pending = DictationIntegration(handy::request, pendingHex::request)
        val start = pending.request(Action.START)
        pending.dispose()
        pendingHex.pending!!.complete(Status(true, true))
        assertFalse(start.join().available)
        assertFalse(Action.START in pendingHex.calls)
    }
}
