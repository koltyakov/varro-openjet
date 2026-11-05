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

    @Test fun `OAuth server names follow effective replacement and legacy merge without exposing credentials`() {
        val remote = Json.obj("type" to "remote", "url" to "https://example.com/mcp", "headers" to Json.obj("Authorization" to "fixture-secret"))
        for (native in listOf(true, false)) {
            write(global.resolve("opencode.json"), config(Json.obj("docs" to remote, "disabled" to remote.deepCopy().apply { addProperty("disabled", true) },
                "header" to remote.deepCopy().apply { addProperty("oauth", false) }, "local" to server), native))
            write(root.resolve("opencode.json"), config(Json.obj("docs" to if (native) server else Json.obj("oauth" to false)), native))
            val result = service(native).read()
            assertEquals(listOf("disabled"), result.arr("oauthServers")!!.strings())
            assertFalse(result.toString().contains("fixture-secret"))
            val attached = service(native).attached(Json.parse(Files.readString(global.resolve("opencode.json"))).asJsonObject)
            assertEquals(listOf("docs", "disabled"), attached.arr("oauthServers")!!.strings())
            assertFalse(attached.toString().contains("fixture-secret"))
        }
    }

    @Test fun `imports choose native or legacy nesting and invert flags without resolving environment references`() {
        for ((native, legacy) in listOf(true to false, true to true, false to false)) {
            val workspace = Files.createDirectories(root.resolve("case-$native-$legacy"))
            val file = workspace.resolve("opencode.json")
            if (legacy) write(file, config(Json.obj("old" to server), false))
            var refreshed: Boolean? = null
            val defaults = McpDefaults(workspace, global, native, refresh = { refreshed = it })
            val result = defaults.add(Json.obj("scope" to "project", "json" to
                """{"mcpServers":{"docs":{"command":"node","args":["docs.js"],"env":{"KEY":"{env:DOCS_KEY}"},"disabled":true}}}"""))
            assertEquals(false, refreshed)
            val document = Json.parse(Files.readString(file)).asJsonObject
            val nested = native && !legacy
            val added = (if (nested) document.obj("mcp").obj("servers") else document.obj("mcp")).obj("docs")!!
            assertEquals(listOf("node", "docs.js"), added.arr("command")!!.strings())
            assertEquals("{env:DOCS_KEY}", added.obj("environment").str("KEY"))
            assertEquals(if (nested) true else false, added.bool(if (nested) "disabled" else "enabled"))
            assertFalse(added.has(if (nested) "enabled" else "disabled"))
            assertEquals(false, result.obj("effective").bool("docs"))
        }
    }

    @Test fun `global batch import preserves comments existing servers and project overrides`() {
        val file = write(global.resolve("opencode.jsonc"), "// keep me\n" + Json.obj("model" to "keep/model", "mcp" to Json.obj("servers" to Json.obj("old" to server))).toString())
        val project = write(root.resolve("opencode.json"), config(Json.obj("old" to Json.obj("type" to "local", "command" to listOf("project")))))
        val original = Files.readString(project)
        var refreshes = 0
        val result = McpDefaults(root, global, true, refresh = { assertTrue(it); refreshes++ }).add(Json.obj("scope" to "global",
            "json" to """{"mcp":{"servers":{"docs":{"url":"https://example.com/mcp"}}},"model":"not/imported"}"""))
        assertEquals(Json.obj("old" to true, "docs" to true), result.obj("global"))
        assertEquals(1, refreshes)
        assertTrue(Files.readString(file).contains("// keep me")); assertTrue(Files.readString(file).contains("keep/model"))
        assertFalse(Files.readString(file).contains("not/imported")); assertEquals(original, Files.readString(project))
    }

    @Test fun `duplicates in any file in the chosen scope reject a whole import and cross-scope overrides remain allowed`() {
        val file = write(global.resolve("opencode.json"), config(Json.obj("docs" to server)))
        val original = Files.readString(file)
        val batch = Json.obj("scope" to "global", "json" to Json.obj("new" to server, "docs" to server).toString())
        assertThrows(IllegalArgumentException::class.java) { service().add(batch) }
        assertEquals(original, Files.readString(file)); assertFalse(Files.exists(root.resolve("opencode.json")))
        service().add(batch.deepCopy().apply { addProperty("scope", "project") })
        assertEquals(Json.obj("new" to true, "docs" to true), service().read().obj("project"))
        assertThrows(IllegalArgumentException::class.java) { service().add(batch.deepCopy().apply { addProperty("scope", "project") }) }
    }

    @Test fun `invalid inputs containers dirty buffers busy checks and concurrent edits cannot overwrite an import target`() {
        val file = write(root.resolve("opencode.json"), "{}")
        val body = Json.obj("scope" to "project", "json" to Json.obj("docs" to server).toString())
        for (invalid in listOf(Json.obj("scope" to "other", "json" to "{}"), Json.obj("scope" to "project", "json" to 1),
            body.deepCopy().apply { addProperty("name", 42) }, Json.obj("scope" to "project", "json" to server.toString()))) {
            assertThrows(Exception::class.java) { service().add(invalid) }
            assertEquals("{}", Files.readString(file))
        }
        for (raw in listOf("{\"mcp\":null}", "{\"mcp\":{\"servers\":[]}}", "{\"mcp\":4}")) {
            write(file, raw)
            assertThrows(Exception::class.java) { service().add(body) }
            assertEquals(raw, Files.readString(file))
        }
        write(file, "{}")
        for (message in listOf("unsaved changes", "active work")) {
            assertThrows(IllegalStateException::class.java) { McpDefaults(root, global, true, beforeWrite = { error(message) }).add(body) }
            assertEquals("{}", Files.readString(file))
        }
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            McpDefaults(root, global, true, beforeWrite = { if (++checks == 2) Files.writeString(it, "{\"model\":\"external\"}") }).add(body)
        }
        assertEquals("{\"model\":\"external\"}", Files.readString(file))
    }

    @Test fun `concurrent imports retain both batches and new names need an explicit name for a bare server`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            listOf("one", "two").map { name -> pool.submit {
                service().add(Json.obj("scope" to "project", "json" to server.toString(), "name" to name))
            } }.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(Json.obj("one" to true, "two" to true), service().read().obj("project"))
    }

    @Test fun `opening creates only schema scaffolds chooses the highest-precedence existing file and never parses invalid JSON`() {
        val defaults = service()
        val project = defaults.open("project")
        assertEquals(root.resolve("opencode.json").toRealPath(), project)
        assertEquals(Json.obj("\$schema" to "https://opencode.ai/config.json"), Json.parse(Files.readString(project)))
        val file = write(global.resolve("opencode.jsonc"), "{ broken JSON")
        assertEquals(file.toRealPath(), defaults.open("global"))
        assertEquals("{ broken JSON", Files.readString(file))
        val nested = write(root.resolve(".opencode/opencode.jsonc"), "// invalid")
        assertEquals(nested.toRealPath(), defaults.open("project"))
    }

    @Test fun `opening an unsaved new config never writes to disk and add routes are narrow`() {
        val selected = service().open("global") { true }
        assertEquals(global.toRealPath().resolve("opencode.json"), selected)
        assertFalse(Files.exists(selected))
        assertThrows(IllegalArgumentException::class.java) { service().open("other") }
        for (route in listOf(ApiRoutes.Endpoints.MCP_CONFIG, ApiRoutes.Endpoints.MCP_CONFIG_OPEN)) {
            assertTrue(ApiRoutes.isAllowed("POST", route))
            for (method in listOf("GET", "DELETE", "PATCH")) assertFalse(ApiRoutes.isAllowed(method, route))
            assertFalse(ApiRoutes.isAllowed("POST", "$route?directory=/other"))
        }
    }

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
