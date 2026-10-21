package varro.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCodeTransportReadTest {
    @Test fun `failed always replies cannot create management links and legacy approvals never persist`() {
        var acknowledged = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val response = when (path) {
                "/permission" -> Json.array(listOf(Json.obj("id" to "request", "sessionID" to "session",
                    "permission" to "bash", "always" to listOf("git *"))))
                "/session/session" -> Json.obj("id" to "session", "projectID" to "project")
                "/project/current" -> Json.obj("id" to "project")
                else -> Json.obj()
            }
            val status = when {
                path == "/permission/request/reply" -> if (acknowledged) 200 else 409
                path.startsWith("/api/") -> 404
                else -> 200
            }
            val bytes = response.toString().toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        var writes = 0
        val transport = OpenCodeTransport({ url }, { "/fixture" }, { ServerStatus.Running(url) }, { false }, {}, {},
            persistSavedPermissions = { writes++ })
        try {
            assertThrows(OpenCodeRequestException::class.java) {
                transport.request("POST", "/permission/request/reply", Json.obj("reply" to "always"))
            }
            assertEquals(false, transport.savedPermissions.handle("GET", null, null, RequestOptions()).bool("supported"))
            acknowledged = true
            transport.request("POST", "/permission/request/reply", Json.obj("reply" to "always"))
            assertEquals(1, transport.savedPermissions.handle("GET", null, null, RequestOptions()).arr("rules")!!.size())
            assertEquals(0, writes)
            transport.resetProtocol()
            assertEquals(false, transport.savedPermissions.handle("GET", null, null, RequestOptions()).bool("supported"))
        } finally { transport.dispose(); server.stop(0) }
    }

    @Test fun `ordinary reads can be cancelled after response headers arrive`() {
        val headers = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/config/providers") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                headers.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        val transport = OpenCodeTransport({ url }, { "/fixture" }, { ServerStatus.Running(url) }, { false }, {}, {})
        val cancelled = AtomicBoolean(false)
        val result = CompletableFuture.supplyAsync {
            runCatching { transport.request("GET", "/config/providers", options = RequestOptions(
                isCancelled = cancelled::get)) }.exceptionOrNull()
        }
        try {
            assertTrue(headers.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(result.get(2, TimeUnit.SECONDS) is CancellationException)
        } finally {
            cancelled.set(true)
            release.countDown()
            transport.dispose()
            server.stop(0)
        }
    }

    @Test fun `a stalled response body cannot outlive the request deadline`() {
        val headers = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/session") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write('['.code)
                exchange.responseBody.flush()
                headers.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        val transport = OpenCodeTransport({ url }, { "/fixture" }, { ServerStatus.Running(url) }, { false }, {}, {})
        try {
            val result = CompletableFuture.supplyAsync {
                runCatching { transport.request("GET", "/session", options = RequestOptions(timeoutMs = 500,
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30))) }.exceptionOrNull()
            }
            assertTrue(headers.await(2, TimeUnit.SECONDS))
            assertTrue(result.get(2, TimeUnit.SECONDS) is java.net.http.HttpTimeoutException)
        } finally { release.countDown(); transport.dispose(); server.stop(0) }
    }

    @Test fun `an already expired adapter deadline never sends an HTTP request`() {
        val transport = OpenCodeTransport({ error("Must not connect") }, { "/fixture" }, { ServerStatus.Stopped }, { false }, {}, {})
        try {
            assertThrows(java.net.http.HttpTimeoutException::class.java) {
                transport.request("GET", "/session", options = RequestOptions(deadlineNanos = System.nanoTime() - 1))
            }
        } finally { transport.dispose() }
    }
}
