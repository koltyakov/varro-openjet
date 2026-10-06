package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ServerOwnershipTest {
    private class Identity : ProcessIdentity() {
        var serverBirth = "darwin:original-start"
        var fail = false
        var listening = setOf(123L)
        var dead = setOf(999L)
        override fun listeners(port: Int): Set<Long> {
            check(!fail) { "inspection failed" }
            return listening
        }
        override fun executable(pid: Long) = "/usr/bin/opencode"
        override fun birth(pid: Long) = if (pid == 123L) serverBirth else "darwin:host-start"
        override fun alive(pid: Long) = pid !in dead
    }

    private fun fixture(action: (Path, Path, Identity) -> Unit) {
        val root = Files.createTempDirectory("ownership-test-")
        try {
            val shared = Files.createDirectory(root.resolve("shared"))
            if (shared.fileSystem.supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwx------"))
            action(shared, Files.createDirectory(root.resolve("legacy")), Identity())
        }
        finally { root.toFile().deleteRecursively() }
    }

    private fun marker() = Json.obj("pid" to 123, "port" to 4096, "executable" to "/usr/bin/opencode",
        "birthIdentity" to "darwin:original-start", "owner" to "varro-server-nonce", "createdAt" to System.currentTimeMillis())

    private fun privateFile(path: Path, text: String): Path = Files.writeString(path, text).also {
        if (it.fileSystem.supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------"))
    }

    @Test fun `legacy marker recovers missing lease and rejects PID reuse`() = fixture { shared, legacy, identity ->
        val path = legacy.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertEquals(path, manager.path)
        assertTrue(manager.refresh(4096))
        assertEquals("varro-server-nonce", ServerOwnership.record(path).str("owner"))
        identity.serverBirth = "darwin:reused-pid"
        assertFalse(manager.refresh(4096))
    }

    @Test fun `products share ownership and handoff without trusting cached host`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val first = ServerOwnership(4096, shared, legacy, identity)
        val second = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(first.refresh(4096))
        assertFalse(second.refresh(4096))
        first.relinquish()
        assertTrue(second.refresh(4096))
        assertFalse(first.stop(1))
        first.relinquish()
        assertEquals("active", ServerOwnership.record(path).str("state"))
        assertTrue(first.refresh(4096, takeover = true))
        assertFalse(second.refresh(4096))
    }

    @Test fun `crashed host recovery preserves password and foreign config`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(path, Json.stringify(marker().apply {
            addProperty("version", 1); addProperty("host", "vscode-host"); addProperty("hostPid", 999)
            addProperty("hostBirthIdentity", "darwin:dead-host"); addProperty("state", "active")
            addProperty("password", "secret"); addProperty("configPath", "/foreign/opencode.json")
        }))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.refresh(4096))
        assertEquals(ServerOwnership.Connection(4096, "secret"), manager.connection())
        assertEquals("/foreign/opencode.json", ServerOwnership.record(path).str("configPath"))
    }

    @Test fun `competing products elect one owner`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val managers = List(8) { ServerOwnership(4096, shared, legacy, identity) }
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(managers.size)
        try {
            val results = managers.map { manager -> pool.submit<Boolean> { start.await(); manager.refresh(4096) } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertNotNull(ServerOwnership.record(path))
            assertFalse(Files.exists(Path.of("$path.claim")))
        } finally { pool.shutdownNow() }
    }

    @Test fun `inspection failures and malformed records preserve evidence`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.refresh(4096))
        val before = Files.readString(path)
        identity.fail = true
        assertThrows(IllegalStateException::class.java) { manager.refresh(4096) }
        assertEquals(before, Files.readString(path))
        privateFile(path, "{broken")
        identity.fail = false
        assertFalse(manager.refresh(4096))
        assertEquals("{broken", Files.readString(path))
    }

    private fun lease() = marker().apply {
        addProperty("version", 1); addProperty("host", "vscode-host"); addProperty("hostPid", 999)
        addProperty("hostBirthIdentity", "darwin:dead-host"); addProperty("state", "active")
        addProperty("password", "secret"); addProperty("username", "varro-user")
    }

    @Test fun `discovery retains the VS Code coordination key and username`() = fixture { shared, legacy, identity ->
        val original = shared.resolve("varro-opencode-server-5000.json")
        privateFile(original, Json.stringify(lease()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection(4096, "secret"))
        assertEquals(original, manager.path)
        assertTrue(manager.refresh(4096))
        assertFalse(Files.exists(shared.resolve("varro-opencode-server-4096.json")))
    }

    @Test fun `replacement credentials allow attachment without ownership or record changes`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        val original = Json.stringify(lease())
        privateFile(path, original)
        identity.serverBirth = "darwin:replacement"
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { it.password == "secret" })
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        assertTrue(manager.credentialOnly)
        assertNull(manager.registeredPid)
        assertFalse(manager.refresh(4096, takeover = true))
        assertFalse(manager.stop(1))
        assertThrows(IllegalStateException::class.java) { manager.verifyConnection(5000) }
        manager.relinquish()
        assertEquals(original, Files.readString(path))
        privateFile(path, Json.stringify(lease().apply { addProperty("password", "changed") }))
        assertThrows(IllegalStateException::class.java) { manager.verifyConnection(4096) }
    }

    @Test fun `failed inspection can attach using a private credential companion`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json.credentials")
        val value = Json.obj("version" to 1, "port" to 4096, "owner" to "launch", "createdAt" to 1,
            "password" to "secret", "username" to "varro-user")
        privateFile(path, Json.stringify(value))
        identity.fail = true
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        assertTrue(manager.credentialOnly)
        assertFalse(Files.exists(manager.path))
    }

    @Test fun `changed discovery port requires exact saved credentials`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(path, Json.stringify(lease()))
        identity.serverBirth = "darwin:replacement"
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { it.port == 5001 && it.password == "secret" })
        assertNull(manager.connection(5001, "unrelated"))
        assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), manager.connection(5001, "secret"))
        assertTrue(manager.credentialOnly)
        assertFalse(manager.refresh(5001, takeover = true))
        assertEquals(4096, ServerOwnership.record(path)?.get("port")?.asInt)
    }

    @Test fun `ambiguous listeners and nonprivate credentials cannot grant ownership`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(path, Json.stringify(lease()))
        identity.listening = setOf(123, 456)
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { false })
        assertNull(manager.connection())
        assertFalse(manager.refresh(4096, takeover = true))
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            assertNull(ServerOwnership.record(path))
            assertNull(manager.connection())
        }
    }

    @Test fun `the family launch claim stays held and uncertain processes block replacement`() = fixture { shared, legacy, identity ->
        val first = ServerOwnership(4096, shared, legacy, identity)
        val second = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(first.beginLaunch())
        assertFalse(second.beginLaunch())
        first.assertLaunchAllowed()
        privateFile(first.path, Json.stringify(lease()))
        assertThrows(IllegalStateException::class.java) { first.assertLaunchAllowed() }
        first.endLaunch()
        assertTrue(second.beginLaunch())
        identity.dead += 123L
        second.assertLaunchAllowed()
        second.endLaunch()
        assertFalse(Files.exists(Path.of("${first.path}.claim")))
    }

    @Test fun `corrupt records are retained and cannot authorize a launch`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(manager.path, "{broken")
        assertTrue(manager.beginLaunch())
        try { assertThrows(IllegalStateException::class.java) { manager.assertLaunchAllowed() } }
        finally { manager.endLaunch() }
        assertEquals("{broken", Files.readString(manager.path))
    }

    @Test fun `credential-only launch persists a private companion shared with VS Code`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
        assertTrue(manager.beginLaunch())
        try { assertTrue(manager.retainConnection(5001, "secret", "varro-user")) }
        finally { manager.endLaunch() }
        assertFalse(Files.exists(manager.path))
        val companion = Path.of("${manager.path}.credentials")
        assertEquals("varro-user", ServerOwnership.connectionRecord(companion).str("username"))
        val reloaded = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
        assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), reloaded.connection())
        assertTrue(reloaded.credentialOnly)
        assertFalse(reloaded.refresh(5001, takeover = true))
    }

    @Test fun `confirmed launch publishes the shared wire format and keeps its claim`() = fixture { shared, legacy, identity ->
        val pid = ProcessHandle.current().pid()
        identity.listening = setOf(pid)
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.beginLaunch())
        try {
            assertTrue(manager.register(5001, pid, "secret", "varro-user"))
            val lease = ServerOwnership.record(manager.path)!!
            assertEquals(1, lease.get("version").asInt)
            assertEquals(5001, lease.get("port").asInt)
            assertEquals("varro-user", lease.str("username"))
            assertEquals(lease.str("owner"), ServerOwnership.connectionRecord(Path.of("${manager.path}.credentials")).str("owner"))
            assertFalse(ServerOwnership.record(Path.of("${manager.path}.managed"), false)!!.has("password"))
            val follower = ServerOwnership(4096, shared, legacy, identity)
            assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), follower.connection())
            assertFalse(follower.refresh(5001))
            assertTrue(Files.exists(Path.of("${manager.path}.claim")))
        } finally { manager.endLaunch() }
        manager.relinquish()
        assertEquals("relinquished", ServerOwnership.record(manager.path).str("state"))
    }

    @Test fun `marker recovery preserves endpoint credentials without claiming a foreign config`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(Path.of("${manager.path}.managed"), Json.stringify(marker().apply { addProperty("configPath", "/vscode/injected.json") }))
        assertNotNull(manager.connection())
        assertTrue(manager.refresh(4096, password = "secret", username = "varro-user"))
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        assertEquals("/vscode/injected.json", ServerOwnership.record(manager.path).str("configPath"))
    }

    @Test fun `followers detect replacement but host handoff and inconclusive inspection are not replacement`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(manager.path, Json.stringify(lease()))
        assertNotNull(manager.connection())
        privateFile(manager.path, Json.stringify(lease().apply { addProperty("host", "openjet-host") }))
        assertFalse(manager.registrationChanged())
        identity.fail = true
        assertThrows(IllegalStateException::class.java) { manager.registrationChanged() }
        identity.fail = false
        assertFalse(manager.registrationChanged())
        identity.serverBirth = "darwin:replacement"
        assertTrue(manager.registrationChanged())
    }

    @Test fun `credential companions reject coerced types and symlinks`() = fixture { shared, _, _ ->
        val companion = shared.resolve("credentials")
        val valid = Json.obj("version" to 1, "port" to 4096, "owner" to "launch", "createdAt" to 1, "password" to "secret")
        for (field in listOf("version", "port", "password")) {
            val invalid = valid.deepCopy().apply { if (field == "password") addProperty(field, 123) else addProperty(field, get(field).asString) }
            privateFile(companion, Json.stringify(invalid))
            assertNull(ServerOwnership.connectionRecord(companion))
        }
        privateFile(companion, Json.stringify(valid))
        assertNotNull(ServerOwnership.connectionRecord(companion))
        if (companion.fileSystem.supportedFileAttributeViews().contains("posix")) {
            val link = Files.createSymbolicLink(shared.resolve("link"), companion)
            assertNull(ServerOwnership.connectionRecord(link))
        }
    }

    @Test fun `macOS resolves launch symlink when lsof executable was replaced`() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        val root = Files.createTempDirectory("macos-ownership-").toRealPath()
        try {
            val binary = Files.writeString(root.resolve("opencode"), "binary")
            val launch = Files.createSymbolicLink(root.resolve("opencode2"), binary)
            val identity = object : ProcessIdentity() {
                override fun command(vararg arguments: String) = if (arguments[0] == "lsof") "p123\nftxt\nn/nonexistent-opencode" else launch.toString()
            }
            assertEquals(binary.toString(), identity.executable(123))
        } finally { root.toFile().deleteRecursively() }
    }
}
