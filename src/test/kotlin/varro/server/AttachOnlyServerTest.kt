package varro.server

import com.sun.net.httpserver.HttpServer
import com.intellij.credentialStore.Credentials
import org.junit.Assert.*
import org.junit.Test
import varro.settings.VarroSettings
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AttachOnlyServerTest {
    @Test
    fun `external servers attach without a local CLI and reject forced restart`() {
        for (version in listOf("1.18.31", "2.0.10")) withServer(version) { server ->
            assertTrue(server.currentStatus().toString(), server.currentStatus() is ServerStatus.Running)
            assertTrue(server.isAttachOnly())
            assertFalse(server.isManaged())
            assertEquals(version, server.version())
            assertEquals(OpenCodeServer.RestartOutcome.NOT_MANAGED, server.restart(force = true))
            assertTrue(server.currentStatus() is ServerStatus.Running)
        }
    }

    @Test
    fun `old external server directs updates to the server host`() {
        withServer("1.0.0") { server ->
            val status = server.currentStatus() as ServerStatus.Error
            assertTrue(status.message.contains("attach-only mode"))
            assertTrue(status.message.contains("server host"))
            assertNull(status.detail)
        }
    }

    @Test
    fun `authentication failure retries prompted credentials before attaching`() {
        for (version in listOf("1.18.31", "2.0.10")) {
            var prompts = 0
            var saved = false
            val credentials = OpenCodeServerAuthentication(read = { null }, save = { _, value ->
                assertEquals("password", value.getPasswordAsString())
                saved = true
            }, prompt = { _, _ -> prompts++; Credentials("user", "password") })
            withServer(version, credentials) { server ->
                assertTrue(server.currentStatus().toString(), server.currentStatus() is ServerStatus.Running)
                assertEquals(1, prompts)
                assertTrue(saved)
                assertTrue(server.isAttachOnly())
            }
        }
    }

    private fun withServer(version: String, authentication: OpenCodeServerAuthentication? = null, check: (OpenCodeServer) -> Unit) {
        val endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint.createContext("/") { exchange ->
            if (authentication != null && exchange.requestHeaders.getFirst("Authorization") != OpenCodeConnection.authorization("password", "user")) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val bytes = """{"healthy":true,"version":"$version","pid":12345}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        endpoint.start()
        val server = OpenCodeServer(VarroSettings().apply {
            serverAutoStart = false
            serverPort = endpoint.address.port
            serverCommand = "/missing/attach-only-opencode"
        }) { null }
        if (authentication != null) server.authentication = authentication
        try {
            val ready = CountDownLatch(1)
            server.onStatus { if (it is ServerStatus.Running || it is ServerStatus.Error) ready.countDown() }
            server.ensureStarted()
            assertTrue("Server did not finish attaching", ready.await(10, TimeUnit.SECONDS))
            assertEquals("http://127.0.0.1:${endpoint.address.port}", server.url())
            check(server)
        } finally {
            server.dispose()
            endpoint.stop(0)
        }
    }
}
