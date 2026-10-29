package varro.server

import org.junit.Assert.*
import org.junit.Test

class OpenCodeCliSetupTest {
    @Test fun `missing CLI notification focuses on installing V2 without restart or legacy guidance`() {
        val message = OpenCodeCli.MISSING_CLI_MESSAGE
        assertEquals("OpenCode CLI not found. Install OpenCode v2 with: npm i -g @opencode/cli.", message)
        assertFalse(Regex("\\bv1\\b|restart", RegexOption.IGNORE_CASE).containsMatchIn(message))
        val info = OpenCodeCommandInfo("opencode", false, "", OpenCodeInstallMethod.UNKNOWN, emptyList())
        assertEquals(message, OpenCodeCliMissingException(info).message)
    }

    @Test fun `missing CLI classification accepts the notification and preserves spawn error markers`() {
        for (message in listOf(OpenCodeCli.MISSING_CLI_MESSAGE, "Startup failed: ${OpenCodeCli.MISSING_CLI_MESSAGE}",
            "spawn opencode ENOENT", "opencode: command not found")) {
            assertTrue(message, OpenCodeCli.isMissingCliFailure(message))
        }
        assertFalse(OpenCodeCli.isMissingCliFailure("OpenCode authentication failed"))
    }

    @Test fun `configured path failures retain their distinct repair message`() {
        val info = OpenCodeCommandInfo("/missing/opencode", false, "/missing/opencode", OpenCodeInstallMethod.CONFIGURED, emptyList())
        assertEquals("The configured OpenCode command was not found: /missing/opencode", OpenCodeCliMissingException(info).message)
    }
}
