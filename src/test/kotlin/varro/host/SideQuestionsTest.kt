package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class SideQuestionsTest {
    @Test fun `proxy side questions reject V1 stopped connections and foreign directories without forwarding`() {
        for ((version, running, directory) in listOf(Triple(1, true, "/repo"), Triple(2, false, "/repo"),
            Triple(2, true, "/foreign"), Triple(2, true, null))) {
            val questions = SideQuestions({ _, _, _, _ -> error("Must not forward") }, { version }, { running }, { 1 }, { listOf("/repo") })
            assertThrows(RuntimeException::class.java) {
                questions.ask("/session/ses_side/side-question", Json.obj("question" to "Why?"), directory) { false }
            }
        }
    }

    @Test fun `proxy uses a bounded cancellable request and rejects late connection changes and timeouts`() {
        for (outcome in listOf("success", "cancel", "restart", "stop", "version", "timeout")) {
            var now = 0L
            var generation = 1
            var running = true
            var version = 2
            var cancelled = false
            var calls = 0
            val questions = SideQuestions({ method, path, body, options ->
                calls++
                assertEquals("POST", method)
                assertEquals("/session/ses_side/side-question", path)
                assertEquals(Json.obj("question" to "Why?"), body)
                assertEquals("/repo", options.directory)
                assertEquals(512 * 1024L, options.maxResponseBytes)
                assertEquals(35_000L, options.timeoutMs)
                assertTrue(options.cancellableBody)
                when (outcome) {
                    "cancel" -> cancelled = true
                    "restart" -> generation++
                    "stop" -> running = false
                    "version" -> version = 1
                    "timeout" -> now = TimeUnit.SECONDS.toNanos(35)
                }
                options.checkCancelled()
                OpenCodeResponse(Json.obj("text" to "Because"))
            }, { version }, { running }, { generation }, { listOf("/repo") }, { now })
            fun ask() = questions.ask("/session/ses_side/side-question", Json.obj("question" to "Why?"), "/repo") { cancelled }
            when (outcome) {
                "success" -> assertEquals(Json.obj("text" to "Because"), ask())
                "cancel" -> assertThrows(CancellationException::class.java) { ask() }
                else -> assertThrows(IllegalStateException::class.java) { ask() }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun `proxy suppresses cancelled or stale answers even when wire returns without checking`() {
        var current = true
        val questions = SideQuestions({ _, _, _, _ -> current = false; OpenCodeResponse(Json.obj("text" to "Late")) },
            { 2 }, { true }, { current }, { listOf("/repo") })
        assertThrows(IllegalStateException::class.java) {
            questions.ask("/session/ses_side/side-question", Json.obj("question" to "Why?"), "/repo") { false }
        }
    }

    @Test(timeout = 10_000) fun `dialog cancellation or deadline stops a stalled generation body without interrupting the session`() {
        for (outcome in listOf("cancel", "timeout")) {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            val now = java.util.concurrent.atomic.AtomicLong()
            val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
            val endpoint = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            endpoint.createContext("/") { exchange ->
                calls.add("${exchange.requestMethod} ${exchange.requestURI.path}")
                try {
                    if (exchange.requestURI.path.endsWith("/generate")) {
                        exchange.sendResponseHeaders(200, 100)
                        exchange.responseBody.write('{'.code)
                        exchange.responseBody.flush()
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    } else {
                        val text = if (exchange.requestURI.path == "/api/info")
                            Json.stringify(Json.obj("version" to "2.0.29", "pid" to ProcessHandle.current().pid()))
                        else Json.stringify(Json.obj("data" to Json.obj("id" to "ses_side", "location" to Json.obj("directory" to "/repo"))))
                        val bytes = text.toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } finally { exchange.close() }
            }
            endpoint.start()
            val url = "http://127.0.0.1:${endpoint.address.port}"
            val transport = varro.server.OpenCodeTransport({ url }, { "/repo" }, { varro.server.ServerStatus.Running(url) }, { false }, {}, {})
            try {
                assertTrue(transport.checkHealth())
                val questions = SideQuestions(transport::request, { transport.apiVersion }, { true }, { 1 }, { listOf("/repo") }, now::get)
                val result = java.util.concurrent.CompletableFuture.supplyAsync {
                    runCatching { questions.ask("/session/ses_side/side-question", Json.obj("question" to "Why?"), "/repo", cancelled::get) }.exceptionOrNull()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (outcome == "cancel") cancelled.set(true) else now.set(TimeUnit.SECONDS.toNanos(35))
                val failure = result.get(2, TimeUnit.SECONDS)
                if (outcome == "cancel") assertTrue(failure is CancellationException)
                else assertEquals("Side question timed out", failure?.message)
                assertEquals(listOf("GET /api/info", "GET /api/session/ses_side", "POST /api/session/ses_side/generate"), calls)
            } finally {
                cancelled.set(true)
                release.countDown()
                transport.dispose()
                endpoint.stop(0)
            }
        }
    }
}
