package varro.server

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeUpgradeCommandTest {
    @Test fun `piped installers run through a shell rather than as curl arguments`() {
        val command = OpenCodeInstallMethod.CURL.upgradeCommand!!
        assertEquals(listOf("/bin/sh", "-c", command), OpenCodeProcess.upgradeCommandLine(command, windows = false))
    }

    @Test fun `windows package manager shims run through cmd`() {
        val command = OpenCodeInstallMethod.NPM.upgradeCommand!!
        assertEquals(listOf("cmd.exe", "/d", "/s", "/c", command), OpenCodeProcess.upgradeCommandLine(command, windows = true))
    }

    @Test fun `every install method upgrade command is a shell command line`() {
        OpenCodeInstallMethod.entries.mapNotNull { it.upgradeCommand }.forEach { command ->
            assertEquals(3, OpenCodeProcess.upgradeCommandLine(command, windows = false).size)
        }
    }
}
