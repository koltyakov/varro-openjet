package varro.settings

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.Json
import java.nio.file.Files
import java.nio.file.Path

class SharedSettingsFileTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun document(model: String = "provider/first", port: Int = 4096): JsonObject = Json.obj(
        "version" to 1,
        "core" to Json.obj("serverPort" to port, "commitMessageModel" to model),
        "models" to Json.obj("hiddenModels" to Json.array(listOf("provider/hidden"))),
    )

    @Test fun `uses native OpenJet locations and honors Linux config override`() {
        val home = Path.of("/users/example")
        assertEquals(home.resolve("Library/Application Support/OpenJet/settings.json"), SharedSettingsFile.location("Mac OS X", home, emptyMap()))
        assertEquals(home.resolve(".config/openjet/settings.json"), SharedSettingsFile.location("Linux", home, emptyMap()))
        assertEquals(Path.of("/custom/openjet/settings.json"), SharedSettingsFile.location("Linux", home, mapOf("XDG_CONFIG_HOME" to "/custom")))
        assertEquals(home.resolve(".config/openjet/settings.json"), SharedSettingsFile.location("Linux", home, mapOf("XDG_CONFIG_HOME" to "relative")))
        assertEquals(Path.of("/roaming/OpenJet/settings.json"), SharedSettingsFile.location("Windows 11", home, mapOf("APPDATA" to "/roaming")))
        assertEquals(home.resolve("AppData/Roaming/OpenJet/settings.json"), SharedSettingsFile.location("Windows 11", home, emptyMap()))
    }

    @Test fun `migration is explicit and never replaces another IDE's settings`() {
        val file = SharedSettingsFile(temporary.root.toPath().resolve("openjet/settings.json"))
        val curated = document()
        assertNull(file.update(curated, curated))
        assertFalse(Files.exists(file.path))
        assertEquals(curated, file.update(curated, curated, initialize = true))
        val other = document("provider/other")
        assertEquals(curated, file.update(other, other, initialize = true))
        assertEquals(curated, file.read())
    }

    @Test fun `stale IDE writes merge changed fields and retain unknown settings`() {
        val file = SharedSettingsFile(temporary.root.toPath().resolve("settings.json"))
        val original = document().apply { addProperty("futureOption", "retained") }
        file.update(original, original, initialize = true)
        val first = original.deepCopy().apply { getAsJsonObject("core").addProperty("serverPort", 5000) }
        file.update(original, first)
        val second = original.deepCopy().apply { getAsJsonObject("core").addProperty("commitMessageModel", "provider/second") }
        val result = file.update(original, second)!!
        assertEquals(5000, result.getAsJsonObject("core").get("serverPort").asInt)
        assertEquals("provider/second", result.getAsJsonObject("core").get("commitMessageModel").asString)
        assertEquals("retained", result.get("futureOption").asString)
        assertEquals(result, file.update(second, second))
    }

    @Test fun `malformed and newer files remain untouched`() {
        val file = SharedSettingsFile(temporary.root.toPath().resolve("settings.json"))
        for (contents in listOf("broken json", "{\"version\":2}", "{\"version\":1,\"core\":[]}")) {
            Files.writeString(file.path, contents)
            assertThrows(IllegalArgumentException::class.java) { file.update(document(), document(), initialize = true) }
            assertEquals(contents, Files.readString(file.path))
        }
    }

    @Test fun `deleted shared settings are not silently recreated`() {
        val file = SharedSettingsFile(temporary.root.toPath().resolve("settings.json"))
        file.update(document(), document(), initialize = true)
        Files.delete(file.path)
        assertNull(file.update(document(), document("provider/other")))
        assertFalse(Files.exists(file.path))
    }
}
