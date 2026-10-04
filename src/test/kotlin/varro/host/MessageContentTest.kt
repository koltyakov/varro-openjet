package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeV2Projection
import java.util.concurrent.CancellationException

class MessageContentTest {
    private fun file() = Json.obj("id" to "part:1", "messageID" to "msg_one", "sessionID" to "ses_one",
        "type" to "file", "mime" to "image/png", "filename" to "shot.png", "url" to ImageThumbnailsTest.image(800, 450))

    @Test fun `history transfers references and on-demand reads return thumbnails or originals`() {
        val file = file()
        val original = Json.obj("info" to Json.obj("id" to "msg_one"), "parts" to listOf(file))
        val projected = MessageContent.messages(Json.array(listOf(original)), "/workspace with spaces")!!.asJsonArray[0].asJsonObject
        val reference = projected.arr("parts")!![0].asJsonObject.str("url")!!
        assertTrue(reference.startsWith("varro-content:/session/ses_one/message/msg_one/part/part%3A1?directory=%2Fworkspace%20with%20spaces"))
        assertEquals("shot.png", projected.arr("parts")!![0].asJsonObject.str("filename"))
        assertTrue(projected.toString().length < 500)
        assertTrue(file.str("url")!!.startsWith("data:"))
        val thumbnails = ImageThumbnails()
        try {
            var requests = 0
            val reader = MessageContentReader(thumbnails) { path, options ->
                requests++
                assertEquals("/session/ses_one/message/msg_one", path)
                assertEquals("/workspace with spaces", options.directory)
                original
            }
            val path = reference.removePrefix("varro-content:")
            fun read(suffix: String = "", server: String = "server") = reader.read(ApiRoutes.parse("GET", path + suffix)!!,
                "/workspace with spaces", server) { false }.asJsonObject
            val preview = read("&view=thumbnail")
            assertNotEquals(file.str("url"), preview.str("url"))
            assertTrue(preview.str("url")!!.startsWith("data:image/png;base64,"))
            assertEquals(preview, read("&view=thumbnail"))
            assertEquals(1, requests)
            read("&view=thumbnail", "other-server")
            assertEquals(2, requests)
            assertEquals(file, read())
            assertEquals(3, requests)
            assertThrows(CancellationException::class.java) {
                reader.read(ApiRoutes.parse("GET", path)!!, "/workspace with spaces", "server") { true }
            }
            assertEquals(3, requests)
        } finally { thumbnails.dispose() }
    }

    @Test fun `v2 tool attachment aliases do not retain original image bodies in history`() {
        val url = file().str("url")!!
        val native = Json.obj("id" to "msg_one", "type" to "assistant", "content" to listOf(Json.obj(
            "type" to "tool", "id" to "call_one", "name" to "browser", "state" to Json.obj("status" to "completed", "input" to Json.obj(),
                "content" to listOf(Json.obj("type" to "file", "uri" to url, "mime" to "image/png", "name" to "shot.png"))))))
        val message = OpenCodeV2Projection.message(native, "ses_one", "/repo")
        val original = MessageContent.find(message, "ses_one", "msg_one", "call_one:file:0")!!
        assertEquals(url, original.str("url"))
        assertNull(MessageContent.find(message, "another-session", "msg_one", "call_one:file:0"))
        val projected = MessageContent.messages(Json.array(listOf(message)), "/repo")!!
        assertFalse(projected.toString().contains(url))
        assertTrue(projected.toString().contains("varro-content:"))
        assertEquals(url, MessageContent.find(message, "ses_one", "msg_one", "call_one:file:0").str("url"))
    }

    @Test fun `completed reasoning and tool bodies defer while preserving live text and full search counts`() {
        val reasoning = Json.obj("id" to "reasoning", "sessionID" to "ses_one", "messageID" to "msg_one", "type" to "reasoning",
            "text" to "detail ".repeat(10_000), "time" to Json.obj("start" to 1))
        assertSame(reasoning, MessageContent.project(reasoning, "/repo"))
        reasoning.obj("time")!!.addProperty("end", 2)
        assertEquals(512, MessageContent.project(reasoning, "/repo").str("text")!!.length)
        val tool = file().apply {
            addProperty("type", "tool"); remove("url"); addProperty("tool", "glob")
            add("state", Json.obj("status" to "completed", "input" to Json.obj("path" to "/repo"),
                "output" to (1..100).joinToString("\n") { "src/file-$it.kt" }, "metadata" to Json.obj()))
        }
        val projected = MessageContent.project(tool, "/repo")
        assertNotNull(projected.str("deferred"))
        assertEquals(100, projected.obj("state").obj("metadata").int("matches"))
        assertEquals("/repo", projected.obj("state").obj("input").str("path"))
        assertTrue(tool.obj("state").str("output")!!.length > 512)
    }

    @Test fun `streamed tool images and duplicate native aliases stay out of browser events`() {
        val projector = StreamingToolContent()
        val properties = Json.obj("sessionID" to "ses_one", "assistantMessageID" to "msg_one", "callID" to "call_one", "name" to "browser",
            "content" to listOf(Json.obj("type" to "file", "uri" to file().str("url")), Json.obj("type" to "text", "text" to "text ".repeat(1000))),
            "state" to Json.obj("image" to file().str("url")), "metadata" to Json.obj("image" to file().str("url")))
        val event = Json.obj("id" to "evt_one", "seq" to 7, "workspaceDirectory" to "/repo", "type" to "session.next.tool.success", "properties" to properties)
        val projected = projector.project(event)
        assertEquals("evt_one", projected.str("id"))
        assertEquals(7, projected.int("seq"))
        assertTrue(projected.toString().length < 1500)
        assertFalse(projected.toString().contains("base64"))
        assertNotNull(projected.obj("properties").str("deferred"))
        assertTrue(event.toString().contains("base64"))
    }
}
