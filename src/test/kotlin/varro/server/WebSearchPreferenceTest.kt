package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files
import java.sql.DriverManager

class WebSearchPreferenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `managed V2 defaults preserve explicit false null malformed and ancestor preferences`() {
        val home = temporary.newFolder().toPath()
        val workspace = Files.createDirectories(home.resolve("parent/repo/nested"))
        Files.createDirectory(home.resolve("parent/repo/.git"))
        val config = home.resolve("parent/opencode.jsonc")
        val environment = mapOf("XDG_CONFIG_HOME" to home.resolve("config").toString(), "XDG_DATA_HOME" to home.resolve("data").toString())
        val preference = WebSearchPreference(environment, { workspace.toString() }, home)
        assertTrue(preference.useRandomDefault())
        assertFalse(Files.exists(home.resolve("data/opencode/opencode.db")))
        for (raw in listOf("{\"websearch\":false}", "{\"websearch\":null}", "{broken", "{\"websearch\":{\"provider\":\"exa\"}}")) {
            Files.writeString(config, raw); assertFalse(raw, preference.useRandomDefault())
        }
        Files.writeString(config, "{}")
        assertTrue(preference.useRandomDefault())
        AskAgentConfig(environment, { workspace.toString() }, home, home).use { injected ->
            val path = java.nio.file.Path.of(injected.prepare(false, v2 = true).getValue("OPENCODE_CONFIG"))
            assertEquals("random", Json.parse(Files.readString(path)).asJsonObject.obj("websearch").str("provider"))
            Files.writeString(config, "{\"websearch\":false}")
            assertTrue(injected.rewrite(false))
            assertFalse(Json.parse(Files.readString(path)).asJsonObject.has("websearch"))
        }
        Files.delete(config)
        AskAgentConfig(environment, { workspace.toString() }, home, home).use { injected ->
            val path = java.nio.file.Path.of(injected.prepare(false).getValue("OPENCODE_CONFIG"))
            assertFalse(Json.parse(Files.readString(path)).asJsonObject.has("websearch"))
        }
    }

    @Test fun `saved false and unreadable databases prevent defaults without creating or migrating tables`() {
        val home = temporary.newFolder().toPath()
        val path = home.resolve("saved.db")
        val environment = mapOf("OPENCODE_DB" to path.toString())
        val preference = WebSearchPreference(environment, { home.toString() }, home)
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use {
                it.execute("CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT)")
                it.execute("INSERT INTO kv VALUES('websearch:provider','false')")
            }
        }
        val before = Files.readAllBytes(path)
        assertFalse(preference.useRandomDefault()); assertArrayEquals(before, Files.readAllBytes(path))
        assertTrue(WebSearchPreference(mapOf("OPENCODE_DB" to ":memory:"), { home.toString() }, home).useRandomDefault())
        Files.writeString(path, "invalid database")
        assertFalse(preference.useRandomDefault())
    }
}
