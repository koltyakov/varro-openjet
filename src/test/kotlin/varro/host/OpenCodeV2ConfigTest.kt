package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class OpenCodeV2ConfigTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    @Test fun `native permission and agent configuration keep native keys and unrelated settings`() {
        val root = temporary.root.toPath()
        val path = root.resolve("opencode.jsonc")
        Files.writeString(path, """{"permissions":[{"action":"shell","resource":"git *","effect":"ask"}],"agents":{"review":{"system":"Review carefully","model":"old/model"}},"theme":"keep"}""")
        val permissions = ProjectPermissionConfig(root)
        assertEquals("bash", permissions.read()[0].asJsonObject.str("permission"))
        permissions.write(Json.array(listOf(Json.obj("permission" to "task", "pattern" to "*", "action" to "allow"))))
        OpenCodeGlobalConfig(root).patch(Json.obj("agent" to Json.obj("review" to Json.obj("model" to "new/model")), "small_model" to "small/model"))
        val document = Json.parse(Files.readString(path)).asJsonObject
        assertFalse(document.has("permission")); assertFalse(document.has("agent")); assertFalse(document.has("small_model"))
        assertEquals("subagent", document.arr("permissions")!![0].asJsonObject.str("action"))
        assertEquals("Review carefully", document.obj("agents").obj("review").str("system"))
        assertEquals("new/model", document.obj("agents").obj("review").str("model"))
        assertEquals("small/model", document.obj("agents").obj("title").str("model"))
        assertEquals("keep", document.str("theme"))
    }

    @Test fun `v2 permission overrides use dot opencode precedence while v1 uses direct config`() {
        val root = temporary.newFolder().toPath()
        Files.createDirectories(root.resolve(".opencode"))
        Files.writeString(root.resolve(".opencode/opencode.json"), """{"permissions":[{"action":"shell","resource":"*","effect":"ask"}]}""")
        val workspace = Files.createDirectories(root.resolve("project"))
        val native = ProjectPermissionConfig(workspace) { true }
        val rules = Json.array(listOf(Json.obj("permission" to "bash", "pattern" to "git *", "action" to "ask")))
        native.write(rules)
        assertEquals(workspace.resolve(".opencode/opencode.json"), native.path())
        assertEquals(rules, native.read())
        assertTrue(Json.parse(Files.readString(native.path())).asJsonObject.has("permissions"))
        assertEquals(workspace.resolve("opencode.json"), ProjectPermissionConfig(workspace).path())
    }

    @Test fun `unsetting model routing chooses the field present in mixed config`() {
        val root = temporary.newFolder().toPath()
        val path = root.resolve("opencode.json")
        Files.writeString(path, """{"agents":{"other":{"model":"keep/model"}},"agent":{"review":{"model":"old/model","description":"keep"}},"small_model":"old/small"}""")
        OpenCodeGlobalConfig(root).patch(Json.obj("agent" to Json.obj("review" to Json.obj("model" to "")), "small_model" to ""))
        val saved = Json.parse(Files.readString(path)).asJsonObject
        assertFalse(saved.has("small_model"))
        assertFalse(saved.obj("agent").obj("review")!!.has("model"))
        assertEquals("keep", saved.obj("agent").obj("review").str("description"))
        assertEquals("keep/model", saved.obj("agents").obj("other").str("model"))
    }
}
