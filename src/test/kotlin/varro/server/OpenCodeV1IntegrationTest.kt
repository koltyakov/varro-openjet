package varro.server

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import varro.protocol.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Released V1 regressions for sharing retirement and configured reasoning variants. */
class OpenCodeV1IntegrationTest {
    @Test fun `released V1 retains cleanup and server-generated reasoning variants`() {
        val binary = System.getenv("VARRO_OPENCODE_V1_TEST_BINARY")?.takeIf { it.isNotBlank() }
        assumeTrue("Set VARRO_OPENCODE_V1_TEST_BINARY to an isolated released V1 executable", binary != null)
        val root = Files.createTempDirectory("varro-v1-compatibility-")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val environment = System.getenv().filterKeys { it.uppercase() in setOf("PATH", "SYSTEMROOT") }.toMutableMap()
        environment.putAll(mapOf("HOME" to root.toString(), "USERPROFILE" to root.toString(),
            "XDG_CONFIG_HOME" to root.resolve("config").toString(), "XDG_DATA_HOME" to root.resolve("data").toString(),
            "XDG_STATE_HOME" to root.resolve("state").toString(), "XDG_CACHE_HOME" to root.resolve("cache").toString(),
            "APPDATA" to root.resolve("appdata").toString(), "LOCALAPPDATA" to root.resolve("local").toString(),
            "OPENCODE_DB" to root.resolve("fixture.db").toString(), "OPENCODE_DISABLE_DEFAULT_PLUGINS" to "true",
            "OPENCODE_DISABLE_MODELS_FETCH" to "true", "OPENCODE_SERVER_PASSWORD" to "fixture-password"))
        Files.writeString(workspace.resolve("opencode.json"), Json.stringify(Json.obj("model" to "fixture/model", "provider" to Json.obj("fixture" to Json.obj(
            "npm" to "@ai-sdk/openai-compatible", "name" to "Fixture", "options" to Json.obj("baseURL" to "http://127.0.0.1:1/v1", "apiKey" to "fixture-only"),
            "models" to Json.obj("model" to Json.obj("name" to "Fixture", "reasoning" to true,
                "reasoning_options" to listOf(Json.obj("type" to "effort", "values" to listOf("low", "high"))),
                "limit" to Json.obj("context" to 32000, "output" to 1024))))))))
        val version = ProcessBuilder(binary!!, "--version").directory(root.toFile()).apply { environment().clear(); environment().putAll(environment) }.start()
        val installed = version.inputStream.bufferedReader().readText().trim()
        assertTrue(version.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, version.exitValue())
        assertEquals(System.getenv("VARRO_OPENCODE_V1_TEST_VERSION") ?: "1.19.3", installed)
        val port = ServerSocket(0).use { it.localPort }
        val log = root.resolve("server.log")
        val process = ProcessBuilder(binary, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
            .directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment().clear(); environment().putAll(environment)
            }.start()
        val transport = OpenCodeTransport({ "http://127.0.0.1:$port" }, { workspace.toString() },
            { ServerStatus.Running("http://127.0.0.1:$port", EventStreamState.HEALTHY) }, { false }, {}, {},
            { OpenCodeConnection.authorization("fixture-password") }, root.resolve("annotations"))
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (!transport.checkHealth() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue(Files.readString(log), transport.checkHealth()); assertEquals(1, transport.apiVersion)
            assertEquals(installed, transport.readHealthInfo().version)
            val model = transport.request("GET", "/provider").data.asObjectOrNull().arr("all")!!.mapNotNull { it.asObjectOrNull() }
                .single { it.str("id") == "fixture" }.obj("models").obj("model")
            assertEquals(setOf("low", "high"), model.obj("variants")!!.keySet())
            val session = transport.request("POST", "/session", Json.obj("title" to "Isolated V1 compatibility")).data.asObjectOrNull()!!
            val id = session.str("id")!!
            try {
                try { transport.request("POST", "/session/$id/share", Json.obj()); fail("New shares must be rejected") }
                catch (expected: OpenCodeRequestException) { assertTrue(expected.message!!.startsWith("500 ")) }
                val cleaned = transport.request("DELETE", "/session/$id/share").data.asObjectOrNull()
                assertFalse(cleaned.hasNonNull("share"))
                assertEquals(session.obj("time").long("created"), cleaned.obj("time").long("created"))
                // Native V1 bumps updated on cleanup; the webview preserves its displayed activity time.
                assertTrue(cleaned.obj("time").long("updated")!! >= session.obj("time").long("updated")!!)
            } finally { transport.request("DELETE", "/session/$id") }
        } finally {
            transport.dispose(); process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
