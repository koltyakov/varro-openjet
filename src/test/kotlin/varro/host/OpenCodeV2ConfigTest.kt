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
}
