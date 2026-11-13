package varro.host

import com.google.gson.JsonArray
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class NativePermissionConfigTest {
    private val shell = Json.obj("action" to "shell", "resource" to "*", "effect" to "ask")
    private val webfetch = Json.obj("action" to "webfetch", "resource" to "https://example.com/*", "effect" to "allow")

    private fun edit(raw: String, rules: JsonArray): String = JsoncEdit.nativePermissions(raw, rules).also {
        assertEquals(rules, Json.parse(ProjectPermissionConfig.stripJsonComments(it)).asJsonObject.arr("permissions"))
    }

    @Test fun `append preserves compact entries comments and trailing comma style`() {
        for (trailing in listOf(false, true)) {
            val entry = "    { \"action\": \"shell\", \"resource\": \"*\", \"effect\": \"ask\" }"
            val raw = "{\n  \"permissions\": [\n    // baseline\n$entry${if (trailing) "," else ""} // keep attached\n  ]\n}\n"
            assertEquals("{\n  \"permissions\": [\n    // baseline\n$entry, // keep attached\n    { \"action\": \"webfetch\", \"resource\": \"https://example.com/*\", \"effect\": \"allow\" }${if (trailing) "," else ""}\n  ]\n}\n",
                edit(raw, Json.array(listOf(shell, webfetch))))
        }
    }

    @Test fun `editing changes only selected fields and preserves unrelated rule bytes`() {
        val raw = "{\n  \"permissions\": [\n    { \"action\": \"shell\", \"resource\": \"*\", \"effect\": \"ask\" }, // baseline\n    {\"resource\":\"https://example.com/*\", \"effect\":\"allow\", \"action\":\"webfetch\"}\n  ]\n}\n"
        assertEquals(raw.replace("\"resource\": \"*\"", "\"resource\": \"git *\"").replace("\"effect\": \"ask\"", "\"effect\": \"allow\""),
            edit(raw, Json.array(listOf(Json.obj("action" to "shell", "resource" to "git *", "effect" to "allow"), webfetch))))
    }

    @Test fun `append preserves multiline entries tabs CRLF and inline comments`() {
        val raw = "{\r\n\t\"permissions\": [\r\n\t\t{\r\n\t\t\t\"action\": \"shell\",\r\n\t\t\t// retain this comment\r\n\t\t\t\"resource\": \"*\",\r\n\t\t\t\"effect\": \"ask\"\r\n\t\t}\r\n\t]\r\n}\r\n"
        val result = edit(raw, Json.array(listOf(shell, webfetch)))
        assertTrue(result.startsWith(raw.substringBefore("\r\n\t]") + ","))
        assertTrue(result.contains("\r\n\t\t{\r\n\t\t\t\"action\": \"webfetch\","))
        assertFalse(result.replace("\r\n", "").contains('\n'))
    }

    @Test fun `removing rules preserves survivor formatting and handles empty arrays`() {
        for (trailing in listOf("", ",")) {
            val raw = "{\n  \"permissions\": [\n    // keep baseline\n    { \"action\": \"shell\", \"resource\": \"*\", \"effect\": \"ask\" },\n    $webfetch$trailing\n  ]\n}\n"
            val result = edit(raw, Json.array(listOf(shell)))
            assertTrue(result.contains("// keep baseline\n    { \"action\": \"shell\", \"resource\": \"*\", \"effect\": \"ask\" }"))
            edit(result, JsonArray())
            edit(raw, JsonArray())
        }
    }

    @Test fun `inline empty commented and duplicate entries keep their order`() {
        for (raw in listOf("{\"permissions\":[]}", "{\"permissions\":[/* keep */]}",
            "{\"permissions\":[$shell]}", "{\"permissions\":[$shell,]}")) {
            val existing = Json.parse(ProjectPermissionConfig.stripJsonComments(raw)).asJsonObject.arr("permissions")!!
            val result = edit(raw, Json.array(existing.toList() + webfetch))
            if ("/*" in raw) assertTrue(result.contains("/* keep */"))
        }
        val raw = "{\"permissions\":[$shell, $webfetch, $shell]}"
        assertEquals(raw, edit(raw, Json.array(listOf(shell, webfetch, shell))))
        edit(raw, Json.array(listOf(webfetch, shell)))
        edit(raw, JsonArray())
    }

    @Test fun `adding native array retains unrelated legacy config and rejects duplicate keys`() {
        val raw = "{\n  // retain legacy\n  \"permission\": {\"bash\":\"ask\"}\n}"
        assertTrue(edit(raw, Json.array(listOf(shell))).contains("// retain legacy\n  \"permission\": {\"bash\":\"ask\"}"))
        assertThrows(IllegalArgumentException::class.java) {
            JsoncEdit.nativePermissions("{\"permissions\":[],\"permissions\":[]}", JsonArray())
        }
        val windows = edit("{\r\n  // keep CRLF\r\n  \"model\": \"p/m\"\r\n}", Json.array(listOf(shell)))
        assertFalse(windows.replace("\r\n", "").contains('\n'))
    }
}
