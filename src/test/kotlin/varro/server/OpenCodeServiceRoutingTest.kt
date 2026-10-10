package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.Json
import java.nio.file.Files
import java.nio.file.Path

class OpenCodeServiceRoutingTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private fun registration(file: Path, port: Int, password: String? = "fixture", pid: Long = ProcessHandle.current().pid()) {
        Files.createDirectories(file.parent)
        Files.writeString(file, Json.stringify(Json.obj("url" to "http://127.0.0.1:$port", "pid" to pid,
            "password" to password, "version" to "2.0.26")))
    }

    @Test fun `v2 launch changes only the child state home while v1 keeps its environment`() {
        val root = temporary.newFolder().toPath()
        val environment = mapOf("XDG_STATE_HOME" to root.resolve("global").toString(), "XDG_CONFIG_HOME" to "/fixture/config",
            "XDG_DATA_HOME" to "/fixture/data", "OPENCODE_DB" to "/fixture/test.db")
        val routing = OpenCodeServiceRouting(environment, root.resolve("servers"))
        assertEquals(listOf("serve", "--port", "4096"), routing.launchArguments("1.18.34", 4096))
        assertEquals(environment, routing.launchEnvironment("1.18.34", environment))
        assertFalse(routing.privateSelected)
        assertEquals(listOf("serve", "--service", "--port", "4096"), routing.launchArguments("2.0.26", 4096))
        val child = routing.launchEnvironment("2.0.26", environment)
        assertEquals(root.resolve("servers/opencode-service").toString(), child["XDG_STATE_HOME"])
        assertEquals(environment.filterKeys { it != "XDG_STATE_HOME" }, child.filterKeys { it != "XDG_STATE_HOME" })
        assertEquals(root.resolve("global").toString(), environment["XDG_STATE_HOME"])
        assertTrue(routing.privateSelected)
    }

    @Test fun `verified private discovery never falls back to Desktop after registration disappears`() {
        val root = temporary.newFolder().toPath()
        val global = root.resolve("global/opencode/service.json")
        val private = root.resolve("servers/opencode-service/opencode/service.json")
        registration(global, 43124, "desktop")
        registration(private, 43123, "varro")
        val original = Files.readString(global)
        val routing = OpenCodeServiceRouting(mapOf("XDG_STATE_HOME" to root.resolve("global").toString()), root.resolve("servers"))
        assertEquals(listOf(43123, 43124), routing.registrations().map { java.net.URI(it.url).port })
        val verified = routing.registrations().first()
        Files.delete(private)
        routing.rememberVerified(verified)
        assertTrue(routing.registrations().isEmpty())
        assertEquals(original, Files.readString(global))
    }

    @Test fun `new private launch waits for its own matching credentials and ignores global registration`() {
        val root = temporary.newFolder().toPath()
        val global = root.resolve("global/opencode/service.json")
        val private = root.resolve("servers/opencode-service/opencode/service.json")
        registration(global, 4096, "desktop")
        val environment = mapOf("XDG_STATE_HOME" to root.resolve("global").toString())
        val routing = OpenCodeServiceRouting(environment, root.resolve("servers"))
        routing.launchEnvironment("2.0.26", environment)
        assertTrue(routing.registrations("http://127.0.0.1:4096", privateOnly = true).isEmpty())
        registration(private, 4097, "wrong-port")
        assertTrue(routing.registrations("http://127.0.0.1:4096", privateOnly = true).isEmpty())
        registration(private, 4096, "persisted")
        assertEquals("persisted", routing.registrations("http://127.0.0.1:4096", privateOnly = true).single().password)
    }

    @Test fun `verified lease or marker restores private selection without requiring a service password`() {
        val root = temporary.newFolder().toPath()
        val private = root.resolve("servers/opencode-service/opencode/service.json")
        val environment = mapOf("XDG_STATE_HOME" to root.resolve("global").toString())
        registration(root.resolve("global/opencode/service.json"), 43124, "desktop")
        registration(private, 43123, password = null)
        for (pid in listOf(null, ProcessHandle.current().pid() + 1, ProcessHandle.current().pid())) {
            val routing = OpenCodeServiceRouting(environment, root.resolve("servers"))
            routing.rememberVerified(pid, 43123)
            assertEquals(pid == ProcessHandle.current().pid(), routing.privateSelected)
        }
        val mismatched = OpenCodeServiceRouting(environment, root.resolve("servers"))
        mismatched.rememberVerified(ProcessHandle.current().pid(), 43125)
        assertFalse(mismatched.privateSelected)
        val restored = OpenCodeServiceRouting(environment, root.resolve("servers"))
        restored.rememberVerified(ProcessHandle.current().pid(), 43123)
        Files.delete(private)
        assertTrue(restored.registrations().isEmpty())
    }

    @Test fun `legacy global services remain discoverable before private selection`() {
        val root = temporary.newFolder().toPath()
        registration(root.resolve("global/opencode/service.json"), 43124, "legacy")
        val routing = OpenCodeServiceRouting(mapOf("XDG_STATE_HOME" to root.resolve("global").toString()), root.resolve("servers"))
        assertEquals("legacy", routing.registrations().single().password)
        assertFalse(routing.privateSelected)
        assertTrue(routing.registrations(privateOnly = true).isEmpty())
    }

    @Test fun `confirmed credentials do not inspect service files on each request`() {
        val url = "http://127.0.0.1:43126"
        OpenCodeConnection.register(url, "verified")
        try {
            assertEquals(OpenCodeConnection.authorization("verified"), OpenCodeConnection.credentials(url, emptyMap()) {
                error("Confirmed requests must not reread registrations")
            })
        } finally { OpenCodeConnection.forget(url) }
    }
}
