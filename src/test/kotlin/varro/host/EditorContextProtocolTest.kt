package varro.host

import org.junit.Assert.assertEquals
import org.junit.Test
import varro.protocol.Json

class EditorContextProtocolTest {
    @Test fun `context updates preserve full snapshots when opening switching and closing files`() {
        val first = Json.obj("path" to "/project/.gitattributes", "relativePath" to ".gitattributes", "language" to "text")
        val second = Json.obj("path" to "/project/Dockerfile", "relativePath" to "Dockerfile", "language" to "dockerfile")
        val range = Json.obj("startLine" to 9, "endLine" to 14)
        val text = Json.obj(
            "kind" to "selection", "path" to "/project/.gitattributes",
            "relativePath" to ".gitattributes", "language" to "text",
            "range" to range, "text" to "selected text", "truncated" to false,
        )
        val snapshots = listOf(
            Triple(first, null, null),
            Triple(first, range, text),
            Triple(second, null, null),
            Triple(second, range, text.deepCopy().apply {
                addProperty("path", "/project/Dockerfile")
                addProperty("relativePath", "Dockerfile")
            }),
            Triple(second, null, null),
            Triple(null, null, null),
        )
        snapshots.forEach { (file, selection, editorText) ->
            val message = Json.message("context/update", Json.obj(
                "workspacePath" to "/project", "activeFile" to file,
                "selection" to selection, "editorText" to editorText,
                "diagnostics" to emptyList<Any>(),
            ))
            assertEquals(message, Json.parse(Json.stringifyMessage(message)))
        }
    }

    @Test fun `context without a workspace keeps its required null fields`() {
        val message = Json.message("context/update", Json.obj(
            "workspacePath" to null, "activeFile" to null, "selection" to null,
            "editorText" to null, "diagnostics" to emptyList<Any>(),
        ))
        assertEquals(message, Json.parse(Json.stringifyMessage(message)))
    }
}
