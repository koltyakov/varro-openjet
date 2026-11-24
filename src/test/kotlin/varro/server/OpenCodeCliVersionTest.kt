package varro.server

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeCliVersionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `V2 floor is 2_0_6 and V1 floor is unchanged`() {
        for (version in listOf("2.0.5", "2.0.6", "2.4.1")) assertEquals("2.0.6", OpenCodeCli.minimumSupportedVersion(version))
        for (version in listOf("1.15.9", "1.19.6")) assertEquals("1.16.0", OpenCodeCli.minimumSupportedVersion(version))
        org.junit.Assert.assertTrue(OpenCodeCli.compareVersions("2.0.5", OpenCodeCli.minimumSupportedVersion("2.0.5")) < 0)
        assertEquals(0, OpenCodeCli.compareVersions("2.0.6", OpenCodeCli.minimumSupportedVersion("2.0.6")))
    }

    @Test fun `version diagnostics can refresh an upgraded CLI without dropping command discovery`() {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val executable = temporary.newFile(if (windows) "opencode.cmd" else "opencode")
        fun install(version: String) {
            executable.writeText(if (windows) "@echo $version\r\n" else "#!/bin/sh\nprintf '%s\\n' '$version'\n")
            if (!windows) check(executable.setExecutable(true))
        }
        install("2.0.21")
        val cli = OpenCodeCli({ executable.absolutePath }, { temporary.root.absolutePath })
        assertEquals("2.0.21", cli.readInstalledVersion())
        install("2.0.22")
        assertEquals("2.0.21", cli.readInstalledVersion())
        assertEquals("2.0.22", cli.readInstalledVersion(refresh = true))
        assertEquals("2.0.22", cli.readInstalledVersion())
    }
}
