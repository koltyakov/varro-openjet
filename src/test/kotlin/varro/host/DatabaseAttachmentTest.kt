package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.Json
import java.nio.file.Files
import java.nio.file.Path

class DatabaseAttachmentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `table snapshots become durable files independently of subsequent schema changes`() {
        val store = AttachmentStore(temporary.root.toPath()) { null }
        val snapshot = Json.obj("name" to "main.users", "ddl" to "create table users (id INT primary key)")
        val file = store.store(AttachmentStore.databaseContent(snapshot))
        snapshot.addProperty("ddl", "changed after drop")
        val stored = Json.parse(Files.readString(Path.of(file["path"].asString))).asJsonObject
        assertEquals("create table users (id INT primary key)", stored["ddl"].asString)
        assertEquals("file", file["type"].asString)
        assertEquals("main.users-context.json", file["relativePath"].asString)
        assertEquals("main.users", file.getAsJsonObject("database")["name"].asString)
        assertEquals("table", file.getAsJsonObject("database")["scope"].asString)
        assertEquals(0, file.getAsJsonObject("database")["rowCount"].asInt)
    }

    @Test fun `selected rows retain table display metadata on stored attachments`() {
        val store = AttachmentStore(temporary.root.toPath()) { null }
        val snapshot = Json.obj("name" to "users", "dataSource" to "Demo SQLite", "scope" to "selected-rows",
            "rows" to listOf(listOf("1"), listOf("2")), "selectedRowCount" to 2)
        val content = AttachmentStore.databaseContent(snapshot)
        val file = store.store(content)
        content.getAsJsonObject("database").addProperty("name", "changed")
        assertEquals("users", file.getAsJsonObject("database")["name"].asString)
        assertEquals(2, file.getAsJsonObject("database")["rowCount"].asInt)
    }
}
