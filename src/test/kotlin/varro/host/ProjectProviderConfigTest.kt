package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class ProjectProviderConfigTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `disabling replaces matching policies and preserves other settings`() {
        val root = temporary.root.toPath()
        val path = root.resolve("opencode.jsonc")
        Files.writeString(path, """{
            // Workspace settings
            "model":"other/model",
            "experimental":{"keep":true,"policies":[
                {"action":"provider.use","resource":"ollama","effect":"allow"},
                {"action":"provider.use","resource":"other","effect":"allow"},
            ]}
        }""")
        val config = ProjectProviderConfig(root)
        repeat(2) { config.disable("ollama") }
        assertTrue(Files.readString(path).contains("// Workspace settings"))
        val saved = Json.parse(ProjectPermissionConfig.stripJsonComments(Files.readString(path))).asJsonObject
        assertEquals("other/model", saved.str("model"))
        assertEquals(true, saved.obj("experimental").bool("keep"))
        val policies = saved.obj("experimental").arr("policies")!!
        assertEquals(2, policies.size())
        assertEquals("other", policies[0].asJsonObject.str("resource"))
        assertEquals("deny", policies[1].asJsonObject.str("effect"))
        assertFalse(Files.exists(root.resolve("opencode.json")))
    }

    @Test fun `native policies override the legacy key without losing comments or unrelated values`() {
        val path = temporary.root.toPath().resolve("opencode.jsonc")
        Files.writeString(path, """{
            // Keep the repository explanation
            "number": 12345,
            "experimental":{"policies":[{"action":"provider.use","resource":"ollama","effect":"allow"}]},
            "policies":[{"action":"provider.use","resource":"other","effect":"allow"}],
        }""")
        repeat(2) { ProjectProviderConfig(path.parent).disable("ollama") }
        val raw = Files.readString(path)
        assertTrue(raw.contains("// Keep the repository explanation"))
        val saved = Json.parse(ProjectPermissionConfig.stripJsonComments(raw)).asJsonObject
        assertEquals(12345, saved.int("number"))
        assertEquals("allow", saved.obj("experimental").arr("policies")!![0].asJsonObject.str("effect"))
        assertEquals(2, saved.arr("policies")!!.size())
        assertEquals("deny", saved.arr("policies")!![1].asJsonObject.str("effect"))
    }

    @Test fun `concurrent configuration edits are not overwritten`() {
        val path = temporary.root.toPath().resolve("opencode.json")
        Files.writeString(path, "{}")
        val changed = """{"model":"new/model"}"""
        assertThrows(IllegalArgumentException::class.java) {
            ProjectProviderConfig(path.parent) { Files.writeString(path, changed) }.disable("ollama")
        }
        assertEquals(changed, Files.readString(path))
        Files.writeString(path, """{"policies":null}""")
        assertThrows(IllegalArgumentException::class.java) { ProjectProviderConfig(path.parent).disable("ollama") }
        assertEquals("""{"policies":null}""", Files.readString(path))
    }

    @Test fun `long strings escaped delimiters and trailing comments survive policy insertion`() {
        val path = temporary.root.toPath().resolve("opencode.jsonc")
        val instructions = "escaped \\\" // /* { [ " + "long".repeat(5000)
        val raw = "{\n\"instructions\":${Json.toElement(instructions)},\n\"experimental\":{\"keep\":[1,true,{\"x\":\"}\\\"\"}]} // trailing explanation\n}"
        Files.writeString(path, raw)
        ProjectProviderConfig(path.parent).disable("ollama")
        val updated = Files.readString(path)
        assertTrue(updated.contains("// trailing explanation"))
        val document = Json.parse(ProjectPermissionConfig.stripJsonComments(updated)).asJsonObject
        assertEquals(instructions, document.str("instructions"))
        assertEquals("deny", document.obj("experimental").arr("policies")!![0].asJsonObject.str("effect"))
    }

    @Test fun `duplicate policy containers are rejected instead of editing an ineffective occurrence`() {
        val path = temporary.root.toPath().resolve("opencode.jsonc")
        for (raw in listOf("""{"policies":[],"policies":[]}""", """{"experimental":{},"experimental":{}}""")) {
            Files.writeString(path, raw)
            assertThrows(IllegalArgumentException::class.java) { ProjectProviderConfig(path.parent).disable("ollama") }
            assertEquals(raw, Files.readString(path))
        }
    }

    @Test fun `invalid provider or malformed policies leave the file untouched`() {
        val root = temporary.root.toPath()
        val path = root.resolve("opencode.json")
        val raw = """{"experimental":{"policies":{}}}"""
        Files.writeString(path, raw)
        val config = ProjectProviderConfig(root)
        assertThrows(IllegalArgumentException::class.java) { config.disable("openai") }
        assertThrows(IllegalArgumentException::class.java) { config.disable("ollama") }
        assertEquals(raw, Files.readString(path))
    }

    @Test fun `ancestor dot opencode config requires a local dot opencode override`() {
        val root = temporary.newFolder().toPath()
        val ancestor = Files.createDirectories(root.resolve(".opencode")).resolve("opencode.jsonc")
        val inherited = """{"experimental":{"policies":[{"action":"provider.use","resource":"ollama","effect":"allow"}]}}"""
        Files.writeString(ancestor, inherited)
        val project = Files.createDirectories(root.resolve("project"))
        Files.createDirectory(project.resolve(".git"))
        Files.writeString(project.resolve("opencode.json"), "{}")
        val config = ProjectProviderConfig(project)
        config.disable("ollama")
        assertEquals(project.resolve(".opencode/opencode.json"), config.path())
        assertEquals("deny", Json.parse(Files.readString(config.path())).asJsonObject.obj("experimental").arr("policies")!![0].asJsonObject.str("effect"))
        assertEquals(inherited, Files.readString(ancestor))
        assertEquals("{}", Files.readString(project.resolve("opencode.json")))
    }
}
