package varro.host

import org.junit.Assert.*
import org.junit.Test

class IdeHostEnvironmentTest {
    @Test fun `public product mode distinguishes local and backend hosts without reading launcher arguments`() {
        for (backend in listOf(false, true)) {
            val environment = IdeHostEnvironment({ backend }, { error("Public product mode should take precedence") })
            assertEquals(backend, environment.isRemoteHost())
        }
    }

    @Test fun `older IDEs reject every supported remote and split-mode launcher`() {
        for (command in listOf("cwmHost", "cwmHostNoLobby", "remoteDevHost", "serverMode", "splitMode")) {
            val environment = IdeHostEnvironment({ null }, { listOf("-Xmx2g", "com.intellij.idea.Main", command, "/project") })
            assertTrue(command, environment.isRemoteHost())
        }
    }

    @Test fun `older local JVM and native launchers allow dictation`() {
        for (arguments in listOf(emptyList(), listOf("com.intellij.idea.Main", "/project"), listOf("/project"))) {
            assertFalse(IdeHostEnvironment({ null }, { arguments }).isRemoteHost())
        }
        assertFalse(IdeHostEnvironment({ null }, { listOf("/projects/remoteDevHost") }).isRemoteHost())
    }

    @Test fun `unknown launcher information and product-mode failures disable dictation`() {
        assertTrue(IdeHostEnvironment({ null }, { null }).isRemoteHost())
        assertTrue(IdeHostEnvironment({ error("Product-mode service unavailable") }, { emptyList() }).isRemoteHost())
        assertTrue(IdeHostEnvironment({ null }, { error("Process inspection unavailable") }).isRemoteHost())
    }

    @Test fun `immutable host mode is read only once`() {
        var reads = 0
        val environment = IdeHostEnvironment({ reads++; false }, { null })
        repeat(3) { assertFalse(environment.isRemoteHost()) }
        assertEquals(1, reads)
    }
}
