package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class VarroChatFileTypeTest {
    @Test fun `new and restored chat files retain their branded file type after renaming`() {
        val new = VarroChatFile("project", "new", Json.obj(), "Varro Chat")
        val restored = VarroChatFile("project", "restored", Json.obj("sessionId" to "ses_fixture"), "Restored title")
        for (file in listOf(new, restored)) {
            assertSame(VarroChatFileType, file.fileType)
            assertTrue(file.fileType.isBinary)
            assertEquals("Varro Chat", file.fileType.name)
            file.rename(null, "Renamed session")
            assertSame(VarroChatFileType, file.fileType)
            assertEquals("Renamed session", file.name)
        }
    }

    @Test fun `chat tab icons have light and dark variants at native editor size`() {
        for (name in listOf("varro-chat.svg", "varro-chat_dark.svg")) {
            val svg = VarroChatFileType::class.java.getResourceAsStream("/icons/$name")!!.bufferedReader().use { it.readText() }
            assertTrue(svg.contains("width=\"16\" height=\"16\""))
            assertTrue(svg.contains("<circle"))
        }
        assertNotNull(VarroChatFileType.icon)
    }
}
