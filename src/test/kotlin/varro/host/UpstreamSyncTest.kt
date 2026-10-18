package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class UpstreamSyncTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `native recovery suppresses only automatic V2 prompts`() {
        for (route in listOf("prompt_async", "message", "command")) {
            assertTrue(RestProxy.skipNativeRecovery("POST", "/session/s/$route?directory=repo", true, 2))
            assertFalse(RestProxy.skipNativeRecovery("POST", "/session/s/$route", false, 2))
            assertFalse(RestProxy.skipNativeRecovery("POST", "/session/s/$route", true, 1))
        }
        assertFalse(RestProxy.skipNativeRecovery("POST", "/session/s/abort", true, 2))
    }

    @Test fun `child routes enforce methods and directory query allowlist`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/session/child/child-task-controls?directory=repo"))
        assertFalse(ApiRoutes.isAllowed("POST", "/session/child/child-task-controls"))
        for (action in listOf("interrupt-child", "background-parent")) {
            assertTrue(ApiRoutes.isAllowed("POST", "/session/child/$action"))
            assertFalse(ApiRoutes.isAllowed("GET", "/session/child/$action"))
            assertFalse(ApiRoutes.isAllowed("POST", "/session/child/$action?resume=true"))
        }
    }

    @Test fun `native picker preserves mixed attachment order and rejects corrupt PDFs without text fallback`() {
        val root = temporary.newFolder().toPath()
        val first = Files.writeString(root.resolve("first.txt"), "file")
        val pdf = Files.writeString(root.resolve("test.pdf"), "%PDF-fixture")
        val last = Files.writeString(root.resolve("last.txt"), "file")
        val corrupt = Files.writeString(root.resolve("corrupt.pdf"), "not pdf")
        val messages = mutableListOf<Pair<String, com.google.gson.JsonElement>>()
        assertTrue(AttachmentStore(root.resolve("stored")) { root.toString() }.pick(listOf(first, pdf, last, corrupt).map { it.toString() }) { type, data -> messages += type to data })
        assertEquals(listOf("files/dropped", "pdfs/picked", "files/dropped"), messages.map { it.first })
        assertEquals("test.pdf", messages[1].second.asJsonArray.single().asJsonObject.str("filename"))
    }
}
