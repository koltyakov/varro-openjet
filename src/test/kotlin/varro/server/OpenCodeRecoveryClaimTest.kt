package varro.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.Json
import varro.settings.VarroSettings
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenCodeRecoveryClaimTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `recovering followers wait for credential publication before any endpoint probe`() {
        for (version in listOf("1.19.0", "2.0.5", "2.0.26")) fixture(version) { server, root, port, claim, probed ->
            startRecovery(server)
            assertFalse("Follower probed during replacement publication", probed.await(500, TimeUnit.MILLISECONDS))
            publish(root, port, "replacement-password")
            claim.endLaunch()
            val ready = CountDownLatch(1)
            val unsubscribe = server.onStatus { if (it is ServerStatus.Running || it is ServerStatus.Error) ready.countDown() }
            try {
                if (server.currentStatus() !is ServerStatus.Running) assertTrue(ready.await(10, TimeUnit.SECONDS))
                assertTrue(server.currentStatus().toString(), server.currentStatus() is ServerStatus.Running)
                assertEquals(version, server.version())
                val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (Files.exists(root.resolve("varro-opencode-server-$port.json.claim"))) {
                    assertTrue("Startup did not release its recovery claim", System.nanoTime() < releaseDeadline)
                    Thread.sleep(10)
                }
            } finally { unsubscribe() }
        }
    }

    @Test fun `disposing a recovering follower does not probe or release another hosts claim`() = fixture("2.0.26") { server, root, port, _, probed ->
        startRecovery(server)
        assertFalse(probed.await(300, TimeUnit.MILLISECONDS))
        server.dispose()
        assertFalse(probed.await(300, TimeUnit.MILLISECONDS))
        assertTrue(Files.exists(root.resolve("varro-opencode-server-$port.json.claim")))
    }

    private fun startRecovery(server: OpenCodeServer) {
        // Seed the recovery phase only; run the actual startup, registration and transport code.
        OpenCodeServer::class.java.getDeclaredField("followsManagedRegistration").apply { isAccessible = true }.setBoolean(server, true)
        val starting = CountDownLatch(1)
        val unsubscribe = server.onStatus { if (it is ServerStatus.Starting) starting.countDown() }
        try {
            server.ensureStarted()
            assertTrue(starting.await(5, TimeUnit.SECONDS))
        } finally { unsubscribe() }
    }

    private fun publish(root: Path, port: Int, password: String) {
        val file = root.resolve("varro-opencode-server-$port.json.credentials")
        Files.writeString(file, Json.stringify(Json.obj("version" to 1, "port" to port, "password" to password,
            "owner" to "replacement", "createdAt" to System.currentTimeMillis())))
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }

    private fun fixture(version: String, action: (OpenCodeServer, Path, Int, ServerOwnership, CountDownLatch) -> Unit) {
        val root = temporary.newFolder().toPath()
        val endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val probed = CountDownLatch(1)
        endpoint.createContext("/") { exchange ->
            probed.countDown()
            val supported = exchange.requestURI.path == when (version) {
                "2.0.5" -> "/api/status"
                "2.0.26" -> "/api/info"
                else -> "/global/health"
            }
            val authorized = exchange.requestHeaders.getFirst("Authorization") == OpenCodeConnection.authorization("replacement-password")
            val status = when { !supported -> 404; !authorized -> 401; else -> 200 }
            val body = """{"healthy":true,"version":"$version","pid":12345}""".toByteArray()
            exchange.sendResponseHeaders(status, if (status == 200) body.size.toLong() else -1)
            if (status == 200) exchange.responseBody.use { it.write(body) } else exchange.close()
        }
        endpoint.start()
        val port = endpoint.address.port
        publish(root, port, "obsolete-password")
        val claim = ServerOwnership(port, root, root)
        assertTrue(claim.beginLaunch())
        val server = OpenCodeServer(VarroSettings().apply {
            serverPort = port
            serverAutoStart = true
            serverCommand = "/missing/fixture-opencode"
        }, root) { null }
        server.authentication = OpenCodeServerAuthentication(read = { fail("Published credentials must skip the vault"); null },
            prompt = { _, _ -> fail("Published credentials must skip the prompt"); null })
        try { action(server, root, port, claim, probed) } finally {
            server.dispose()
            claim.endLaunch()
            endpoint.stop(0)
            OpenCodeConnection.forget("http://127.0.0.1:$port")
        }
    }
}
