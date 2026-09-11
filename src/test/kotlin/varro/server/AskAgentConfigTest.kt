package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path

class AskAgentConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun config(environment: Map<String, String> = emptyMap(), workspace: Path? = null) = AskAgentConfig(
        environment, { workspace?.toString() }, temporary.root.toPath(), temporary.root.toPath(),
    )

    private fun read(path: Path) = Json.parseOrNull(Files.readString(path)).asObjectOrNull()!!

    @Test fun `Ask is injected as read only and can be toggled without changing its config path`() {
        val config = config()
        val path = Path.of(config.prepare(true).getValue("OPENCODE_CONFIG"))
        val ask = read(path).obj("agent").obj("ask")!!
        assertEquals("primary", ask.str("mode"))
        assertEquals("deny", ask.obj("permission").str("*"))
        assertEquals("allow", ask.obj("permission").str("read"))
        assertFalse(ask.obj("permission")!!.has("bash"))
        assertFalse(config.rewrite(true))
        assertTrue(config.rewrite(false))
        assertFalse(read(path).has("agent"))
        assertTrue(config.rewrite(true))
        assertNotNull(read(path).obj("agent").obj("ask"))
        config.close()
        assertFalse(Files.exists(path.parent))
    }

    @Test fun `initially disabled Ask can be enabled and a new launch cleans the old file`() {
        config().use { config ->
            val old = Path.of(config.prepare(false).getValue("OPENCODE_CONFIG"))
            assertFalse(read(old).has("agent"))
            assertTrue(config.rewrite(true))
            val next = Path.of(config.prepare(true).getValue("OPENCODE_CONFIG"))
            assertNotEquals(old, next)
            assertFalse(Files.exists(old.parent))
        }
    }

    @Test fun `caller supplied config is not replaced or rewritten`() {
        val path = temporary.newFile("custom.json").toPath()
        Files.writeString(path, "{}")
        config(mapOf("OPENCODE_CONFIG" to path.toString())).use {
            assertTrue(it.prepare(true).isEmpty())
            assertFalse(it.rewrite(false))
        }
        assertEquals("{}", Files.readString(path))
    }

    @Test fun `inline Ask including disabled or differently cased agents is preserved`() {
        listOf("""{"agent":{"Ask":{"disable":true}}}""", "not json").forEach { inline ->
            config(mapOf("OPENCODE_CONFIG_CONTENT" to inline)).use {
                val path = Path.of(it.prepare(true).getValue("OPENCODE_CONFIG"))
                assertFalse(read(path).has("agent"))
            }
        }
    }

    @Test fun `global JSONC and markdown Ask definitions prevent injection`() {
        val root = temporary.newFolder("global").toPath()
        val directory = Files.createDirectory(root.resolve("opencode"))
        val file = directory.resolve("opencode.jsonc")
        val original = """{ // user config
          "agent": { "ask": { "prompt": "Custom Ask", }, },
        }"""
        Files.writeString(file, original)
        config(mapOf("XDG_CONFIG_HOME" to root.toString())).use {
            val injected = Path.of(it.prepare(true).getValue("OPENCODE_CONFIG"))
            assertFalse(read(injected).has("agent"))
            assertEquals(original, Files.readString(file))
            Files.delete(file)
            Files.createDirectory(directory.resolve("agents"))
            Files.writeString(directory.resolve("agents/ask.md"), "Custom Ask")
            assertFalse(it.rewrite(true))
        }
    }

    @Test fun `project Ask is discovered up to the git root but not outside it`() {
        val root = temporary.newFolder("repo").toPath()
        Files.createDirectory(root.resolve(".git"))
        val child = Files.createDirectory(root.resolve("child"))
        Files.writeString(temporary.root.toPath().resolve("opencode.json"), """{"agent":{"ask":{}}}""")
        config(workspace = child).use {
            val path = Path.of(it.prepare(true).getValue("OPENCODE_CONFIG"))
            assertNotNull(read(path).obj("agent"))
            Files.writeString(root.resolve("opencode.json"), """{"agent":{"ask":{"disable":true}}}""")
            assertTrue(it.rewrite(true))
            assertFalse(read(path).has("agent"))
        }
    }

    @Test fun `only OpenCode agent directories and config names suppress the runtime agent`() {
        val root = temporary.newFolder("workspace").toPath()
        Files.createDirectory(root.resolve(".git"))
        Files.writeString(root.resolve("config.json"), "[]")
        Files.createDirectory(root.resolve("agents"))
        Files.writeString(root.resolve("agents/ask.md"), "Not an OpenCode agent")
        config(workspace = root).use {
            val path = Path.of(it.prepare(true).getValue("OPENCODE_CONFIG"))
            assertNotNull(read(path).obj("agent"))
            val directory = Files.createDirectories(root.resolve(".opencode/agent"))
            Files.writeString(directory.resolve("Ask.md"), "User's OpenCode agent")
            assertTrue(it.rewrite(true))
            assertFalse(read(path).has("agent"))
        }
    }
}
