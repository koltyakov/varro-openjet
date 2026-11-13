package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class GlobalPermissionConfigTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private val rules = Json.array(listOf(Json.obj("permission" to "webfetch", "pattern" to "https://trusted.example/*", "action" to "allow")))

    @Test fun `global approvals and panel edits keep compact native entries and comments`() {
        val root = temporary.newFolder().toPath()
        val file = root.resolve("opencode.jsonc")
        val entry = "    { \"action\": \"shell\", \"resource\": \"*\", \"effect\": \"ask\" }"
        val raw = "{\n  \"permissions\": [\n    // baseline\n$entry // keep attached\n  ]\n}"
        Files.writeString(file, raw)
        val config = GlobalPermissionConfig(root)
        config.allow("webfetch", listOf("https://trusted.example/*"))
        val appended = Files.readString(file)
        assertTrue(appended.contains("// baseline\n$entry, // keep attached"))
        val current = config.read().arr("rules")!!
        config.write(current)
        assertEquals(appended, Files.readString(file))
        current[0].asJsonObject.addProperty("action", "deny")
        config.write(current)
        assertEquals(appended.replace("\"effect\": \"ask\"", "\"effect\": \"deny\""), Files.readString(file))
    }

    @Test fun `global config selects only last global file and preserves unrelated fields and comments`() {
        val root = temporary.root.toPath()
        val first = Files.writeString(root.resolve("config.json"), "{\"permission\":\"ask\"}")
        val selected = root.resolve("opencode.jsonc")
        val original = "{ // global policy\n \"model\": \"p/m\", \"permissions\": [] }"
        Files.writeString(root.resolve("opencode.json"), "{\"model\":\"other/model\"}")
        Files.writeString(selected, original)
        val config = GlobalPermissionConfig(root)
        assertEquals(selected.toString(), config.read().str("targetPath"))
        assertEquals(true, config.write(rules).bool("supportsPatterns"))
        assertEquals(rules, config.read().arr("rules"))
        assertTrue(Files.readString(selected).contains("// global policy")); assertTrue(Files.readString(selected).contains("\"model\": \"p/m\""))
        assertEquals("{\"permission\":\"ask\"}", Files.readString(first))
        config.write(Json.array(emptyList<Any>()))
        assertTrue(config.read().arr("rules")!!.isEmpty)
    }

    @Test fun `missing global config creates native syntax and URL rules without workspace or runtime files`() {
        val root = temporary.newFolder().toPath()
        val config = GlobalPermissionConfig(root)
        config.allow("webfetch", listOf("https://trusted.example/*"))
        val file = root.resolve("opencode.json")
        assertEquals(rules, config.read().arr("rules"))
        assertTrue(Json.parse(Files.readString(file)).asJsonObject.has("permissions"))
        Files.list(root).use { assertEquals(listOf(file), it.toList()) }
    }

    @Test fun `global legacy syntax stays legacy and retains inherited scalar defaults`() {
        val root = temporary.newFolder().toPath()
        Files.writeString(root.resolve("config.json"), "{\"permission\":{\"bash\":\"ask\"}}")
        val target = Files.writeString(root.resolve("opencode.json"), "{\"permission\":{}}")
        val config = GlobalPermissionConfig(root)
        assertEquals(false, config.read().bool("supportsPatterns"))
        config.allow("bash", listOf("git diff *"))
        val permission = Json.parse(Files.readString(target)).asJsonObject.obj("permission").obj("bash")
        assertEquals("ask", permission.str("*")); assertEquals("allow", permission.str("git diff *"))
        assertFalse(Json.parse(Files.readString(target)).asJsonObject.has("permissions"))
        assertThrows(IllegalArgumentException::class.java) { config.write(rules) }
    }

    @Test fun `global writes reject dirty documents and concurrent edits without changing unrelated settings`() {
        val root = temporary.newFolder().toPath()
        val file = Files.writeString(root.resolve("opencode.json"), "{}")
        assertThrows(IllegalStateException::class.java) { GlobalPermissionConfig(root) { error("unsaved") }.write(rules) }
        assertEquals("{}", Files.readString(file))
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            GlobalPermissionConfig(root) { if (++checks == 2) Files.writeString(file, "{\"model\":\"external\"}") }.write(rules)
        }
        assertEquals("{\"model\":\"external\"}", Files.readString(file))
    }
}
