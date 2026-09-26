package varro.database

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import java.lang.reflect.Proxy

class DatabaseDetailsTest {
    private inline fun <reified T> proxy(crossinline value: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> value(method.name) } as T

    @Test fun `connection uses the active session namespace without accessing connection secrets`() {
        val source = proxy<DbDataSource> { method ->
            when (method) { "getUniqueId" -> "staging-id"; else -> error("Unexpected datasource access: $method") }
        }
        val point = proxy<DatabaseConnectionPoint> { method ->
            when (method) { "isReadOnly" -> true; "isAutoCommit" -> false; else -> error("Unexpected connection access: $method") }
        }
        val session = proxy<DatabaseSession> { method ->
            when (method) { "getConnectionPoint" -> point; "isConnected" -> false; else -> error("Unexpected session access: $method") }
        }
        val path = ObjectPath.ROOT.append("app", ObjectKind.DATABASE).append("public", ObjectKind.SCHEMA)
        val result = DatabaseDetails.connection(source, session, SearchPath.of(path))!!
        assertEquals("staging-id", result["dataSourceId"].asString)
        assertEquals("app", result["catalog"].asString)
        assertEquals("public", result["schema"].asString)
        assertFalse(result["connected"].asBoolean)
        assertTrue(result["readOnly"].asBoolean)
        assertFalse(result["autoCommit"].asBoolean)
    }

    @Test fun `selected datasource does not pretend to identify a live session`() {
        val source = proxy<DbDataSource> { method ->
            when (method) { "getUniqueId" -> "ds-1"; else -> error("Unexpected datasource access: $method") }
        }
        val result = DatabaseDetails.connection(source)!!
        assertTrue(result["connected"].isJsonNull)
        assertTrue(result["schema"].isJsonNull)
        assertFalse(result.has("autoCommit"))
        assertNull(DatabaseDetails.connection(null))
    }

    @Test fun `wide table metadata keeps identity and usable columns within the shared budget`() {
        val detail = Json.obj(
            "name" to "app.public.events", "kind" to "table", "catalog" to "app", "schema" to "public",
            "dataSourceId" to "staging", "comment" to "c".repeat(1_000), "truncated" to false,
            "columns" to (1..64).map { Json.obj(
                "name" to "column_$it", "type" to "varchar(100)", "nullable" to false, "primaryKey" to false,
                "default" to "x".repeat(1_000), "comment" to "y".repeat(1_000),
            ) },
            "primaryKey" to listOf("column_1"), "foreignKeys" to emptyList<Any>(), "indexes" to emptyList<Any>(),
        )
        val result = DatabaseDetails.fitObject(detail, 7_500)
        assertTrue(result.toString().length <= 7_500)
        assertEquals("app.public.events", result["name"].asString)
        assertTrue(result["truncated"].asBoolean)
        assertTrue(result.getAsJsonArray("columns").size() > 0)
    }
}
