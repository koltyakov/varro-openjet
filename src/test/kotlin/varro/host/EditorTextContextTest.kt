package varro.host

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.bool
import varro.protocol.str

class EditorTextContextTest {
    @Test fun `saved selections use line references instead of inline editor text`() {
        val document = DocumentImpl("first\nsecond\nthird\n")
        assertNull(ContextProvider.editorTextSlice(document, TextRange(6, 13), unsaved = false))
        assertNull(ContextProvider.editorTextSlice(document, null, unsaved = false))
    }

    @Test fun `unsaved selections retain exact text and exclude the next line at column zero`() {
        val document = DocumentImpl("first\nsecond\nthird\n")
        val snapshot = ContextProvider.editorTextSlice(document, TextRange(6, 13), unsaved = true)!!
        assertEquals("selection", snapshot.str("kind"))
        assertEquals("second\n", snapshot.str("text"))
        assertEquals(Json.obj("startLine" to 2, "endLine" to 2), snapshot.getAsJsonObject("range"))
        assertEquals(false, snapshot.bool("truncated"))

        val partial = ContextProvider.editorTextSlice(document, TextRange(8, 15), unsaved = true)!!
        assertEquals("cond\nth", partial.str("text"))
        assertEquals(Json.obj("startLine" to 2, "endLine" to 3), partial.getAsJsonObject("range"))
    }

    @Test fun `unsaved buffers remain inline when no text is selected`() {
        val document = DocumentImpl("changed\n")
        val snapshot = ContextProvider.editorTextSlice(document, null, unsaved = true)!!
        assertEquals("dirty-buffer", snapshot.str("kind"))
        assertEquals("changed\n", snapshot.str("text"))
        assertEquals(Json.obj("startLine" to 1, "endLine" to 2), snapshot.getAsJsonObject("range"))
        assertNull(ContextProvider.editorTextSlice(DocumentImpl(""), null, unsaved = true))
    }

    @Test fun `unsaved selection snapshots remain bounded`() {
        val document = DocumentImpl("x".repeat(100_001))
        val snapshot = ContextProvider.editorTextSlice(document, TextRange(0, document.textLength), unsaved = true)!!
        assertEquals(100_000, snapshot.str("text")!!.length)
        assertEquals(true, snapshot.bool("truncated"))
    }
}
