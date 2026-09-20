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
        val saved = Json.parse(Files.readString(path)).asJsonObject
        assertEquals("other/model", saved.str("model"))
        assertEquals(true, saved.obj("experimental").bool("keep"))
        val policies = saved.obj("experimental").arr("policies")!!
        assertEquals(2, policies.size())
        assertEquals("other", policies[0].asJsonObject.str("resource"))
        assertEquals("deny", policies[1].asJsonObject.str("effect"))
        assertFalse(Files.exists(root.resolve("opencode.json")))
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
