package varro.host.quota

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class QuotaHttpTest {
    @Test fun `native HTTP supports JSON posts auth errors and rejects redirects without forwarding credentials`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val targetHits = AtomicInteger()
        server.createContext("/post") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer fixture", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("{}", exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val body = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/unauthorized") { exchange ->
            exchange.sendResponseHeaders(401, 2); exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/target") { exchange -> targetHits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val http = IdeQuotaHttp()
            assertEquals(200, http.send(QuotaRequest("$base/post", mapOf("Authorization" to "Bearer fixture"), "{}".toByteArray())).code)
            assertEquals(401, http.send(QuotaRequest("$base/unauthorized")).code)
            assertEquals(302, http.send(QuotaRequest("$base/redirect", mapOf("Authorization" to "Bearer fixture"))).code)
            assertEquals(0, targetHits.get())
        } finally { server.stop(0) }
    }

    @Test fun `native HTTP bounds response bytes and rejects nonlocal cleartext endpoints`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = ByteArray(1024 * 1024 + 1)
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertThrows(QuotaFailure::class.java) { IdeQuotaHttp().send(QuotaRequest("http://127.0.0.1:${server.address.port}/")) }
            assertThrows(IllegalArgumentException::class.java) { IdeQuotaHttp().send(QuotaRequest("http://example.com/")) }
        } finally { server.stop(0) }
    }
}
