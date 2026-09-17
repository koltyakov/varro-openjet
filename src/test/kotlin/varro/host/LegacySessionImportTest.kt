package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.sql.DriverManager

class LegacySessionImportTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private fun fixture(): java.nio.file.Path {
        val path = temporary.root.toPath().resolve("legacy.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { sql ->
            sql.execute("CREATE TABLE session(id TEXT PRIMARY KEY,title TEXT,directory TEXT,parent_id TEXT,time_created INTEGER,time_updated INTEGER)")
            sql.execute("CREATE TABLE message(id TEXT,session_id TEXT,time_created INTEGER,data TEXT)")
            sql.execute("CREATE TABLE part(id TEXT,message_id TEXT,session_id TEXT,time_created INTEGER,data TEXT)")
            sql.execute("INSERT INTO session VALUES('ses_root','Original','/workspace',NULL,1,2),('ses_child','Child','/workspace','ses_root',1,2),('ses_other','Other','/other','ses_root',1,2)")
            sql.execute("INSERT INTO message VALUES('msg_user','ses_root',1,'{\"role\":\"user\",\"time\":{\"created\":1}}'),('msg_assistant','ses_child',2,'{\"role\":\"assistant\",\"providerID\":\"test\",\"modelID\":\"model\",\"time\":{\"created\":2}}')")
            sql.execute("INSERT INTO part VALUES('part_text','msg_user','ses_root',1,'{\"type\":\"text\",\"text\":\"Original prompt\"}'),('part_tool','msg_assistant','ses_child',2,'{\"type\":\"tool\",\"tool\":\"bash\",\"callID\":\"call_test\",\"state\":{\"status\":\"running\",\"input\":{\"command\":\"pwd\"}}}')")
        } }
        return path
    }

    @Test fun `copies same-workspace children without altering source or executing tools`() {
        val path = fixture()
        val before = java.nio.file.Files.readAllBytes(path)
        val payloads = mutableListOf<com.google.gson.JsonObject>()
        val importer = LegacySessionImport(path) { method, route, body ->
            if (method == "GET") Json.obj("project" to Json.obj("id" to "project_test"))
            else {
                assertEquals("POST", method); assertEquals("/api/experimental/session/import", route)
                payloads.add(body!!.asJsonObject); Json.obj()
            }
        }
        val choice = importer.list("/workspace").single()
        val id = importer.importCopy(choice)
        assertNotEquals("ses_root", id)
        assertEquals(2, payloads.size)
        assertEquals(id, payloads[1].obj("info").str("parentID"))
        assertEquals("Original (v1 copy)", payloads[0].obj("info").str("title"))
        assertFalse(payloads[0].obj("info")!!.has("permissions"))
        assertEquals("ses_root", payloads[0].obj("info").obj("metadata").obj("varroLegacyImport").str("sourceSessionID"))
        val tool = payloads[1].arr("messages")!![0].asJsonObject.arr("content")!![0].asJsonObject
        assertEquals("shell", tool.str("name"))
        assertEquals("error", tool.obj("state").str("status"))
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(path))
    }

    @Test fun `failed tree import removes already-created copies`() {
        val path = fixture()
        val deleted = mutableListOf<String>()
        var created = 0
        val importer = LegacySessionImport(path) { method, route, _ -> when (method) {
            "GET" -> Json.obj("project" to Json.obj("id" to "project_test"))
            "POST" -> { if (++created == 2) error("Import failed"); Json.obj() }
            else -> { deleted.add(route); Json.obj() }
        } }
        assertThrows(IllegalStateException::class.java) { importer.importCopy(importer.list("/workspace").single()) }
        assertEquals(1, deleted.size)
        assertTrue(deleted[0].startsWith("/api/session/ses_"))
        assertFalse(deleted[0].contains("ses_root"))
    }
}
