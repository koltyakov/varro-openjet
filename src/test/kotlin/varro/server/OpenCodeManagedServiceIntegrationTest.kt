package varro.server

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Released-binary lifecycle coverage, confined to fixture state/config/data roots and database. */
class OpenCodeManagedServiceIntegrationTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `managed private service survives Desktop registration and a second editor reload`() {
        val binary = System.getenv("VARRO_OPENCODE_TEST_BINARY")
        assumeTrue("Set VARRO_OPENCODE_TEST_BINARY to a released v2 CLI", !binary.isNullOrBlank())
        val root = temporary.newFolder().toPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val environment = System.getenv().filterKeys { it.equals("PATH", true) || it.equals("SystemRoot", true) } + mapOf(
            "HOME" to root.toString(), "USERPROFILE" to root.toString(), "LOCALAPPDATA" to root.resolve("local").toString(),
            "APPDATA" to root.resolve("appdata").toString(), "TMPDIR" to root.toString(), "TEMP" to root.toString(),
            "XDG_CONFIG_HOME" to root.resolve("config").toString(), "XDG_DATA_HOME" to root.resolve("data").toString(),
            "XDG_STATE_HOME" to root.resolve("global-state").toString(), "XDG_CACHE_HOME" to root.resolve("cache").toString(),
            "OPENCODE_DB" to root.resolve("fixture.db").toString(), "VARRO_TEST_STATE_ROOT" to root.toString(),
        )
        val cli = OpenCodeCli({ binary!! }, { workspace.toString() }, environment)
        assumeTrue("The managed service fixture requires v2", cli.readInstalledVersion()?.startsWith("2.") == true)
        val port = ServerSocket(0).use { it.localPort }
        val managed = OpenCodeProcess(cli, { port }, { workspace.toString() }, serverStateDirectory = root.resolve("servers"))
        val url = "http://127.0.0.1:$port"
        val transport = OpenCodeTransport({ url }, { workspace.toString() }, { ServerStatus.Stopped }, { false }, {}, {},
            managed::authorization, root.resolve("annotations"))
        val callbacks = object : ProcessLaunchCallbacks {
            override fun onStdout(text: String) {}
            override fun onStderr(text: String) {}
            override fun onExit(exitCode: Int) {}
        }
        var desktop: Process? = null
        var desktopReader: Thread? = null
        fun startManaged() {
            assertTrue(managed.beginLaunch())
            try {
                managed.launch(callbacks)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var ready = false
                while (!ready && managed.isRunning && System.nanoTime() < deadline) {
                    if (managed.refreshLaunchCredentials()) ready = managed.launchHealthMatches(transport.readHealthInfo(1000) { false })
                    if (!ready) Thread.sleep(50)
                }
                assertTrue(managed.output, ready)
                assertTrue(managed.confirmOwnership())
            } finally { managed.endLaunch() }
        }
        try {
            startManaged()
            val privateFile = root.resolve("servers/opencode-service/opencode/service.json")
            val privateRegistration = OpenCodeConnection.registration(privateFile)!!
            assertEquals(url, privateRegistration.url)
            assertFalse(managed.launchHealthMatches(HealthInfo(true, privateRegistration.version, privateRegistration.pid + 1)))
            assertFalse(managed.launchHealthMatches(HealthInfo(true, "${privateRegistration.version}-mismatch", privateRegistration.pid)))
            assertFalse(Files.exists(OpenCodeConnection.serviceFile(environment)))
            val lease = root.resolve("servers/varro-opencode-server-$port.json")
            val original = Files.readString(lease)
            val desktopPort = ServerSocket(0).use { it.localPort }
            desktop = ProcessBuilder(System.getenv("VARRO_OPENCODE_DESKTOP_TEST_BINARY") ?: binary!!,
                "serve", "--service", "--port", desktopPort.toString()).directory(workspace.toFile()).redirectErrorStream(true).apply {
                environment().clear(); environment().putAll(environment)
            }.start()
            val desktopProcess = desktop
            desktopReader = Thread { desktopProcess.inputStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) } }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var desktopRegistration: OpenCodeConnection.Registration? = null
            while (desktopRegistration == null && desktopProcess.isAlive && System.nanoTime() < deadline) {
                desktopRegistration = OpenCodeConnection.registration(environment)
                if (desktopRegistration == null) Thread.sleep(50)
            }
            assertNotNull("Desktop fixture did not register", desktopRegistration)
            assertNotEquals(privateRegistration.pid, desktopRegistration!!.pid)
            assertEquals("http://127.0.0.1:$desktopPort", desktopRegistration.url)
            val desktopOriginal = Files.readString(OpenCodeConnection.serviceFile(environment))
            assertTrue(transport.checkHealth())
            assertEquals(original, Files.readString(lease))

            val reloaded = OpenCodeProcess(cli, { port }, { workspace.toString() }, serverStateDirectory = root.resolve("servers"))
            assertTrue(reloaded.restoreConnection())
            assertFalse(reloaded.canProbeUnregisteredService)
            assertEquals(listOf(privateRegistration), reloaded.serviceRegistrations(allowGlobal = true))
            assertEquals(managed.authorization(), reloaded.authorization())
            reloaded.disconnect()

            val savedLease = root.resolve("saved-lease.json")
            Files.move(lease, savedLease)
            try {
                val markerReload = OpenCodeProcess(cli, { port }, { workspace.toString() }, serverStateDirectory = root.resolve("servers"))
                assertTrue(markerReload.restoreConnection())
                assertFalse(markerReload.canProbeUnregisteredService)
                assertEquals(listOf(privateRegistration), markerReload.serviceRegistrations(allowGlobal = true))
                assertEquals(managed.authorization(), markerReload.authorization())
                markerReload.disconnect()
            } finally { Files.move(savedLease, lease) }

            managed.stop()
            startManaged()
            assertTrue(desktopProcess.isAlive)
            assertEquals(desktopOriginal, Files.readString(OpenCodeConnection.serviceFile(environment)))
            assertTrue(transport.checkHealth())
        } finally {
            try { managed.stop() } finally {
                managed.endLaunch()
                desktop?.let { process ->
                    process.destroy()
                    if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                }
                desktopReader?.join(1000)
                transport.dispose()
                OpenCodeConnection.forget(url)
            }
        }
    }
}
