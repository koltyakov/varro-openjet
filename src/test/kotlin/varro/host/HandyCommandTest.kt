package varro.host

import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HandyCommandTest {
    private lateinit var fixture: Path

    @Before fun prepareFixture() {
        fixture = Files.createTempDirectory("openjet-handy-command-")
        val file = fixture.resolve("varro/host/HandyCommandFixture.class")
        Files.createDirectories(file.parent)
        HandyCommandFixture::class.java.getResourceAsStream("HandyCommandFixture.class")!!.use {
            Files.write(file, it.readBytes())
        }
    }

    @After fun cleanupFixture() { fixture.toFile().deleteRecursively() }

    private fun command(vararg args: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java").toString(),
        "-cp", fixture.toString(),
        HandyCommandFixture::class.java.name,
    ) + args

    @Test fun `commands preserve arguments without a shell and capture exit codes`() {
        val runtime = HandyIntegration.Runtime()
        assertEquals("space ; $(not-a-command)", runtime.run(command("echo", "space ; $(not-a-command)")).output.lineSequence().last())
        assertEquals(3, runtime.run(command("exit")).exitCode)
    }

    @Test(timeout = 10_000) fun `commands have a two second deadline`() {
        val start = System.nanoTime()
        val failure = assertThrows(IllegalStateException::class.java) { HandyIntegration.Runtime().run(command("sleep")) }
        assertTrue(failure.message!!.contains("timed out"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000)
    }

    @Test(timeout = 10_000) fun `command output is bounded`() {
        val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { HandyIntegration.Runtime().run(command("output")) }
        assertTrue(failure.cause!!.message!!.contains("safety limit"))
    }

    @Test fun `POSIX process inspection resolves a numeric current user ID`() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        assertTrue(HandyIntegration.Runtime().userId().matches(Regex("\\d+")))
    }
}
