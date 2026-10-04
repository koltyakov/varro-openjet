package varro.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import java.net.InetSocketAddress
import java.nio.file.Files

class OpenCodeRequestObserverTest {
    @Test fun `only acknowledged requests notify observers and observer failures do not change the response`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val directory = Files.createTempDirectory("varro-request-observer")
        var status = 200
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val body = if (status == 200) "true" else "{\"message\":\"reply failed\"}"
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val transport = OpenCodeTransport({ "http://127.0.0.1:${server.address.port}" }, { null }, { ServerStatus.Stopped }, { false }, {}, {},
            sessionStateDirectory = directory)
        val acknowledged = mutableListOf<String>()
        transport.onRequestSucceeded = { method, path -> acknowledged.add("$method $path") }
        try {
            transport.request("POST", "/permission/p/reply", Json.obj("reply" to "once"))
            assertEquals(listOf("POST /permission/p/reply"), acknowledged)
            status = 500
            assertThrows(Exception::class.java) { transport.request("POST", "/question/q/reply", Json.obj()) }
            assertEquals(1, acknowledged.size)
            status = 200
            transport.onRequestSucceeded = { _, _ -> error("observer failed") }
            assertTrue(transport.request("POST", "/question/q/reply", Json.obj()).data!!.asBoolean)
        } finally {
            transport.dispose()
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
