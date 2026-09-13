package varro.settings

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class SharedCoreSettingsTest {
    @Test fun `shares model and core choices while preserving destination appearance`() {
        val source = VarroSettings().apply {
            commitMessageModel = "provider/commit"
            chatAutoApproveModel = "provider/judge"
            chatDefaultPermissionMode = "default"
            chatFontSize = 22
            chatShowFileDiffs = true
            webviewOffscreenRendering = true
        }
        val destination = VarroSettings().apply { chatFontSize = 14 }
        val shared = SharedCoreSettings.snapshot(source)
        assertFalse(shared.has("chatFontSize"))
        assertFalse(shared.has("webviewOffscreenRendering"))
        SharedCoreSettings.apply(shared, destination)
        assertEquals("provider/commit", destination.commitMessageModel)
        assertEquals("provider/judge", destination.chatAutoApproveModel)
        assertEquals("default", destination.chatDefaultPermissionMode)
        assertEquals(14, destination.chatFontSize)
        assertFalse(destination.chatShowFileDiffs)
        assertFalse(destination.webviewOffscreenRendering)
    }

    @Test fun `invalid values do not partially update settings`() {
        val settings = VarroSettings()
        val before = SharedCoreSettings.snapshot(settings)
        val invalid = before.deepCopy().apply {
            addProperty("serverPort", 5000)
            addProperty("chatAutoCompact", "not a boolean")
        }
        assertThrows(IllegalArgumentException::class.java) { SharedCoreSettings.apply(invalid, settings) }
        assertEquals(before, SharedCoreSettings.snapshot(settings))
    }

    @Test fun `partial documents preserve missing and ignore unknown or local fields`() {
        val settings = VarroSettings().apply { chatFontSize = 17 }
        SharedCoreSettings.apply(Json.obj("commitMessageModel" to "provider/commit", "chatFontSize" to 99, "future" to true), settings)
        assertEquals("provider/commit", settings.commitMessageModel)
        assertEquals(4096, settings.serverPort)
        assertEquals(17, settings.chatFontSize)
    }
}
