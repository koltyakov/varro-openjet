package varro.host

import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.obj
import varro.protocol.str
import java.nio.file.Files

class GlobalWarmingConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `warming creates the default global file and updates the highest configured copy in place`() {
        val directory = temporary.newFolder("opencode").toPath()
        assertTrue(GlobalWarmingConfig(directory).update(JsonPrimitive(true)))
        val created = Files.readString(directory.resolve("opencode.json"))
        assertEquals(true, Json.parse(created).asJsonObject.get("warming").asBoolean)
        assertEquals("https://opencode.ai/config.json", Json.parse(created).asJsonObject.str("\$schema"))
        // Unchanged values do not rewrite the file.
        assertFalse(GlobalWarmingConfig(directory).update(JsonPrimitive(true)))

        Files.writeString(directory.resolve("opencode.jsonc"), "{\n  // keep me\n  \"model\": \"a/b\"\n}\n")
        // The highest-precedence file that already sets warming is the one edited.
        assertTrue(GlobalWarmingConfig(directory).update(Json.obj("duration" to "60 minutes")))
        assertEquals("60 minutes", Json.parse(Files.readString(directory.resolve("opencode.json"))).asJsonObject.obj("warming").str("duration"))
        assertFalse(Files.readString(directory.resolve("opencode.jsonc")).contains("warming"))
    }

    @Test fun `removal clears every global copy and preserves comments`() {
        val directory = temporary.newFolder("opencode").toPath()
        Files.writeString(directory.resolve("config.json"), "{ \"warming\": true }\n")
        Files.writeString(directory.resolve("opencode.jsonc"), "{\n  // keep me\n  \"model\": \"a/b\",\n  \"warming\": { \"duration\": \"2 hours\" }\n}\n")
        val checked = mutableListOf<String>()
        assertTrue(GlobalWarmingConfig(directory) { checked.add(it.fileName.toString()) }.update(null))
        for (name in listOf("config.json", "opencode.jsonc")) {
            val raw = Files.readString(directory.resolve(name))
            assertFalse(name, Json.parse(ProjectPermissionConfig.stripJsonComments(raw)).asObjectOrNull()!!.has("warming"))
        }
        assertTrue(Files.readString(directory.resolve("opencode.jsonc")).contains("// keep me"))
        assertEquals(listOf("config.json", "opencode.jsonc"), checked)
        assertFalse(GlobalWarmingConfig(directory).update(null))
        assertFalse(Files.exists(directory.resolve("opencode.json")))
    }

    @Test fun `unsaved editor changes block the write`() {
        val directory = temporary.newFolder("opencode").toPath()
        Files.writeString(directory.resolve("opencode.json"), "{}\n")
        assertThrows(IllegalArgumentException::class.java) {
            GlobalWarmingConfig(directory) { require(false) { "unsaved" } }.update(JsonPrimitive(true))
        }
        assertEquals("{}\n", Files.readString(directory.resolve("opencode.json")))
    }

    @Test fun `warming state messages keep explicit nulls the webview requires`() {
        val state = Json.obj("available" to true, "mode" to "chosen", "durationMs" to 1_800_000, "intervalMs" to 240_000,
            "openCodeWarming" to false, "sessions" to Json.obj("ses_a" to Json.obj("warmUntil" to null, "sentAt" to null, "stopped" to null)))
        val wire = Json.parse(Json.stringifyMessage(Json.message("warming/state", state))).asJsonObject
        val session = wire.obj("payload").obj("sessions").obj("ses_a")!!
        for (key in listOf("warmUntil", "sentAt", "stopped")) assertTrue(key, session.has(key) && session.get(key).isJsonNull)
    }
}
