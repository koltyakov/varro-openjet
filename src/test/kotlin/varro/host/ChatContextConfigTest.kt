package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.bool
import varro.settings.VarroSettings

class ChatContextConfigTest {
    @Test fun `boot and live context flags retain saved problems opt outs under the new contract`() {
        val settings = VarroSettings().apply { chatEnableProblemsContext = false }
        val restored = VarroSettings().apply { loadState(settings.state) }
        val boot = JsonObject().apply { addChatContextConfig(restored) }
        assertEquals(setOf("autoAttachProblems", "autoAttachActiveFile"), boot.keySet())
        assertEquals(false, boot.bool("autoAttachProblems"))
        assertEquals(true, boot.bool("autoAttachActiveFile"))

        restored.chatEnableProblemsContext = true
        restored.chatAutoAttachActiveFile = false
        val update = JsonObject().apply { addChatContextConfig(restored) }
        assertEquals(true, update.bool("autoAttachProblems"))
        assertEquals(false, update.bool("autoAttachActiveFile"))
        assertFalse(boot.bool("autoAttachProblems")!!)
    }
}
