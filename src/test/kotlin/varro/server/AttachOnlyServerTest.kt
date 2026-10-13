package varro.server

import com.sun.net.httpserver.HttpServer
import com.intellij.credentialStore.Credentials
import org.junit.Assert.*
import org.junit.Test
import varro.settings.VarroSettings
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import varro.protocol.Json

class AttachOnlyServerTest {
    @Test
    fun `external servers attach without a local CLI and reject forced restart`() {
        for (version in listOf("1.18.31", "2.0.10")) withServer(version) { server ->
            assertTrue(server.currentStatus().toString(), server.currentStatus() is ServerStatus.Running)
            assertTrue(server.isAttachOnly())
            assertFalse(server.isManaged())
            assertEquals(version, server.version())
            val diagnostics = server.readVersionInfo()!!
            assertEquals(version, diagnostics.serverVersion)
            assertNull(diagnostics.cliVersion)
            assertTrue(diagnostics.attachOnly)
            assertEquals(server.url(), diagnostics.url)
            assertEquals(12345L, diagnostics.processId)
            assertSame(diagnostics, server.readVersionInfo())
            assertEquals(version.substringBefore('.').toInt(), (server.currentStatus() as ServerStatus.Running).apiVersion)
            assertEquals(version.substringBefore('.').toInt(), server.currentStatus().toJson().get("apiVersion").asInt)
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

    @Test
    fun `shared Varro credentials reconnect without a prompt or lifecycle authority`() {
        for (version in listOf("1.18.34", "2.0.24")) {
            val root = Files.createTempDirectory("openjet-credential-attachment-")
            val endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            endpoint.createContext("/") { exchange ->
                val authorized = exchange.requestHeaders.getFirst("Authorization") == OpenCodeConnection.authorization("secret", "varro-user")
                val supported = exchange.requestURI.path == if (version.startsWith("1.")) "/global/health" else "/api/info"
                val status = when { !supported -> 404; !authorized -> 401; else -> 200 }
                val bytes = """{"healthy":true,"version":"$version","pid":12345}""".toByteArray()
                exchange.sendResponseHeaders(status, if (status == 200) bytes.size.toLong() else -1)
                if (status == 200) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
            endpoint.start()
            val companion = root.resolve("varro-opencode-server-${endpoint.address.port}.json.credentials")
            val original = Json.stringify(Json.obj("version" to 1, "port" to endpoint.address.port, "owner" to "vscode-launch",
                "createdAt" to 1, "password" to "secret", "username" to "varro-user"))
            Files.writeString(companion, original)
            if (companion.fileSystem.supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(companion, PosixFilePermissions.fromString("rw-------"))
            val server = OpenCodeServer(VarroSettings().apply {
                serverAutoStart = true
                serverPort = endpoint.address.port
                serverCommand = "/missing/opencode"
            }, serverStateDirectory = root) { null }
            server.authentication = OpenCodeServerAuthentication(read = { fail("Private shared credentials must skip the vault"); null },
                prompt = { _, _ -> fail("Private shared credentials must skip the prompt"); null })
            try {
                val ready = CountDownLatch(1)
                server.onStatus { if (it is ServerStatus.Running || it is ServerStatus.Error) ready.countDown() }
                server.ensureStarted()
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                assertTrue(server.currentStatus().toString(), server.currentStatus() is ServerStatus.Running)
                assertTrue(server.isAttachOnly())
                assertFalse(server.isManaged())
                assertEquals(OpenCodeServer.RestartOutcome.NOT_MANAGED, server.restart(force = true))
                assertEquals(original, Files.readString(companion))
                assertFalse(Files.exists(Path.of(companion.toString().removeSuffix(".credentials"))))
            } finally {
                server.dispose()
                endpoint.stop(0)
                OpenCodeConnection.forget("http://127.0.0.1:${endpoint.address.port}")
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `PID diagnostics follow the HTTP server rather than the local forwarding listener`() {
        val payload = AtomicReference("""{"healthy":true,"version":"2.0.26","pid":79285}""")
        withServer("2.0.26", healthPayload = payload::get) { server ->
            val first = server.readVersionInfo()!!
            assertEquals(79285L, first.processId)
            assertNull(first.startedAt)
            assertTrue(first.attachOnly)
            assertFalse(server.isManaged())
            payload.set("""{"healthy":true,"version":"2.0.26","pid":5678}""")
            assertEquals(5678L, server.readVersionInfo(refresh = true)!!.processId)
            payload.set("{}")
            assertNull(server.readVersionInfo(refresh = true)!!.processId)
            assertFalse(server.isManaged())
            assertEquals(OpenCodeServer.RestartOutcome.NOT_MANAGED, server.restart(force = true))
        }
    }

    @Test fun `unknown and invalid V1 server PIDs stay unknown`() {
        val payload = AtomicReference("""{"healthy":true,"version":"1.19.0","pid":12345}""")
        withServer("1.19.0", healthPayload = payload::get) { server ->
            for (pid in listOf("null", "0", "-1", "1.5", "9007199254740992", "\"12345\"", "{}")) {
                payload.set("""{"healthy":true,"version":"1.19.0","pid":$pid}""")
                assertNull(pid, server.readVersionInfo(refresh = true)!!.processId)
            }
            payload.set("""{"healthy":true,"version":"1.19.0"}""")
            assertNull(server.readVersionInfo(refresh = true)!!.processId)
        }
    }

    @Test fun `a stopped connection discards in-flight PID diagnostics`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pause = AtomicBoolean(false)
        withServer("2.0.26", healthPayload = {
            if (pause.get()) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            """{"healthy":true,"version":"2.0.26","pid":12345}"""
        }) { server ->
            pause.set(true)
            val diagnostics = CompletableFuture.supplyAsync { server.readVersionInfo(refresh = true) }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                server.dispose()
                release.countDown()
                assertNull(diagnostics.get(3, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }

    @Test fun `invalid V2 health PIDs cannot become connected process diagnostics`() {
        val payload = AtomicReference("""{"version":"2.0.26","pid":12345}""")
        withServer("2.0.26", healthPayload = payload::get) { server ->
            for (pid in listOf("null", "0", "-1", "1.5", "9007199254740992", "\"12345\"")) {
                payload.set("""{"version":"2.0.26","pid":$pid}""")
                assertNull(pid, server.readVersionInfo(refresh = true)!!.processId)
            }
        }
    }

    private fun withServer(version: String, authentication: OpenCodeServerAuthentication? = null,
        healthPayload: () -> String = { """{"healthy":true,"version":"$version","pid":12345}""" },
        check: (OpenCodeServer) -> Unit,
    ) {
        val endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint.createContext("/") { exchange ->
            if (authentication != null && exchange.requestHeaders.getFirst("Authorization") != OpenCodeConnection.authorization("password", "user")) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val bytes = healthPayload().toByteArray()
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
            assertNull(server.readVersionInfo())
            endpoint.stop(0)
        }
    }
}
