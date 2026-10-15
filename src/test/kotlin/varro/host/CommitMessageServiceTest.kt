package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.settings.VarroSettings
import java.util.concurrent.CancellationException

class CommitMessageServiceTest {
    @Test fun `style rules enter both generation paths without weakening safety`() {
        for (version in listOf(1, 2)) {
            val prompts = mutableListOf<String>()
            val paths = mutableListOf<String>()
            val settings = VarroSettings().apply { commitMessageModel = "fixture/model" }
            val service = CommitMessageService(settings, { "/default" }, { version }) { method, path, body, options ->
                assertEquals("/selected", options.directory)
                if (method != "DELETE") assertTrue(options.cancellableBody)
                paths += "$method $path"
                OpenCodeResponse(when (path) {
                    "/openapi.json" -> Json.obj("paths" to Json.obj("/api/experimental/generate" to Json.obj("post" to Json.obj())))
                    "/api/experimental/generate" -> { prompts += body.asObjectOrNull().str("prompt")!!; Json.obj("data" to Json.obj("text" to "fix: totals")) }
                    "/session" -> {
                        assertEquals("deny", body.asObjectOrNull().arr("permission")!![0].asJsonObject.str("action"))
                        Json.obj("id" to "ses_helper")
                    }
                    "/session/ses_helper/prompt_async" -> { prompts += body.asObjectOrNull().arr("parts")!![0].asJsonObject.str("text")!!; Json.obj() }
                    "/session/ses_helper/message" -> Json.array(listOf(Json.obj("info" to Json.obj("role" to "assistant", "time" to Json.obj("completed" to 1)),
                        "parts" to listOf(Json.obj("type" to "text", "text" to "fix: totals")))))
                    "/session/ses_helper" -> Json.toElement(true)
                    else -> error("Unexpected $method $path")
                })
            }
            assertEquals(CommitMessageService.Result.Generated("fix: totals"), service.generate("+fix totals", listOf("old style"), "/selected", "Use Conventional Commits."))
            assertEquals(1, prompts.size)
            assertTrue(prompts.single().contains("Use Conventional Commits."))
            assertTrue(prompts.single().contains("Do not use tools"))
            assertTrue(prompts.single().contains("not the grounding, honesty, length, output format, or no-tools"))
            if (version == 2) assertFalse(paths.any { it == "POST /session" })
            else assertTrue(paths.contains("DELETE /session/ses_helper"))
        }
    }

    @Test fun `cancelled helper generation aborts and deletes only its helper without applying output`() {
        var cancelled = false
        val calls = mutableListOf<String>()
        val service = CommitMessageService(VarroSettings().apply { commitMessageModel = "fixture/model" }, { "/repo" }, { 1 }) { method, path, _, options ->
            calls += "$method $path"
            when (path) {
                "/session" -> OpenCodeResponse(Json.obj("id" to "ses_helper"))
                "/session/ses_helper/prompt_async" -> { cancelled = true; options.checkCancelled(); error("Unreachable") }
                "/session/ses_helper/abort", "/session/ses_helper" -> OpenCodeResponse(Json.toElement(true))
                else -> error("Unexpected $method $path")
            }
        }
        assertThrows(CancellationException::class.java) { service.generate("diff", emptyList(), isCancelled = { cancelled }) }
        assertEquals(listOf("POST /session", "POST /session/ses_helper/prompt_async", "POST /session/ses_helper/abort", "DELETE /session/ses_helper"), calls)
        calls.clear()
        assertThrows(CancellationException::class.java) { service.generate("diff", emptyList(), isCancelled = { true }) }
        assertTrue(calls.isEmpty())
    }

    @Test(timeout = 10_000) fun `cancellation interrupts stateless generation stalled in an HTTP response body`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/openapi.json") { exchange ->
            val body = Json.obj("paths" to Json.obj("/api/experimental/generate" to Json.obj("post" to Json.obj()))).toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.createContext("/api/experimental/generate") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{".toByteArray())
            exchange.responseBody.flush()
            entered.countDown()
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS) } finally { exchange.close() }
        }
        http.start()
        val url = "http://127.0.0.1:${http.address.port}"
        val transport = varro.server.OpenCodeTransport({ url }, { null },
            { varro.server.ServerStatus.Running(url, varro.server.EventStreamState.HEALTHY) }, { false }, {}, {})
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val service = CommitMessageService(VarroSettings().apply { commitMessageModel = "fixture/model" }, { null }, { 2 }, transport::request)
            val future = executor.submit<CommitMessageService.Result> { service.generate("diff", emptyList(), isCancelled = cancelled::get) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            cancelled.set(true)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
        } finally {
            release.countDown()
            transport.dispose()
            executor.shutdownNow()
            http.stop(0)
        }
    }
}
