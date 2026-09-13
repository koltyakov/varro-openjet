package varro.database

import org.junit.Assert.*
import org.junit.Test

class DatabaseSnapshotTest {
    private fun snapshot(rows: Sequence<List<Any?>>, count: Int = 1, ddl: String? = null, ddlEditor: Boolean = false) = DatabaseSnapshot.capture(
        "public.orders", "local", "PostgreSQL", "", listOf("id" to "bigint", "id" to "text"),
        count, rows, false, false, 0, ddl, ddlEditor,
    )

    @Test fun `DDL editor captures the whole definition with no row selection`() {
        val ddl = "create table users (id INT primary key, name VARCHAR(50), city VARCHAR(50));"
        val result = snapshot(emptySequence(), 0, ddl, true)
        assertEquals("ddl", result["scope"].asString)
        assertEquals(ddl, result["ddl"].asString)
        assertFalse(result["truncated"].asBoolean)
    }

    @Test fun `row snapshots include bounded DDL alongside values`() {
        val result = snapshot(sequenceOf(listOf(1, "Alice")), ddl = "x".repeat(DatabaseSnapshot.MAX_DDL + 1))
        assertEquals("selected-rows", result["scope"].asString)
        assertEquals(DatabaseSnapshot.MAX_DDL, result["ddl"].asString.length)
        assertEquals("Alice", result.getAsJsonArray("rows")[0].asJsonArray[1].asString)
        assertTrue(result["truncated"].asBoolean)
    }

    @Test fun `preserves null duplicate columns and large integers`() {
        val result = snapshot(sequenceOf(listOf(9007199254740993L, null)))
        assertEquals("9007199254740993", result.getAsJsonArray("rows")[0].asJsonArray[0].asString)
        assertTrue(result.getAsJsonArray("rows")[0].asJsonArray[1].isJsonNull)
        assertEquals(2, result.getAsJsonArray("columns").size())
    }

    @Test fun `empty selection contains metadata without reading rows`() {
        val result = snapshot(sequence { error("An empty selection must not read any rows") }, 0)
        assertEquals("table", result["scope"].asString)
        assertEquals(0, result.getAsJsonArray("rows").size())
    }

    @Test fun `caps rows cell lengths and serialized size`() {
        val result = snapshot(generateSequence { listOf<Any?>("x".repeat(5000), "y".repeat(5000)) }, 500)
        assertTrue(result["truncated"].asBoolean)
        assertTrue(result.getAsJsonArray("rows").size() < 200)
        assertTrue(result.getAsJsonArray("rows").toString().length < 81000)
        assertEquals(4000, result.getAsJsonArray("rows")[0].asJsonArray[0].asString.length)
        assertEquals(500, result["selectedRowCount"].asInt)
    }

    @Test fun `does not materialize unknown driver values`() {
        val value = object { override fun toString(): String = error("must not load") }
        val result = snapshot(sequenceOf(listOf(value, byteArrayOf(1, 2))))
        assertEquals("[value not materialized]", result.getAsJsonArray("rows")[0].asJsonArray[0].asString)
        assertEquals("[binary: 2 bytes]", result.getAsJsonArray("rows")[0].asJsonArray[1].asString)
    }
}
