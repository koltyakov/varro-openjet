package varro.host

import org.junit.Assert.*
import org.junit.Test

class TerminalServiceTest {
    @Test fun `Claude Code login is allowlisted without allowing arbitrary shell commands`() {
        assertTrue("claude auth login" in TerminalService.ALLOWED_COMMANDS)
        for (command in listOf("claude auth login; whoami", "claude auth login && whoami", "claude auth login --token secret", "claude update")) {
            assertFalse(command in TerminalService.ALLOWED_COMMANDS)
        }
    }
}
