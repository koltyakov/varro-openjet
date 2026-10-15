package varro.server

import com.google.gson.JsonElement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.util.concurrent.CancellationException

class OpenCodeV2SideQuestionsTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private fun adapter(wire: (String, String, JsonElement?, RequestOptions) -> JsonElement?) = OpenCodeV2Adapter(
        { method, path, body, options -> OpenCodeResponse(Json.obj("data" to wire(method, path, body, options))) },
        OpenCodeV2SessionState(temporary.newFolder().toPath()),
    )

    @Test fun `side questions use only canonical context and generation without changing active work`() {
        val calls = mutableListOf<String>()
        val history = Json.array(listOf(Json.obj("question" to "Choose a name", "answer" to "Maple 29")))
        val native = adapter { method, path, body, options ->
            options.checkCancelled()
            assertEquals(512 * 1024L, options.maxResponseBytes)
            assertTrue(options.cancellableBody)
            calls.add("$method $path")
            when (path) {
                "/api/session/ses_side" -> Json.obj("id" to "ses_side", "location" to Json.obj("directory" to "/repo"))
                "/api/session/ses_side/generate" -> {
                    val prompt = body.asObjectOrNull().str("prompt")!!
                    assertEquals(setOf("prompt"), body!!.asJsonObject.keySet())
                    assertTrue(prompt.contains(Json.stringify(history)))
                    assertTrue(prompt.contains("Do not call any tools and do not take any actions."))
                    assertTrue(prompt.contains("do not claim to read files"))
                    assertTrue(prompt.endsWith("\n\nWhat name?"))
                    Json.obj("text" to " **Maple 29** ")
                }
                else -> error("Must not inspect or mutate inbox, active work, permissions, forms or model: $method $path")
            }
        }
        assertEquals(Json.obj("text" to "**Maple 29**"), native.request("POST", "/session/ses_side/side-question",
            Json.obj("question" to " What name? ", "history" to history),
            RequestOptions(directory = "/repo", maxResponseBytes = 512 * 1024, cancellableBody = true)).data)
        assertEquals(listOf("GET /api/session/ses_side", "POST /api/session/ses_side/generate"), calls)
    }

    @Test fun `invalid questions history and aggregate context fail before wire requests`() {
        val native = adapter { _, _, _, _ -> error("Invalid input must not reach the server") }
        val turn = Json.obj("question" to "Why?", "answer" to "Because")
        val invalid = listOf(Json.obj(), Json.obj("question" to " "), Json.obj("question" to 1),
            Json.obj("question" to "x".repeat(8_001)), Json.obj("question" to "Why?", "model" to "other")) +
            listOf(null, "bad", listOf(Json.obj("question" to "Why?")), listOf(Json.obj("question" to "Why?", "answer" to 1)),
                listOf(Json.obj("question" to "Why?", "answer" to "", "extra" to true)), List(12) { turn },
                listOf(Json.obj("question" to "x".repeat(8_001), "answer" to "a")),
                listOf(Json.obj("question" to "Why?", "answer" to "x".repeat(64_001))),
                List(3) { Json.obj("question" to "Why?", "answer" to "x".repeat(43_000)) })
                .map { Json.obj("question" to "Why?", "history" to it) }
        for (body in invalid) assertThrows(IllegalArgumentException::class.java) {
            native.request("POST", "/session/ses_side/side-question", body, RequestOptions())
        }
    }

    @Test fun `canonical id and location must still match before generation`() {
        for (canonical in listOf(null, Json.obj("id" to "other", "location" to Json.obj("directory" to "/repo")),
            Json.obj("id" to "ses_side"), Json.obj("id" to "ses_side", "location" to Json.obj("directory" to "/foreign")))) {
            val native = adapter { method, path, _, _ ->
                assertEquals("GET", method)
                assertEquals("/api/session/ses_side", path)
                canonical
            }
            assertTrue(assertThrows(IllegalStateException::class.java) {
                native.request("POST", "/session/ses_side/side-question?directory=%2Frepo", Json.obj("question" to "Why?"), RequestOptions())
            }.message!!.contains("location changed"))
        }
        for (directory in listOf("c:\\Repo\\", "C:/repo")) {
            val native = adapter { _, path, _, _ -> if (path.endsWith("/generate")) Json.obj("text" to "Answer")
                else Json.obj("id" to "ses_side", "location" to Json.obj("directory" to directory)) }
            native.request("POST", "/session/ses_side/side-question", Json.obj("question" to "Why?"), RequestOptions(directory = "C:/Repo"))
        }
    }

    @Test fun `empty oversized and unsupported answers never retry or create a helper`() {
        for (text in listOf(null, "", "  ", "x".repeat(64_001), "unsupported")) {
            var posts = 0
            val native = adapter { method, path, _, _ ->
                if (method == "GET") Json.obj("id" to "ses_side", "location" to Json.obj("directory" to "/repo"))
                else {
                    assertEquals("/api/session/ses_side/generate", path)
                    posts++
                    if (text == "unsupported") error("404 Unsupported endpoint")
                    Json.obj("text" to text)
                }
            }
            assertThrows(IllegalStateException::class.java) {
                native.request("POST", "/session/ses_side/side-question", Json.obj("question" to "Why?"), RequestOptions())
            }
            assertEquals(1, posts)
        }
    }

    @Test fun `cancellation before during and after generation never interrupts the task`() {
        for (cancelAt in 0..2) {
            var calls = 0
            val native = adapter { _, path, _, _ ->
                calls++
                if (path.endsWith("/generate")) Json.obj("text" to "Answer")
                else Json.obj("id" to "ses_side", "location" to Json.obj("directory" to "/repo"))
            }
            assertThrows(CancellationException::class.java) {
                native.request("POST", "/session/ses_side/side-question", Json.obj("question" to "Why?"),
                    RequestOptions(isCancelled = { calls >= cancelAt }))
            }
            assertEquals(cancelAt, calls)
        }
    }

    @Test fun `side question bounds match the vendored shared contract`() {
        val source = java.io.File("webview/vendor/shared/side-question.ts").readText()
        for ((name, value) in mapOf("MAX_SIDE_QUESTION_LENGTH" to OpenCodeV2SideQuestions.MAX_QUESTION_LENGTH,
            "MAX_SIDE_QUESTION_ANSWER_LENGTH" to OpenCodeV2SideQuestions.MAX_ANSWER_LENGTH,
            "MAX_SIDE_QUESTION_TURNS" to OpenCodeV2SideQuestions.MAX_TURNS,
            "MAX_SIDE_QUESTION_CONTEXT_LENGTH" to OpenCodeV2SideQuestions.MAX_CONTEXT_LENGTH,
            "SIDE_QUESTION_TIMEOUT_MS" to OpenCodeV2SideQuestions.TIMEOUT_MS)) {
            val declared = Regex("const $name = ([0-9_]+);").find(source)!!.groupValues[1].replace("_", "").toLong()
            assertEquals(name, value.toLong(), declared)
        }
    }

    @Test fun `boundary sized questions answers and eleven previous turns are accepted without truncation`() {
        val question = "q".repeat(8_000)
        val answer = "a".repeat(64_000)
        assertTrue(OpenCodeV2SideQuestions.prompt(Json.obj("question" to question,
            "history" to listOf(Json.obj("question" to question, "answer" to answer)))).endsWith(question))
        assertEquals(answer, OpenCodeV2SideQuestions.answer(Json.obj("text" to answer)).str("text"))
        val history = List(11) { Json.obj("question" to "Why?", "answer" to "Because.") }
        assertTrue(OpenCodeV2SideQuestions.prompt(Json.obj("question" to "Why?", "history" to history)).contains(Json.stringify(history)))
    }
}
