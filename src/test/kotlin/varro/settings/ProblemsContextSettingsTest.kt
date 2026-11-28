package varro.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemsContextSettingsTest {
    @Test fun `problems context defaults off and persists the opt in`() {
        val settings = VarroSettings()
        assertFalse(settings.chatEnableProblemsContext)
        settings.chatEnableProblemsContext = true
        val restored = VarroSettings().apply { loadState(settings.getState()) }
        assertTrue(restored.chatEnableProblemsContext)
        restored.loadState(VarroSettings())
        assertFalse(restored.chatEnableProblemsContext)
    }

    @Test fun `active file context defaults on and persists independently of problems`() {
        val settings = VarroSettings()
        assertTrue(settings.chatAutoAttachActiveFile)
        settings.chatAutoAttachActiveFile = false
        val restored = VarroSettings().apply { loadState(settings.getState()) }
        assertFalse(restored.chatAutoAttachActiveFile)
        assertFalse(restored.chatEnableProblemsContext)
        restored.loadState(VarroSettings().apply { chatEnableProblemsContext = true })
        assertTrue(restored.chatAutoAttachActiveFile)
        assertTrue(restored.chatEnableProblemsContext)
    }
}
