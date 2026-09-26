package varro.database

import org.junit.Assert.*
import org.junit.Test

class DatabaseEnvironmentTest {
    private val demo = DatabaseEnvironment.Source("demo-id", "Demo SQLite", "SQLite", true, schemas = listOf("main"))
    private val other = DatabaseEnvironment.Source("other-id", "Other SQLite", "SQLite", true)

    @Test fun `empty editor still identifies the sole configured database and its loaded schemas`() {
        val result = DatabaseEnvironment.snapshot(listOf(demo), emptyList(), "explorer")!!
        assertEquals("demo-id", result["activeDataSourceId"].asString)
        assertEquals("Demo SQLite", result["dataSource"].asString)
        assertEquals("only-datasource", result["activeDataSourceReason"].asString)
        assertEquals("main", result.getAsJsonArray("dataSources")[0].asJsonObject.getAsJsonArray("schemas")[0].asString)
    }

    @Test fun `selected database wins over the other open connection`() {
        val result = DatabaseEnvironment.snapshot(listOf(demo, other), listOf(other.id), "explorer")!!
        assertEquals(other.id, result["activeDataSourceId"].asString)
        assertEquals("explorer", result["activeDataSourceReason"].asString)
        assertEquals(2, result.getAsJsonArray("dataSources").size())
    }

    @Test fun `multiple open or selected databases do not invent a current connection`() {
        for (selection in listOf(emptyList(), listOf(demo.id, other.id))) {
            val result = DatabaseEnvironment.snapshot(listOf(demo, other), selection, "explorer")!!
            assertTrue(result["activeDataSourceId"].isJsonNull)
            assertTrue(result["connection"].isJsonNull)
            assertEquals(2, result["dataSourceCount"].asInt)
        }
    }

    @Test fun `removed selection falls back to the only connected datasource`() {
        val result = DatabaseEnvironment.snapshot(listOf(demo, other.copy(connected = false)), listOf("removed"), "explorer")!!
        assertEquals(demo.id, result["activeDataSourceId"].asString)
        assertEquals("only-connected-datasource", result["activeDataSourceReason"].asString)
    }

    @Test fun `inventory stays bounded while retaining the current source`() {
        val sources = (1..100).map { demo.copy(id = "id-$it", name = "name-$it", schemas = List(100) { "x".repeat(500) }) }
        val result = DatabaseEnvironment.snapshot(sources, listOf("id-100"), "console")!!
        assertTrue(result["truncated"].asBoolean)
        assertEquals(100, result["dataSourceCount"].asInt)
        assertEquals("id-100", result.getAsJsonArray("dataSources")[0].asJsonObject["id"].asString)
        assertTrue(result.getAsJsonArray("dataSources").toString().length <= 41_000)
        assertNull(DatabaseEnvironment.snapshot(emptyList(), emptyList(), "explorer"))
    }
}
