package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpDefaultsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("repo").toPath() }
    private val global by lazy { temporary.newFolder("global").toPath() }
    private val server = Json.obj("type" to "local", "command" to listOf("docs"), "environment" to Json.obj("KEY" to "{env:DOCS_KEY}"))
    private fun write(path: Path, text: String): Path { Files.createDirectories(path.parent); Files.writeString(path, text); return path }
    private fun config(value: com.google.gson.JsonObject, native: Boolean = true) =
        Json.obj("mcp" to if (native) Json.obj("servers" to value) else value).toString()
    private fun update(scope: String, enabled: Boolean?) = Json.obj("name" to "docs", "scope" to scope, "enabled" to enabled)
    private fun service(native: Boolean = true) = McpDefaults(root, global, native)

    @Test fun `defaults come from files with v2 replacement and v1 shallow inheritance`() {
        write(global.resolve("opencode.json"), config(Json.obj("docs" to server.deepCopy().apply { addProperty("disabled", true) })))
        write(root.resolve("opencode.json"), config(Json.obj("docs" to server)))
        assertEquals(Json.obj("docs" to false), service().read().obj("global"))
        assertEquals(Json.obj("docs" to true), service().read().obj("effective"))
        write(global.resolve("opencode.json"), config(Json.obj("docs" to server.deepCopy().apply { addProperty("enabled", false) }), false))
        write(root.resolve("opencode.json"), config(Json.obj("docs" to Json.obj("timeout" to 1000)), false))
        assertEquals(Json.obj("docs" to false), service(false).read().obj("effective"))
    }

    @Test fun `project opt in copies full native server and clearing restores inheritance`() {
        val file = write(global.resolve("opencode.jsonc"), config(Json.obj("docs" to server.deepCopy().apply { addProperty("disabled", true) })))
        val original = Files.readString(file)
        val service = service()
        assertEquals(Json.obj("docs" to true), service.update(update("project", true)).obj("effective"))
        val project = root.resolve("opencode.json")
        val copied = Json.parse(Files.readString(project)).asJsonObject.obj("mcp").obj("servers").obj("docs")!!
        assertEquals(server.get("command"), copied.get("command"))
        assertEquals(server.get("environment"), copied.get("environment"))
        assertEquals(false, copied.bool("disabled"))
        assertFalse(copied.has("enabled"))
        assertEquals(original, Files.readString(file))
        assertEquals(Json.obj("docs" to false), service.update(update("project", null)).obj("effective"))
        assertFalse(Files.readString(project).contains("docs"))
        assertEquals(Json.obj(), service.read().obj("project"))
    }

    @Test fun `choosing inherited value avoids a file and removes redundant copies`() {
        write(global.resolve("opencode.json"), config(Json.obj("docs" to server)))
        service().update(update("project", true))
        assertFalse(Files.exists(root.resolve("opencode.json")))
        write(root.resolve("opencode.jsonc"), "// keep me\n" + config(Json.obj("docs" to server.deepCopy().apply { addProperty("disabled", true) }, "other" to server)))
        service().update(update("project", true))
        val raw = Files.readString(root.resolve("opencode.jsonc"))
        assertTrue(raw.contains("// keep me"))
        val servers = Json.parse(ProjectPermissionConfig.stripJsonComments(raw)).asJsonObject.obj("mcp").obj("servers")!!
        assertFalse(servers.has("docs"))
        assertNotNull(servers.obj("other"))
        assertEquals(Json.obj("docs" to false, "other" to true), service().update(update("global", false)).obj("effective"))
    }

    @Test fun `native connection comments survive flag changes and conflicting legacy flags are removed`() {
        val file = write(global.resolve("opencode.jsonc"), """{ "mcp": { "servers": { "docs": { /* keep connection */ "type": "remote", "url": "https://docs.example/mcp", "enabled": false } } } }""")
        var refreshes = 0
        val service = McpDefaults(root, global, true, refresh = { assertTrue(it); refreshes++ })
        service.update(update("global", true))
        val raw = Files.readString(file)
        assertTrue(raw.contains("/* keep connection */"))
        assertFalse(raw.contains("enabled"))
        assertEquals(false, Json.parse(ProjectPermissionConfig.stripJsonComments(raw)).asJsonObject.obj("mcp").obj("servers").obj("docs").bool("disabled"))
        assertEquals(1, refreshes)
    }

    @Test fun `clearing never deletes a project connection or a second project override`() {
        write(global.resolve("opencode.json"), config(Json.obj("docs" to server)))
        val file = write(root.resolve("opencode.json"), config(Json.obj("docs" to Json.obj("type" to "local", "command" to listOf("own")))))
        val original = Files.readString(file)
        assertThrows(IllegalArgumentException::class.java) { service().update(update("project", null)) }
        assertEquals(original, Files.readString(file))
        write(file, config(Json.obj("docs" to server)))
        write(root.resolve("opencode.jsonc"), config(Json.obj("docs" to server)))
        assertThrows(IllegalArgumentException::class.java) { service().update(update("project", null)) }
    }

    @Test fun `project only servers cannot be cleared or promoted globally`() {
        write(root.resolve("opencode.json"), config(Json.obj("docs" to server)))
        assertThrows(IllegalArgumentException::class.java) { service().update(update("project", null)) }
        assertThrows(IllegalStateException::class.java) { service().update(update("global", false)) }
        assertFalse(Files.exists(global.resolve("opencode.json")))
    }

    @Test fun `v1 partial project override can be removed and flags stay legacy`() {
        val file = write(global.resolve("opencode.json"), config(Json.obj("docs" to server), false))
        val project = write(root.resolve("opencode.json"), config(Json.obj("docs" to Json.obj("enabled" to false)), false))
        service(false).update(update("project", null))
        assertFalse(Files.readString(project).contains("docs"))
        service(false).update(update("global", false))
        assertEquals(false, Json.parse(Files.readString(file)).asJsonObject.obj("mcp").obj("docs").bool("enabled"))
        assertFalse(Files.readString(file).contains("disabled"))
    }

    @Test fun `ancestor nested configs win over direct configs and select local nested target`() {
        write(global.resolve("opencode.json"), config(Json.obj("docs" to server)))
        write(root.resolve(".opencode/opencode.jsonc"), config(Json.obj("docs" to server.deepCopy().apply { addProperty("disabled", true) })))
        val child = Files.createDirectories(root.resolve("child"))
        write(child.resolve("opencode.json"), config(Json.obj("docs" to server)))
        val service = McpDefaults(child, global, true)
        assertEquals(Json.obj("docs" to false), service.read().obj("effective"))
        service.update(update("project", true))
        assertTrue(Files.exists(child.resolve(".opencode/opencode.json")))
        assertEquals(Json.obj("docs" to true), service.read().obj("effective"))
    }

    @Test fun `invalid updates unsaved buffers and concurrent disk edits do not overwrite config`() {
        val file = write(global.resolve("opencode.json"), config(Json.obj("docs" to server)))
        val original = Files.readString(file)
        for (body in listOf(update("global", null), Json.obj("name" to "docs", "scope" to "project"),
            Json.obj("name" to "docs", "scope" to "project", "enabled" to "false"), update("other", true))) {
            assertThrows(IllegalArgumentException::class.java) { service().update(body) }
        }
        assertThrows(IllegalStateException::class.java) { McpDefaults(root, global, true, beforeWrite = { error("Unsaved") }).update(update("global", false)) }
        assertEquals(original, Files.readString(file))
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            McpDefaults(root, global, true, beforeWrite = { if (++checks == 2) Files.writeString(it, original + "\n// external") }).update(update("global", false))
        }
        assertEquals(original + "\n// external", Files.readString(file))
    }

    @Test fun `concurrent local updates serialize and attached reads expose effective values only`() {
        val file = write(global.resolve("opencode.json"), config(Json.obj("docs" to server, "other" to server)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            listOf("docs", "other").map { name -> pool.submit { service().update(Json.obj("name" to name, "scope" to "global", "enabled" to false)) } }
                .forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(Json.obj("docs" to false, "other" to false), service().read().obj("global"))
        val attached = service().attached(Json.parse(Files.readString(file)).asJsonObject)
        assertEquals(Json.obj(), attached.obj("global"))
        assertEquals(Json.obj(), attached.obj("project"))
        assertEquals(false, attached.bool("editable"))
        assertEquals(Json.obj("docs" to false, "other" to false), attached.obj("effective"))
        assertTrue(ApiRoutes.isAllowed("GET", ApiRoutes.Endpoints.MCP_DEFAULTS))
        assertTrue(ApiRoutes.isAllowed("POST", ApiRoutes.Endpoints.MCP_DEFAULTS))
        assertFalse(ApiRoutes.isAllowed("DELETE", ApiRoutes.Endpoints.MCP_DEFAULTS))
        assertFalse(ApiRoutes.isAllowed("POST", "${ApiRoutes.Endpoints.MCP_DEFAULTS}?directory=/repo"))
    }
}
