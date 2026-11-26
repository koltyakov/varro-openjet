package varro.host

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryCommandsTest {
    private fun parse(json: String) = CommandHistoryState.parse(JsonParser.parseString(json).asJsonObject)

    @Test fun `commands state carries back and forward availability`() {
        val state = parse("""{"canAbort":false,"canSwitchSessions":true,"canNavigateBack":true,"canNavigateForward":false}""")!!
        assertTrue(state.allows(SessionHistoryDirection.BACK))
        assertFalse(state.allows(SessionHistoryDirection.FORWARD))
    }

    @Test fun `older webviews without history flags cannot navigate`() {
        assertEquals(CommandHistoryState.NONE, parse("""{"canAbort":false,"canSwitchSessions":true}"""))
        assertEquals(CommandHistoryState.NONE, CommandHistoryState.parse(null))
    }

    @Test fun `non boolean history flags reject the message like upstream`() {
        assertNull(parse("""{"canNavigateBack":"yes"}"""))
        assertNull(parse("""{"canNavigateForward":1}"""))
        assertNull(parse("""{"canNavigateBack":null}"""))
    }

    @Test fun `navigation directions use upstream wire values`() {
        assertEquals(listOf("back", "forward"), SessionHistoryDirection.entries.map { it.wire })
    }
}
