package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path

class ServerOwnershipTest {
    private class Identity : ProcessIdentity() {
        var serverBirth = "darwin:original-start"
        var fail = false
        override fun listeners(port: Int): Set<Long> {
            check(!fail) { "inspection failed" }
            return setOf(123)
        }
        override fun executable(pid: Long) = "/usr/bin/opencode"
        override fun birth(pid: Long) = if (pid == 123L) serverBirth else "darwin:host-start"
        override fun alive(pid: Long) = pid != 999L
    }

    private fun fixture(action: (Path, Path, Identity) -> Unit) {
        val root = Files.createTempDirectory("ownership-test-")
        try { action(Files.createDirectory(root.resolve("shared")), Files.createDirectory(root.resolve("legacy")), Identity()) }
        finally { root.toFile().deleteRecursively() }
    }

    private fun marker() = Json.obj("pid" to 123, "port" to 4096, "executable" to "/usr/bin/opencode",
        "birthIdentity" to "darwin:original-start", "owner" to "varro-server-nonce", "createdAt" to System.currentTimeMillis())

    @Test fun `legacy marker recovers missing lease and rejects PID reuse`() = fixture { shared, legacy, identity ->
        val path = legacy.resolve("varro-opencode-server-4096.json")
        Files.writeString(Path.of("$path.managed"), Json.stringify(marker()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertEquals(path, manager.path)
        assertTrue(manager.refresh(4096))
        assertEquals("varro-server-nonce", ServerOwnership.record(path).str("owner"))
        identity.serverBirth = "darwin:reused-pid"
        assertFalse(manager.refresh(4096))
    }

    @Test fun `products share ownership and handoff without trusting cached host`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        Files.writeString(Path.of("$path.managed"), Json.stringify(marker()))
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
        Files.writeString(path, Json.stringify(marker().apply {
            addProperty("version", 1); addProperty("host", "vscode-host"); addProperty("hostPid", 999)
            addProperty("hostBirthIdentity", "darwin:dead-host"); addProperty("state", "active")
            addProperty("password", "secret"); addProperty("configPath", "/foreign/opencode.json")
        }))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.refresh(4096))
        assertEquals(4096 to "secret", manager.connection())
        assertEquals("/foreign/opencode.json", ServerOwnership.record(path).str("configPath"))
    }

    @Test fun `competing products elect one owner`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        Files.writeString(Path.of("$path.managed"), Json.stringify(marker()))
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
        Files.writeString(Path.of("$path.managed"), Json.stringify(marker()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.refresh(4096))
        val before = Files.readString(path)
        identity.fail = true
        assertThrows(IllegalStateException::class.java) { manager.refresh(4096) }
        assertEquals(before, Files.readString(path))
        Files.writeString(path, "{broken")
        identity.fail = false
        assertFalse(manager.refresh(4096))
        assertEquals("{broken", Files.readString(path))
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
