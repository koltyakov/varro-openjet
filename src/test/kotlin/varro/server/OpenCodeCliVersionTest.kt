package varro.server

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCodeCliVersionTest {
    @get:Rule val temporary = TemporaryFolder()

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
