package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.host.HandyIntegration.Action
import varro.host.HandyIntegration.CommandResult
import varro.protocol.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class HexIntegrationTest {
    private class Runtime(os: String = "Mac OS X", environment: Map<String, String> = emptyMap()) : HexIntegration.Runtime(os, "/fixture/home", environment) {
        val commands = mutableListOf<Pair<List<String>, Long>>()
        val inspected = mutableListOf<Path>()
        var executable: (Path) -> Boolean = { true }
        var time = 0L
        var version: String? = "0.0.0-dev-456"
        @Volatile var state = "idle"
        var execute: ((List<String>) -> CommandResult)? = null
        override fun nanoTime() = time
        override fun isExecutable(path: Path): Boolean { inspected.add(path); return executable(path) }
        override fun readVersion(path: Path) = version
        override fun run(command: List<String>, timeoutMs: Long): CommandResult {
            synchronized(commands) { commands += command to timeoutMs }
            execute?.let { return it(command) }
            return if (command[1] == "status") reply("status", "listener" to Json.obj("running" to true), "capture" to Json.obj("state" to state))
            else { state = when (command[2]) { "start" -> "recording"; "stop" -> "transcribing"; else -> "idle" }; reply("done") }
        }
    }
    private fun integration(runtime: Runtime, local: Boolean = true, interval: Long = 10) =
        HexIntegration(runtime, { local }, Executor { it.run() }, interval)

    @Test fun `status discovery never activates and commands use fixed idempotent arguments and deadlines`() {
        val runtime = Runtime()
        integration(runtime).use { hex ->
            assertEquals("idle", hex.request(Action.STATUS).join().state)
            assertEquals(listOf("/Applications/Hex.app/Contents/MacOS/hex", "status", "--json") to 2_000L, runtime.commands.single())
            for ((action, expected) in listOf(Action.START to "recording", Action.STOP to "processing", Action.CANCEL to "idle")) {
                assertEquals(expected, hex.request(action).join().state)
                assertEquals(listOf("/Applications/Hex.app/Contents/MacOS/hex", "dictation", action.wireName, "--json") to 12_000L,
                    runtime.commands[runtime.commands.size - 2])
            }
            assertTrue(runtime.commands.none { (command, _) -> command.any { "toggle" in it } })
        }
    }

    @Test fun `remote missing stopped and disposed integrations cannot mutate`() {
        val runtime = Runtime()
        integration(runtime, local = false).use { assertFalse(it.request(Action.START).join().available) }
        integration(runtime).apply { close() }.use { assertFalse(it.request(Action.START).join().available) }
        assertTrue(runtime.commands.isEmpty()); assertTrue(runtime.inspected.isEmpty())
        runtime.executable = { false }
        integration(runtime).use { assertFalse(it.request(Action.START).join().available) }
        assertTrue(runtime.commands.isEmpty())
        runtime.executable = { true }
        runtime.execute = { reply("error", "code" to "not-running", "message" to "Stopped").copy(exitCode = 3) }
        integration(runtime).use { hex ->
            assertFalse(hex.request(Action.STATUS).join().running)
            assertTrue(hex.request(Action.START).join().error!!.contains("Start Hex"))
        }
        assertTrue(runtime.commands.all { it.first[1] == "status" })
    }

    @Test fun `unsafe Windows pointers are rejected and PATH discovery ignores relative entries`() {
        for (pointer in listOf("..", ".", "../outside", "v/../../escape", "x".repeat(129))) {
            val runtime = Runtime("Windows 11", mapOf("APPDATA" to "/roaming", "PATH" to "relative")).apply { version = pointer }
            integration(runtime).use { assertFalse(it.request(Action.START).join().available) }
            assertTrue(runtime.inspected.isEmpty()); assertTrue(runtime.commands.isEmpty())
        }
        val windows = Runtime("Windows 11", mapOf("APPDATA" to "/roaming"))
        integration(windows).use { assertTrue(it.request(Action.STATUS).join().available) }
        assertEquals(Path.of("/roaming/hex/versions/0.0.0-dev-456/hex.exe"), windows.inspected.single())
        val linux = Runtime("Linux", mapOf("PATH" to ".:/custom/bin with spaces&safe")).apply {
            executable = { it == Path.of("/custom/bin with spaces&safe/hex") }
        }
        integration(linux).use { assertTrue(it.request(Action.START).join().available) }
        assertTrue(linux.inspected.all { it.isAbsolute })
        assertEquals("/custom/bin with spaces&safe/hex", linux.commands.last().first.first())
    }

    @Test fun `malformed replies refusals timeouts and expired discovery never retry a mutation`() {
        for (output in listOf("not JSON", "{}", "{\"version\":1.5}", "{\"version\":true}",
            "{version:4,status:'status',listener:{running:true},capture:{state:'idle'}}",
            "{\"version\":4,\"status\":\"status\",\"listener\":{\"running\":true},\"capture\":{\"state\":\"invented\"}}")) {
            val runtime = Runtime().apply { execute = { CommandResult(0, output) } }
            integration(runtime).use { assertNotNull(it.request(Action.START).join().error) }
            assertEquals(1, runtime.commands.size)
        }
        val runtime = Runtime().apply { execute = { command ->
            if (command[1] == "status") reply("status", "listener" to Json.obj("running" to true), "capture" to Json.obj("state" to "idle"))
            else reply("error", "code" to "not-ready", "message" to "Microphone permission required").copy(exitCode = 1)
        } }
        integration(runtime).use { assertTrue(it.request(Action.START).join().error!!.contains("Microphone permission required")) }
        assertEquals(2, runtime.commands.size)
        runtime.commands.clear()
        runtime.execute = { command -> if (command[1] == "status") reply("status", "listener" to Json.obj("running" to true), "capture" to Json.obj("state" to "idle")) else error("timed out") }
        integration(runtime).use { assertTrue(it.request(Action.START).join().error!!.contains("timed out")) }
        assertEquals(2, runtime.commands.size)
        runtime.commands.clear(); runtime.executable = { runtime.time = TimeUnit.SECONDS.toNanos(2); true }
        integration(runtime).use { assertTrue(it.request(Action.START).join().error!!.contains("discovery timed out")) }
        assertTrue(runtime.commands.isEmpty())
    }

    @Test fun `discarded completion succeeds and explicit capture watches through external idle`() {
        val runtime = Runtime()
        integration(runtime).use { hex ->
            val idle = CountDownLatch(1)
            assertEquals("recording", hex.request(Action.START) { if (it.state == "idle") idle.countDown() }.join().state)
            runtime.state = "idle"
            assertTrue(idle.await(2, TimeUnit.SECONDS))
        }
        runtime.execute = { command -> if (command[1] == "status") reply("status", "listener" to Json.obj("running" to true), "capture" to Json.obj("state" to "idle"))
            else reply("error", "code" to "discarded", "message" to "No speech").copy(exitCode = 1) }
        integration(runtime).use { assertNull(it.request(Action.STOP).join().error) }
    }

    @Test fun `real CLI subprocess timeout and oversized stdout are bounded`() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val runtime = HexIntegration.Runtime()
        try { runtime.run(listOf("/bin/sleep", "5"), 20); fail("Expected timeout") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("timed out")) }
        try { runtime.run(listOf("/usr/bin/head", "-c", "1048577", "/dev/zero"), 2_000); fail("Expected output bound") }
        catch (expected: java.util.concurrent.ExecutionException) { assertTrue(expected.cause!!.message!!.contains("safety limit")) }
    }

    companion object {
        private fun reply(status: String, vararg fields: Pair<String, Any?>): CommandResult =
            CommandResult(0, Json.obj("version" to 4, "status" to status, *fields).toString())
    }
}
