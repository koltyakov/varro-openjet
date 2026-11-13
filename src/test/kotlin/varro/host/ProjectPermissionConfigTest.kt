package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectPermissionConfigTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private val rules = Json.array(listOf(Json.obj("permission" to "bash", "pattern" to "git *", "action" to "ask")))

    @Test fun `standing approvals retain native fields comments and formatting`() {
        val root = temporary.newFolder().toPath()
        val path = root.resolve("opencode.jsonc")
        val raw = "{\n  \"permissions\": [\n    { \"action\": \"shell\", /* policy */ \"resource\": \"*\", \"effect\": \"ask\", \"extra\": true } // keep\n  ]\n}"
        Files.writeString(path, raw)
        ProjectPermissionConfig(root, { true }).allow("webfetch", listOf("https://example.com/*"))
        val saved = Files.readString(path)
        assertTrue(saved.startsWith(raw.substringBefore(" // keep") + ", // keep"))
        assertEquals(true, Json.parse(ProjectPermissionConfig.stripJsonComments(saved)).asJsonObject.arr("permissions")!![0].asJsonObject.bool("extra"))
        assertEquals(2, ProjectPermissionConfig(root).read().size())
    }

    @Test fun `new V2 rules use native arrays while existing legacy syntax is preserved`() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder().toPath()
            val file = root.resolve("opencode.jsonc")
            Files.writeString(file, if (legacy) "{ // keep\n \"permission\": {\"bash\":\"deny\"}, \"model\":\"p/m\" }" else "{ // keep\n \"model\":\"p/m\" }")
            val config = ProjectPermissionConfig(root, { true })
            assertEquals(!legacy, config.supportsPatterns())
            config.write(rules)
            val raw = Files.readString(file)
            assertTrue(raw.contains("// keep")); assertTrue(raw.contains("\"model\":\"p/m\""))
            val document = Json.parse(ProjectPermissionConfig.stripJsonComments(raw)).asJsonObject
            assertEquals(!legacy, document.has("permissions")); assertEquals(legacy, document.has("permission"))
            assertEquals(rules, config.read())
        }
    }

    @Test fun `native URL-specific allowances append in order and do not use legacy scalar restrictions`() {
        val config = ProjectPermissionConfig(temporary.newFolder().toPath(), { true })
        config.write(Json.array(listOf(Json.obj("permission" to "webfetch", "pattern" to "*", "action" to "ask"))))
        config.allow("webfetch", listOf("https://trusted.example/*"))
        val saved = config.read()
        assertEquals(listOf("*", "https://trusted.example/*"), saved.map { it.asJsonObject.str("pattern") })
        assertEquals(listOf("ask", "allow"), saved.map { it.asJsonObject.str("action") })
    }

    @Test fun `independent standing approval writers merge fresh config without losing rules`() {
        val root = temporary.newFolder().toPath()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..8).map { index -> pool.submit { ProjectPermissionConfig(root, { true }).allow("read", listOf("file-$index")) } }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals((1..8).map { "file-$it" }.toSet(), ProjectPermissionConfig(root, { true }).read().map { it.asJsonObject.str("pattern") }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun `dirty targets symlink aliases and changed target selection cannot overwrite config`() {
        val root = temporary.newFolder().toPath()
        val file = Files.writeString(root.resolve("opencode.json"), "{}")
        val config = ProjectPermissionConfig(root, { true }, beforeSave = { error("unsaved document") })
        assertThrows(IllegalStateException::class.java) { config.write(rules) }
        assertEquals("{}", Files.readString(file))
        val alias = Files.createSymbolicLink(root.resolve("alias"), root)
        ProjectPermissionConfig(alias, { true }).write(rules)
        assertTrue(Files.isSymbolicLink(alias)); assertEquals(rules, ProjectPermissionConfig(root).read())
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPermissionConfig(root, { true }).write(rules.deepCopy().apply { add(Json.obj("permission" to "read", "pattern" to "*", "action" to "deny")) }) {
                Files.writeString(root.resolve("opencode.jsonc"), "{\"model\":\"changed\"}")
            }
        }
        assertEquals(rules, ProjectPermissionConfig(root, target = { file }).read())
    }

    @Test fun `permission edits retain comments whitespace unrelated fields and JSONC filename`() {
        val root = temporary.root.toPath()
        val path = root.resolve("opencode.jsonc")
        val raw = "{\n  // team policy\n  \"model\" : \"provider/model\",\n  \"permission\": {\"bash\": \"deny\"}, // retained\n}\n"
        Files.writeString(path, raw)
        val config = ProjectPermissionConfig(root)
        config.write(rules)
        assertEquals(raw.replace("{\"bash\": \"deny\"}", PermissionService.toConfig(rules).toString()), Files.readString(path))
        assertEquals(rules, config.read())
        assertFalse(Files.exists(root.resolve("opencode.json")))
    }

    @Test fun `native permissions update the effective field and reject duplicate keys`() {
        val root = temporary.root.toPath()
        val path = root.resolve("opencode.jsonc")
        Files.writeString(path, "{ /* keep */ \"permissions\": [], \"model\":\"p/m\" }")
        ProjectPermissionConfig(root).write(rules)
        assertTrue(Files.readString(path).contains("/* keep */"))
        assertEquals(rules, ProjectPermissionConfig(root).read())
        val duplicate = "{\"permission\":{},\"permission\":{}}"
        Files.writeString(path, duplicate)
        assertThrows(IllegalArgumentException::class.java) { ProjectPermissionConfig(root).write(rules) }
        assertEquals(duplicate, Files.readString(path))
    }

    @Test fun `external edits win over a prepared permission replacement`() {
        val path = temporary.root.toPath().resolve("opencode.jsonc")
        Files.writeString(path, "{}")
        val changed = "{\"model\":\"new/model\"}"
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPermissionConfig(path.parent).write(rules) { Files.writeString(path, changed) }
        }
        assertEquals(changed, Files.readString(path))
        Files.list(path.parent).use { assertEquals(1, it.count()) }
    }

    @Test fun `independent permission and provider writers preserve each others fields`() {
        val root = temporary.root.toPath()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val permissions = pool.submit { repeat(20) { ProjectPermissionConfig(root).write(rules) } }
            val providers = pool.submit { repeat(20) { ProjectProviderConfig(root).disable("ollama") } }
            permissions.get(5, TimeUnit.SECONDS)
            providers.get(5, TimeUnit.SECONDS)
            assertEquals(rules, ProjectPermissionConfig(root).read())
            val saved = Json.parse(Files.readString(root.resolve("opencode.json"))).asJsonObject
            assertEquals("deny", saved.obj("experimental").arr("policies")!![0].asJsonObject.str("effect"))
        } finally { pool.shutdownNow() }
    }
}
