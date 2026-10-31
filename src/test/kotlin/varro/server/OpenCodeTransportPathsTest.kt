package varro.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenCodeTransportPathsTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `managed requests responses errors and SSE use editor aliases while attach-only stays exact`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
        val root = temporary.newFolder().toPath().toRealPath()
        val physical = Files.createDirectory(root.resolve("physical")).toString()
        val logical = Files.createSymbolicLink(root.resolve("logical"), java.nio.file.Path.of(physical)).toString()
        val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
        val events = CopyOnWriteArrayList<com.google.gson.JsonElement>()
        val delivered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/global/event") {
                try {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    val payload = Json.obj("directory" to physical, "payload" to Json.obj("type" to "session.created",
                        "properties" to Json.obj("info" to Json.obj("id" to "session", "directory" to physical))))
                    exchange.responseBody.write("data: $payload\n\n".toByteArray())
                    exchange.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                } finally { exchange.close() }
            } else {
                requests.add(Triple(URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), Charsets.UTF_8),
                    exchange.requestHeaders.getFirst("x-opencode-directory")?.let { URLDecoder.decode(it, Charsets.UTF_8) },
                    exchange.requestBody.bufferedReader().readText()))
                val response = if (exchange.requestURI.path == "/error") Json.obj("message" to "$physical/missing")
                    else Json.obj("id" to "session", "directory" to physical, "path" to "$physical/README.md")
                val bytes = response.toString().toByteArray()
                exchange.sendResponseHeaders(if (exchange.requestURI.path == "/error") 409 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        var managed = true
        val transport = OpenCodeTransport({ url }, { logical }, { ServerStatus.Running(url) }, { false }, {}, {
            events.add(it); delivered.countDown()
        }, canAliasWorkspacePaths = { managed })
        try {
            // Learn aliases before the first REST request, as a normal startup does.
            transport.startEventStream()
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            assertEquals(logical, events.single().asObjectOrNull().str("directory"))
            assertEquals(logical, transport.observedSessionDirectories()["session"])
            val body = Json.obj("path" to "$logical/README.md")
            val result = transport.request("POST", "/session", body).data.asObjectOrNull()
            assertEquals(logical, result.str("directory"))
            assertEquals("$logical/README.md", result.str("path"))
            assertEquals("$logical/README.md", body.str("path"))
            assertEquals(physical, requests.last().second)
            assertTrue(requests.last().first.contains("directory=$physical"))
            assertEquals("$physical/README.md", Json.parse(requests.last().third).asObjectOrNull().str("path"))
            transport.request("GET", "/api/fixture?location%5Bdirectory%5D=$logical", options = RequestOptions(unscoped = true))
            assertTrue(requests.last().first.contains("location[directory]=$physical"))
            val error = assertThrows(OpenCodeRequestException::class.java) { transport.request("GET", "/error") }
            assertEquals("409 $logical/missing", error.message)
            transport.onSessionObserved = { fail("Maintenance records must not replace IDE directory observations") }
            assertEquals(physical, transport.request("POST", "/session", body,
                RequestOptions(directory = logical, nativePaths = true)).data.asObjectOrNull().str("directory"))
            assertEquals(logical, requests.last().second)
            assertEquals("$logical/README.md", Json.parse(requests.last().third).asObjectOrNull().str("path"))
            assertEquals(logical, transport.observedSessionDirectories()["session"])
            transport.onSessionObserved = {}
            managed = false
            assertEquals(physical, transport.request("POST", "/session", body).data.asObjectOrNull().str("directory"))
            assertEquals(logical, requests.last().second)
            assertEquals("$logical/README.md", Json.parse(requests.last().third).asObjectOrNull().str("path"))
        } finally { release.countDown(); transport.dispose(); server.stop(0); executor.shutdownNow() }
    }
}
