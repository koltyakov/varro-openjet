package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.util.concurrent.CancellationException

class OpenCodeV2ParityTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private fun shell(id: String = "shell_one", session: String = "ses_one") = Json.obj(
        "id" to id, "status" to "running", "metadata" to Json.obj("sessionID" to session), "time" to Json.obj("started" to 1000))

    @Test fun `background snapshots cannot overwrite newer events or other workspaces`() {
        var now = 0L
        val work = OpenCodeV2BackgroundWork { now }
        work.observe("shell.created", Json.obj("info" to shell()), "/one")
        val version = work.snapshotVersion()
        work.observe("session.step.ended", Json.obj("sessionID" to "ses_one", "finish" to "stop"), "/one")
        assertEquals(listOf("ses_one"), work.reconcile(listOf(shell()), setOf("ses_one"), "/one", version))
        assertEquals(1000.0, work.startedAt("ses_one"))
        work.reconcile(emptyList(), emptySet(), "/two", work.snapshotVersion())
        assertEquals(listOf("shell_one"), work.shellIDs("ses_one"))
        val beforeExit = work.snapshotVersion()
        work.observe("shell.exited", Json.obj("id" to "shell_one"), "/one")
        work.reconcile(listOf(shell()), emptySet(), "/one", beforeExit)
        assertTrue(work.shellIDs("ses_one").isEmpty())
        assertTrue(work.isWaiting("ses_one"))
        now = 1999
        work.reconcile(emptyList(), emptySet(), "/one", work.snapshotVersion())
        assertTrue(work.isWaiting("ses_one"))
        now = 2000
        work.reconcile(emptyList(), emptySet(), "/one", work.snapshotVersion())
        assertFalse(work.isWaiting("ses_one"))
    }

    @Test fun `background events and status snapshots support abort and continuation`() {
        val calls = mutableListOf<String>()
        val native = OpenCodeV2Adapter({ method, path, _, _ ->
            calls.add("$method $path")
            OpenCodeResponse(when {
                path == "/api/session/active" -> Json.obj("data" to Json.obj())
                path.startsWith("/api/shell?") -> Json.obj("data" to listOf(shell()))
                else -> Json.obj()
            })
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        fun event(type: String, data: com.google.gson.JsonObject) = native.events(Json.obj("id" to "evt_test", "type" to type,
            "durable" to Json.obj("seq" to 1), "location" to Json.obj("directory" to "/one"), "data" to data))
        event("shell.created", Json.obj("info" to shell()))
        val waiting = event("session.step.ended", Json.obj("sessionID" to "ses_one", "finish" to "stop"))
        assertEquals(2, waiting.size)
        assertEquals(true, waiting.last().obj("properties").obj("status").bool("background"))
        assertEquals("evt_test:waiting", waiting.last().str("id"))
        assertFalse(waiting.last().has("seq"))
        val succeeded = event("session.execution.succeeded", Json.obj("sessionID" to "ses_one")).single()
        assertEquals(true, succeeded.obj("properties").obj("status").bool("background"))
        val status = native.request("GET", "/session/status", null, RequestOptions(directory = "/one")).data.asObjectOrNull().obj("ses_one")
        assertEquals(true, status.bool("background"))
        assertEquals(1000L, status.long("backgroundStartedAt"))
        native.request("POST", "/session/ses_one/abort", null, RequestOptions(directory = "/one"))
        assertTrue(calls.indexOf("DELETE /api/shell/shell_one?location%5Bdirectory%5D=%2Fone") < calls.indexOf("POST /api/session/ses_one/interrupt?resume=false"))
        assertFalse(native.request("GET", "/session/status", null, RequestOptions(directory = "/one")).data.asObjectOrNull()!!.has("ses_one"))
        event("session.execution.started", Json.obj("sessionID" to "ses_one"))
        event("session.step.ended", Json.obj("sessionID" to "ses_one", "finish" to "stop"))
        val resumed = event("session.step.started", Json.obj("sessionID" to "ses_one"))
        assertEquals(1, resumed.size)
        val finished = event("session.execution.interrupted", Json.obj("sessionID" to "ses_one")).single()
        assertEquals("idle", finished.obj("properties").obj("status").str("type"))
    }

    @Test fun `OAuth callbacks isolate attempts by id and directory and clean up cancellation`() {
        var next = 0
        var cancelled = false
        val completed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val native = OpenCodeV2Adapter({ method, path, _, _ ->
            val route = path.substringBefore('?')
            OpenCodeResponse(when {
                route == "/api/provider/cloud" -> Json.obj("data" to Json.obj("integrationID" to "login"))
                route == "/api/integration/login" -> Json.obj("data" to Json.obj("methods" to listOf(Json.obj("id" to "oauth", "type" to "oauth"))))
                route.endsWith("/connect/oauth") -> Json.obj("data" to Json.obj("attemptID" to "attempt_${++next}", "url" to "https://example.com"))
                method == "DELETE" -> { deleted.add(path); Json.obj() }
                method == "GET" -> {
                    completed.add(path)
                    if (route.endsWith("attempt_4")) { cancelled = true; Json.obj("data" to Json.obj("status" to "pending")) }
                    else Json.obj("data" to Json.obj("status" to "complete"))
                }
                else -> error("Unexpected request $method $path")
            })
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        fun authorize(directory: String) = native.request("POST", "/provider/cloud/oauth/authorize", Json.obj("method" to 0), RequestOptions(directory = directory)).data.asObjectOrNull().str("attemptID")!!
        fun callback(directory: String, id: String?) = native.request("POST", "/provider/cloud/oauth/callback", Json.obj("attemptID" to id), RequestOptions(directory = directory, isCancelled = { cancelled }))
        val one = authorize("/one")
        val two = authorize("/one")
        val three = authorize("/two")
        assertThrows(IllegalStateException::class.java) { callback("/one", null) }
        assertThrows(IllegalStateException::class.java) { callback("/two", one) }
        callback("/one", one)
        callback("/one", two)
        callback("/two", three)
        assertEquals(3, completed.size)
        assertTrue(completed[0].contains("attempt_1?location%5Bdirectory%5D=%2Fone"))
        val four = authorize("/one")
        assertThrows(CancellationException::class.java) { callback("/one", four) }
        assertEquals(listOf("/api/integration/login/connect/oauth/attempt_4?location%5Bdirectory%5D=%2Fone"), deleted)
        cancelled = false
        assertThrows(IllegalStateException::class.java) { callback("/one", four) }
    }

    @Test fun `API key forms receive typed answers and conditional hidden defaults`() {
        var sent: com.google.gson.JsonObject? = null
        val native = OpenCodeV2Adapter({ _, path, body, _ -> OpenCodeResponse(when (path) {
            "/api/provider/cloud" -> Json.obj("data" to Json.obj("integrationID" to "login"))
            "/api/integration/login" -> Json.obj("data" to Json.obj("methods" to listOf(Json.obj("type" to "key", "form" to listOf(
                Json.obj("key" to "enabled", "type" to "boolean"), Json.obj("key" to "port", "type" to "integer"),
                Json.obj("key" to "server", "type" to "string", "hidden" to true, "default" to "local", "when" to listOf(Json.obj("key" to "enabled", "op" to "eq", "value" to true))))))))
            "/api/integration/login/connect/key" -> { sent = body.asObjectOrNull(); Json.obj() }
            else -> error("Unexpected $path")
        }) }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        fun connect(port: String) = native.request("PUT", "/auth/cloud", Json.obj("type" to "api", "key" to "secret",
            "metadata" to Json.obj("enabled" to "true", "port" to port)), RequestOptions())
        connect("8080")
        assertEquals("secret", sent.str("key"))
        assertEquals(true, sent.obj("answer").bool("enabled"))
        assertEquals(8080, sent.obj("answer").int("port"))
        assertEquals("local", sent.obj("answer").str("server"))
        for (invalid in listOf("", "1.5", "NaN", "Infinity")) assertThrows(IllegalArgumentException::class.java) { connect(invalid) }
    }

    @Test fun `configured model pricing is projected and partial limits retain catalog values`() {
        val native = OpenCodeV2Adapter({ _, path, _, _ -> OpenCodeResponse(when (path) {
            "/api/model" -> Json.obj("data" to listOf(Json.obj("id" to "test", "providerID" to "cloud", "limit" to Json.obj("context" to 100000, "output" to 1000))))
            "/api/provider" -> Json.obj("data" to listOf(Json.obj("id" to "cloud")))
            "/api/integration" -> Json.obj("data" to emptyList<Any>())
            "/api/config" -> Json.obj("data" to listOf(Json.obj("info" to Json.obj("providers" to Json.obj("cloud" to Json.obj("models" to Json.obj("test" to Json.obj(
                "cost" to listOf(Json.obj("input" to 2, "output" to 8, "cache" to Json.obj("read" to 1))), "limit" to Json.obj("output" to 2000)))))))))
            else -> error("Unexpected $path")
        }) }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        val model = native.request("GET", "/provider", null, RequestOptions()).data.asObjectOrNull().arr("all")!![0].asJsonObject.obj("models").obj("test")
        assertEquals(2.0, model.obj("cost").num("input"))
        assertEquals(1.0, model.obj("cost").num("cache_read"))
        assertEquals(100000, model.obj("limit").int("context"))
        assertEquals(2000, model.obj("limit").int("output"))
    }

    @Test fun `directory encoding preserves Unicode and literal percent escapes across server families`() {
        val directory = "/workspace/日本語/%20 project"
        val header = OpenCodeRequestScope.directoryHeaders(directory).getValue(OpenCodeRequestScope.DIRECTORY_HEADER)
        assertTrue(header.all { it.code < 128 })
        assertEquals(directory, java.net.URLDecoder.decode(header, Charsets.UTF_8))
        for (legacy in listOf(false, true)) {
            val scoped = OpenCodeRequestScope.scope("http://localhost:1234", "/session", directory, legacy)
            val query = java.net.URI(scoped.url).rawQuery.substringAfter("directory=")
            val decoded = java.net.URLDecoder.decode(query, Charsets.UTF_8)
            assertEquals(directory, if (legacy) java.net.URLDecoder.decode(decoded, Charsets.UTF_8) else decoded)
            assertEquals(directory, scoped.directory)
        }
    }

    @Test fun `cancelled HTTP requests do not wait for response headers`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/session") { exchange ->
            entered.countDown()
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS) } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        val transport = OpenCodeTransport({ url }, { null }, { ServerStatus.Running(url, EventStreamState.HEALTHY) }, { false }, {}, {})
        try {
            val request = java.util.concurrent.CompletableFuture.supplyAsync {
                transport.request("GET", "/session", options = RequestOptions(isCancelled = cancelled::get))
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cancelled.set(true)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { request.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
        } finally {
            release.countDown()
            transport.dispose()
            server.stop(0)
        }
    }

    @Test fun `cancelled annotation updates preserve the previous state`() {
        val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
        state.update("ses_one", Json.obj("metadata" to Json.obj("title" to "original")))
        assertThrows(CancellationException::class.java) {
            state.update("ses_one", Json.obj("metadata" to Json.obj("title" to "cancelled"))) { throw CancellationException() }
        }
        assertEquals("original", state.read("ses_one").obj("metadata").str("title"))
        state.remove("ses_one")
        assertEquals(0, state.read("ses_one").size())
    }
}
