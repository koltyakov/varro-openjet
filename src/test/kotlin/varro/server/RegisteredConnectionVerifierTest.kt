package varro.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import java.net.InetSocketAddress

class RegisteredConnectionVerifierTest {
    private fun fixture(action: (HttpServer, MutableList<Pair<String, String?>>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val probes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        try { action(server, probes) } finally { server.stop(0) }
    }

    @Test fun `both API families require anonymous and wrong-password rejection`() {
        for (version in listOf("1.18.34", "2.0.24")) fixture { server, probes ->
            server.createContext("/") { exchange ->
                val route = exchange.requestURI.path
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                probes.add(route to authorization)
                val bytes = Json.stringify(Json.obj("version" to version, "pid" to 123, "healthy" to true)).toByteArray()
                val supported = if (version.startsWith("1.")) route == "/global/health" else route == "/api/info"
                val status = when {
                    !supported -> 404
                    authorization != OpenCodeConnection.authorization("secret", "varro-user") -> 401
                    else -> 200
                }
                exchange.sendResponseHeaders(status, if (status == 200) bytes.size.toLong() else -1)
                if (status == 200) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
            server.start()
            assertTrue(RegisteredConnectionVerifier().verify(ServerOwnership.Connection(server.address.port, "secret", "varro-user")))
            val relevant = probes.filter { it.first == if (version.startsWith("1.")) "/global/health" else "/api/info" }
            assertEquals(3, relevant.size)
            assertNull(relevant[0].second)
            assertNotEquals(OpenCodeConnection.authorization("secret", "varro-user"), relevant[1].second)
            assertEquals(OpenCodeConnection.authorization("secret", "varro-user"), relevant[2].second)
        }
    }

    @Test fun `a server accepting anonymous or arbitrary credentials is not registered`() {
        for (acceptAnonymous in listOf(true, false)) fixture { server, probes ->
            server.createContext("/") { exchange ->
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                probes.add(exchange.requestURI.path to authorization)
                val status = if (acceptAnonymous || authorization != null) 200 else 401
                val bytes = """{"version":"2.0.24","pid":123}""".toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            assertFalse(RegisteredConnectionVerifier().verify(ServerOwnership.Connection(server.address.port, "secret")))
            assertEquals(if (acceptAnonymous) 1 else 2, probes.size)
        }
    }

    @Test fun `redirects and unsupported API families do not establish attachment`() {
        for (redirect in listOf(true, false)) fixture { server, _ ->
            server.createContext("/") { exchange ->
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                val status = when {
                    redirect -> 302
                    authorization != OpenCodeConnection.authorization("secret") -> 401
                    else -> 200
                }
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/elsewhere")
                val bytes = """{"version":"3.0.0","pid":123}""".toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            assertFalse(RegisteredConnectionVerifier().verify(ServerOwnership.Connection(server.address.port, "secret")))
        }
    }
}
