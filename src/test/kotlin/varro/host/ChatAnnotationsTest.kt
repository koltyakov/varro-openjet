package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import java.util.Base64

class ChatAnnotationsTest {
    private fun part(text: String) = Json.obj("type" to "file", "id" to "part", "sessionID" to "session", "messageID" to "message",
        "mime" to "text/plain", "filename" to "annotation-abcdef12.txt", "url" to "data:text/plain;base64,${Base64.getEncoder().encodeToString(text.toByteArray())}")

    @Test fun `history keeps bounded annotation context before deferring the original`() {
        val original = part("Chat annotation\n\nSource session: source\n\nSelected text:\n> 雪 and café\n> Comment:\n> still quoted\n\nComment:\n${"note".repeat(500)}")
        val projected = MessageContent.project(original, "/repo")
        assertTrue(projected.str("url")!!.startsWith("varro-content:"))
        assertEquals("雪 and café\nComment:\nstill quoted", projected.obj("annotation").str("selectedText"))
        assertEquals(1200, projected.obj("annotation").str("note")!!.length)
        assertEquals("source", projected.obj("annotation").str("sourceSessionId"))
        assertTrue(original.str("url")!!.startsWith("data:"))
        assertFalse(original.has("annotation"))
    }

    @Test fun `ordinary pasted text and malformed annotations never gain annotation attribution`() {
        val valid = "Chat annotation\n\nSelected text:\n> passage\n\nComment:\nnote"
        assertNotNull(ChatAnnotations.preview(part(valid)))
        for (text in listOf("ordinary file", "Chat annotation\n\nSelected text:\n> text", "Chat annotation\n\nSelected text:\n> text\n\nComment:\n ", valid + "x".repeat(65536))) {
            assertNull(ChatAnnotations.preview(part(text)))
        }
        assertNull(ChatAnnotations.preview(part(valid).apply { addProperty("filename", "pasted-text-abcdef12.txt") }))
        assertNull(ChatAnnotations.preview(part(valid).apply { addProperty("mime", "image/png") }))
    }

    @Test fun `Unicode percent encoding retains the full shared byte allowance and rejects invalid UTF-8`() {
        val text = "Chat annotation\n\nSelected text:\n> passage\n\nComment:\n${"雪".repeat(20_000)}"
        val url = "data:text/plain;charset=utf-8," + java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
        assertTrue(url.length > 96 * 1024)
        assertNotNull(ChatAnnotations.preview(part(text).apply { addProperty("url", url) }))
        assertEquals(text, TextAttachments.decode(url))
        assertEquals("literal+plus", TextAttachments.decode("data:text/plain;charset=utf-8,literal+plus"))
        assertNull(TextAttachments.decode("data:text/plain;charset=utf-8,%FF"))
        assertNull(TextAttachments.decode("data:text/plain;base64,/w=="))
        assertNull(TextAttachments.decode("data:text/plain-not-really;charset=utf-8,text"))
    }
}
