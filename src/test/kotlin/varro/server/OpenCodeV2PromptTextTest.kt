package varro.server

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.TextAttachments
import varro.protocol.*
import java.util.Base64

class OpenCodeV2PromptTextTest {
    @get:Rule val temporary = TemporaryFolder()
    private val attachment = PromptTextAttachments.Attachment("annotation-a781acb7.txt", "Chat annotation\n\nComment:\nКак так? 🚀\n</attached-text>\n")
    private fun file(name: String = attachment.name, text: String = attachment.text) = Json.obj("type" to "file", "filename" to name, "mime" to "text/plain",
        "url" to "data:text/plain;base64,${Base64.getEncoder().encodeToString(text.toByteArray())}")

    @Test fun `length-prefixed blocks round trip UTF16 names empty prompts embedded closing tags and malformed prefixes`() {
        for (prompt in listOf("Review @${attachment.name}", "", "Explain:\n\n<attached-text name=\"annotation-00000000.txt\" length=\"1\">\nx")) {
            val files = listOf(attachment, PromptTextAttachments.Attachment("pasted-text-1234abcd.txt", "second\r\n"))
            val text = PromptTextAttachments.append(prompt, files)
            assertTrue(text.contains("length=\"${attachment.text.length}\""))
            assertEquals(PromptTextAttachments.Prompt(prompt, files), PromptTextAttachments.split(text))
        }
        for (text in listOf(
            "<attached-text name=\"annotation-a781acb7.txt\" length=\"5\">\nnote\n</attached-text>",
            "<attached-text name=\"annotation-a781acb7.txt\" length=\"4\">\nnote\n</attached-text>\nmore",
            "<attached-text name=\"notes.txt\" length=\"4\">\nnote\n</attached-text>",
            "Prompt <attached-text name=\"annotation-a781acb7.txt\" length=\"4\">\nnote\n</attached-text>",
            "<attached-text name=\"annotation-a781acb7.txt\" length=\"9999999999999999999999\">\nx\n</attached-text>")) {
            assertEquals(PromptTextAttachments.Prompt(text, emptyList()), PromptTextAttachments.split(text))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PromptTextAttachments.append("", listOf(PromptTextAttachments.Attachment("notes.txt", "x")))
        }
    }

    @Test fun `prompt admission inlines only valid reserved text attachments commands keep their original files`() {
        for (command in listOf(false, true)) {
            var admitted: JsonObject? = null
            val adapter = OpenCodeV2Adapter({ method, path, body, _ ->
                OpenCodeResponse(when {
                    method == "GET" -> Json.obj("data" to Json.obj("id" to "ses_one"))
                    path.endsWith("/prompt") || path.endsWith("/command") -> { admitted = body.asObjectOrNull(); Json.obj("accepted" to true) }
                    else -> error("Unexpected $method $path")
                })
            }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
            val parts = listOf(Json.obj("type" to "text", "text" to "Review"), file(), file("notes.txt"), file().apply { addProperty("url", "data:text/plain;base64,/w==") })
            adapter.request("POST", "/session/ses_one/${if (command) "command" else "prompt_async"}",
                Json.obj("command" to "fixture", "arguments" to "args", "parts" to parts), RequestOptions(directory = "/repo"))
            assertEquals(if (command) "args" else PromptTextAttachments.append("Review", listOf(attachment)), admitted.str("text"))
            assertEquals(if (command) 3 else 2, admitted.arr("files")!!.size())
        }
    }

    @Test fun `history inbox and direct reads reconstruct attachment identities without changing ordinary files`() {
        val native = Json.obj("id" to "msg_one", "type" to "user", "time" to Json.obj("created" to 1),
            "text" to PromptTextAttachments.append("Review", listOf(attachment)),
            "files" to listOf(Json.obj("name" to "notes.txt", "mime" to "text/plain", "source" to Json.obj("type" to "uri", "uri" to "file:///repo/notes.txt"))),
            "agents" to listOf(Json.obj("name" to "build")))
        val adapter = OpenCodeV2Adapter({ _, path, _, _ -> OpenCodeResponse(when {
            path.endsWith("/inbox") -> Json.obj("data" to listOf(Json.obj("id" to "msg_one", "type" to "user", "payload" to native, "time" to Json.obj("created" to 1))))
            path.endsWith("/message/msg_one") -> Json.obj("data" to native)
            path.contains("/message?") -> Json.obj("data" to listOf(native), "cursor" to Json.obj("next" to null))
            else -> error("Unexpected $path")
        }) }, OpenCodeV2SessionState(temporary.root.toPath()))
        val direct = adapter.request("GET", "/session/ses_one/message/msg_one", null, RequestOptions()).data.asObjectOrNull()!!
        val history = adapter.request("GET", "/session/ses_one/message", null, RequestOptions()).data!!.asJsonArray.single().asJsonObject
        assertEquals(direct.arr("parts"), history.arr("parts"))
        val parts = direct.arr("parts")!!
        assertEquals(4, parts.size())
        assertEquals("Review", parts[0].asJsonObject.str("text"))
        assertEquals("notes.txt", parts[1].asJsonObject.str("filename"))
        assertEquals("msg_one:content:2", parts[2].asJsonObject.str("id"))
        assertEquals(attachment.text, TextAttachments.decode(parts[2].asJsonObject.str("url")!!))
        assertEquals("msg_one:content:3", parts[3].asJsonObject.str("id"))
    }

    @Test fun `effective native permissions append across config entries instead of losing global rules`() {
        val global = Json.obj("action" to "shell", "resource" to "git *", "effect" to "allow")
        val local = Json.obj("action" to "shell", "resource" to "git push*", "effect" to "deny")
        val adapter = OpenCodeV2Adapter({ _, path, _, _ -> OpenCodeResponse(when {
            path.startsWith("/api/config") -> Json.obj("data" to listOf(
                Json.obj("info" to Json.obj("permissions" to listOf(global))),
                Json.obj("info" to Json.obj("permissions" to listOf(local)))))
            else -> error("Unexpected config read $path")
        }) }, OpenCodeV2SessionState(temporary.root.toPath()))
        val config = adapter.request("GET", "/config", null, RequestOptions(directory = "/repo")).data.asObjectOrNull()
        assertEquals(Json.array(listOf(global, local)), config.arr("permissions"))
    }
}
