package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class QueuedDispatchesTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `queued follow-up title survives restart and write-ahead admission without changing the prompt`() {
        val path = temporary.root.toPath().resolve("queue.json")
        val item = Json.obj("id" to "item", "sessionId" to "s", "messageId" to "msg_fixture", "text" to "Run only the parser tests",
            "followupTitle" to "Test parser", "ownerViewId" to "editor", "droppedFiles" to emptyList<Any>(), "clipboardImages" to emptyList<Any>())
        val queue = QueuedDispatches(path, Json.array(emptyList<Any>()))
        queue.update("editor", Json.array(listOf(item)))
        val restored = QueuedDispatches(path, Json.array(emptyList<Any>()))
        assertEquals(item, restored.messages().single())
        val lease = restored.claim("editor", "s", "item", false)!!
        assertTrue(restored.admit("editor", Json.obj("method" to "POST", "path" to "/session/s/prompt_async",
            "queuedMessageDispatch" to Json.obj("itemId" to "item", "lease" to lease),
            "body" to Json.obj("messageID" to "msg_fixture", "followupTitle" to "Test parser"))))
        val admitted = QueuedDispatches(path, Json.array(emptyList<Any>())).messages().single().asJsonObject
        assertEquals("Test parser", admitted.str("followupTitle"))
        assertEquals("Run only the parser tests", admitted.str("text"))
    }

    @Test fun `invalid queued follow-up labels reject the update without overwriting durable messages`() {
        val path = temporary.root.toPath().resolve("queue.json")
        val queue = QueuedDispatches(path, Json.array(emptyList<Any>()))
        val valid = Json.obj("id" to "item", "sessionId" to "s", "text" to "Prompt")
        queue.update("sidebar", Json.array(listOf(valid)))
        for (label in listOf("", "x".repeat(41), "two\nlines", "unsafe\u202e")) {
            try { queue.update("sidebar", Json.array(listOf(valid.deepCopy().apply { addProperty("followupTitle", label) }))); fail("Expected invalid label") }
            catch (_: IllegalArgumentException) { }
            assertEquals(Json.array(listOf(valid)), QueuedDispatches(path, Json.array(emptyList<Any>())).messages())
        }
    }
}
