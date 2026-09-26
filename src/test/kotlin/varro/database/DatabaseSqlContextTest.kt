package varro.database

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import org.junit.Assert.*
import org.junit.Test

class DatabaseSqlContextTest {
    @Test fun `selection overrides the caret statement and excludes a trailing empty line`() {
        val text = "select 1;\nselect 'a;b';\nselect 2;\nselect 3;"
        val start = text.indexOf("select 'a;b'")
        val end = text.indexOf("select 3")
        val sql = DatabaseSqlContext.editorSql(DocumentImpl(text), TextRange(start, end), TextRange(end, text.length), 4)
        assertEquals("selection", sql["kind"].asString)
        assertEquals("select 'a;b';\nselect 2;\n", sql["text"].asString)
        assertEquals(2, sql["startLine"].asInt)
        assertEquals(3, sql["endLine"].asInt)
    }

    @Test fun `unparsed documents retain the entire current buffer`() {
        val sql = DatabaseSqlContext.editorSql(DocumentImpl("select 'changed';"), null, null, 1)
        assertEquals("buffer", sql["kind"].asString)
        assertEquals("select 'changed';", sql["text"].asString)
    }

    @Test fun `empty console has a valid one based range`() {
        val sql = DatabaseSqlContext.editorSql(DocumentImpl(""), null, null, 1)
        assertEquals("", sql["text"].asString)
        assertEquals(1, sql["startLine"].asInt)
        assertEquals(1, sql["endLine"].asInt)
        assertFalse(sql["truncated"].asBoolean)
    }

    @Test fun `large selections retain original range while bounding the captured SQL`() {
        val text = "select '" + "x".repeat(45_000) + "';\nselect 2;"
        val sql = DatabaseSqlContext.editorSql(DocumentImpl(text), TextRange(0, text.length), null, 1)
        assertEquals(40_000, sql["text"].asString.length)
        assertTrue(sql["truncated"].asBoolean)
        assertEquals("selection", sql["kind"].asString)
        assertEquals(2, sql["endLine"].asInt)
    }
}
