package varro.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemsContextSettingsTest {
    @Test fun `problems context defaults on and persists the opt out`() {
        val settings = VarroSettings()
        assertTrue(settings.chatEnableProblemsContext)
        settings.chatEnableProblemsContext = false
        val restored = VarroSettings().apply { loadState(settings.getState()) }
        assertFalse(restored.chatEnableProblemsContext)
        restored.loadState(VarroSettings())
        assertTrue(restored.chatEnableProblemsContext)
    }

    @Test fun `active file context defaults on and persists independently of problems`() {
        val settings = VarroSettings()
        assertTrue(settings.chatAutoAttachActiveFile)
        settings.chatAutoAttachActiveFile = false
        val restored = VarroSettings().apply { loadState(settings.getState()) }
        assertFalse(restored.chatAutoAttachActiveFile)
        assertTrue(restored.chatEnableProblemsContext)
        restored.loadState(VarroSettings().apply { chatEnableProblemsContext = false })
        assertTrue(restored.chatAutoAttachActiveFile)
        assertFalse(restored.chatEnableProblemsContext)
    }
}
