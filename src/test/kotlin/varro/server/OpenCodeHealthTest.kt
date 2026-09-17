package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class OpenCodeHealthTest {
    @Test fun `detects both protocol families and rejects HTML and authentication failures`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var family = 2
        var authenticated = true
        server.createContext("/") { exchange ->
            val authorized = exchange.requestHeaders.getFirst("Authorization") == OpenCodeConnection.authorization("test-only")
            val text = when {
                !authorized -> "Unauthorized"
                family == 2 && exchange.requestURI.path == "/api/status" -> Json.stringify(Json.obj("version" to "2.0.5", "pid" to ProcessHandle.current().pid()))
                family == 1 && exchange.requestURI.path == "/global/health" -> Json.stringify(Json.obj("version" to "1.18.31", "healthy" to true))
                else -> "<!doctype html><html>OpenCode</html>"
            }.toByteArray()
            exchange.sendResponseHeaders(if (authorized) 200 else 401, text.size.toLong())
            exchange.responseBody.use { it.write(text) }
        }
        server.start()
        val transport = OpenCodeTransport({ "http://127.0.0.1:${server.address.port}" }, { null }, { ServerStatus.Stopped }, { false }, {}, {},
            { if (authenticated) OpenCodeConnection.authorization("test-only") else null })
        try {
            assertEquals("2.0.5", transport.readHealthInfo().version)
            assertEquals(2, transport.apiVersion)
            family = 1
            assertEquals("1.18.31", transport.readHealthInfo().version)
            assertEquals(1, transport.apiVersion)
            family = 0
            assertFalse(transport.checkHealth())
            authenticated = false
            assertFalse(transport.checkHealth())
            assertTrue(transport.healthFailure!!.contains("authentication"))
        } finally { transport.dispose(); server.stop(0) }
    }
}
