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
