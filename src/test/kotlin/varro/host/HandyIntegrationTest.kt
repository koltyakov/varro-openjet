package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.host.HandyIntegration.Action
import varro.host.HandyIntegration.CommandResult
import varro.host.HandyIntegration.Status
import varro.protocol.Json
import java.nio.file.Path
import java.util.concurrent.Executor

class HandyIntegrationTest {
    private class Runtime(os: String = "Mac OS X", environment: Map<String, String> = emptyMap()) : HandyIntegration.Runtime(os, "/home/test", environment) {
        val inspected = mutableListOf<Path>()
        val commands = mutableListOf<List<String>>()
        var executable: (Path) -> Boolean = { true }
        var execute: (List<String>) -> CommandResult = { CommandResult(0, "123\n") }
        var time = 0L
        override fun nanoTime() = time
        override fun isExecutable(path: Path): Boolean { inspected.add(path); return executable(path) }
        override fun run(command: List<String>): CommandResult { commands += command; return execute(command) }
        override fun userId() = "501"
    }

    private val direct = Executor { it.run() }
    private fun integration(runtime: Runtime, path: String = "", local: Boolean = true, executor: Executor = direct) =
        HandyIntegration({ path }, { local }, runtime, executor)

    @Test fun `remote hosts never discover or activate Handy`() {
        val runtime = Runtime()
        for (action in Action.entries) assertEquals(Status(false, false), integration(runtime, local = false).request(action).join())
        assertTrue(runtime.inspected.isEmpty())
        assertTrue(runtime.commands.isEmpty())
    }

    @Test fun `status discovers the macOS app and checks only the current user's processes`() {
        val runtime = Runtime()
        assertEquals(Status(true, true), integration(runtime).request(Action.STATUS).join())
        assertEquals(listOf(Path.of("/Applications/Handy.app/Contents/MacOS/Handy")), runtime.inspected)
        assertEquals(listOf(listOf("/usr/bin/pgrep", "-u", "501", "-x", "Handy|handy")), runtime.commands)
    }

    @Test fun `missing and stopped apps never execute dictation commands`() {
        val runtime = Runtime().apply { executable = { false } }
        val handy = integration(runtime)
        assertEquals(Status(false, false), handy.request(Action.TOGGLE).join())
        assertTrue(runtime.commands.isEmpty())
        runtime.executable = { true }
        runtime.execute = { CommandResult(1) }
        assertEquals(Status(true, false), handy.request(Action.STATUS).join())
        val result = handy.request(Action.TOGGLE).join()
        assertTrue(result.available)
        assertFalse(result.running)
        assertTrue(result.error!!.contains("Start Handy"))
        assertTrue(runtime.commands.all { it.first() == "/usr/bin/pgrep" })
    }

    @Test fun `toggle and cancel use fixed arguments without interpreting configured paths`() {
        val path = Path.of("/custom/Handy with spaces;safe").toAbsolutePath().toString()
        for ((action, flag) in listOf(Action.TOGGLE to "--toggle-transcription", Action.CANCEL to "--cancel")) {
            val runtime = Runtime()
            assertEquals(Status(true, true), integration(runtime, path).request(action).join())
            assertEquals(listOf(Path.of(path)), runtime.inspected)
            assertEquals(listOf(path, flag), runtime.commands.last())
            assertEquals(2, runtime.commands.size)
        }
    }

    @Test fun `configured paths must be absolute and never fall back to other installs`() {
        val runtime = Runtime()
        assertTrue(integration(runtime, "relative/handy").request(Action.STATUS).join().error!!.contains("absolute"))
        assertTrue(runtime.inspected.isEmpty())
        runtime.executable = { false }
        val missing = Path.of("/missing/Handy").toAbsolutePath()
        assertEquals(Status(false, false), integration(runtime, " $missing ").request(Action.TOGGLE).join())
        assertEquals(listOf(missing), runtime.inspected)
        assertTrue(runtime.commands.isEmpty())
    }

    @Test fun `macOS discovery includes the user's Applications folder`() {
        val runtime = Runtime().apply { executable = { it == Path.of("/home/test/Applications/Handy.app/Contents/MacOS/Handy") } }
        assertEquals(Status(true, true), integration(runtime).request(Action.STATUS).join())
        assertEquals(2, runtime.inspected.size)
    }

    @Test fun `Linux discovery ignores relative PATH entries and duplicate candidates`() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val runtime = Runtime("Linux", mapOf("PATH" to ".:/custom/bin:/custom/bin")).apply {
            executable = { it == Path.of("/custom/bin/handy") }
        }
        assertEquals(Status(true, true), integration(runtime).request(Action.TOGGLE).join())
        assertFalse(runtime.inspected.contains(Path.of("handy")))
        assertEquals(1, runtime.inspected.count { it == Path.of("/custom/bin/handy") })
        assertEquals(listOf("/custom/bin/handy", "--toggle-transcription"), runtime.commands.last())
    }

    @Test fun `Windows discovery checks install locations and uses tasklist without a shell`() {
        val runtime = Runtime("Windows 11", mapOf("LOCALAPPDATA" to "/user/local", "ProgramFiles" to "/programs")).apply {
            execute = { CommandResult(0, "\"handy.exe\",\"123\",\"Console\",\"1\",\"12 K\"\r\n") }
        }
        val handy = integration(runtime)
        assertEquals(Status(true, true), handy.request(Action.STATUS).join())
        assertEquals(Path.of("/user/local/Handy/handy.exe"), runtime.inspected.single())
        assertEquals(listOf("tasklist.exe", "/FI", "IMAGENAME eq handy.exe", "/FO", "CSV", "/NH"), runtime.commands.single())
        runtime.execute = { CommandResult(0, "INFO: No tasks are running which match the specified criteria.") }
        assertEquals(Status(true, false), handy.request(Action.STATUS).join())
    }

    @Test fun `inspection and activation failures never retry a mutation`() {
        val runtime = Runtime().apply { execute = { error("inspection denied") } }
        val handy = integration(runtime)
        assertTrue(handy.request(Action.TOGGLE).join().error!!.contains("inspection denied"))
        assertEquals(1, runtime.commands.size)
        runtime.commands.clear()
        runtime.execute = { if (it.first() == "/usr/bin/pgrep") CommandResult(0, "123") else error("timed out") }
        assertTrue(handy.request(Action.TOGGLE).join().error!!.contains("timed out"))
        assertEquals(2, runtime.commands.size)
        runtime.commands.clear()
        runtime.execute = { CommandResult(2) }
        assertTrue(handy.request(Action.CANCEL).join().error!!.contains("inspection"))
        assertEquals(1, runtime.commands.size)
        runtime.commands.clear()
        runtime.execute = { if (it.first() == "/usr/bin/pgrep") CommandResult(0, "123") else CommandResult(3) }
        assertTrue(handy.request(Action.CANCEL).join().error!!.contains("code 3"))
        assertEquals(2, runtime.commands.size)
    }

    @Test fun `a late filesystem result cannot activate dictation`() {
        val runtime = Runtime().apply { executable = { time = 2_000_000_000L; true } }
        val status = integration(runtime).request(Action.TOGGLE).join()
        assertTrue(status.error!!.contains("discovery timed out"))
        assertFalse(status.available)
        assertTrue(runtime.commands.isEmpty())
    }

    @Test fun `all views share discovery and overlapping mutations are rejected`() {
        val tasks = mutableListOf<Runnable>()
        val runtime = Runtime()
        val handy = integration(runtime, executor = Executor { tasks += it })
        val first = handy.request(Action.TOGGLE)
        val second = handy.request(Action.TOGGLE)
        val cancel = handy.request(Action.CANCEL)
        val status = handy.request(Action.STATUS)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertEquals(Status(true, true), first.join())
        assertTrue(second.join().error!!.contains("pending"))
        assertTrue(cancel.join().error!!.contains("pending"))
        assertEquals(Status(true, true), status.join())
        assertEquals(2, runtime.commands.size)
        val next = handy.request(Action.CANCEL)
        tasks.removeAt(0).run()
        assertEquals(Status(true, true), next.join())
        assertEquals(4, runtime.commands.size)
    }

    @Test fun `executor rejection releases the command guard`() {
        var rejected = true
        val runtime = Runtime()
        val handy = integration(runtime, executor = Executor { if (rejected) error("executor stopped") else it.run() })
        assertTrue(handy.request(Action.TOGGLE).join().error!!.contains("executor stopped"))
        rejected = false
        assertEquals(Status(true, true), handy.request(Action.STATUS).join())
    }

    @Test fun `protocol validation ignores malformed requests and correlates results`() {
        val runtime = Runtime()
        val handy = integration(runtime)
        val replies = mutableListOf<com.google.gson.JsonObject>()
        for (payload in listOf(null, Json.obj(), Json.obj("requestId" to 1, "action" to "toggle"),
            Json.obj("requestId" to "", "action" to "status"), Json.obj("requestId" to "x".repeat(129), "action" to "status"),
            Json.obj("requestId" to "id", "action" to "launch"), Json.obj("requestId" to "id", "action" to 1))) {
            handy.handleRequest(payload) { replies += it }
        }
        assertTrue(replies.isEmpty())
        assertTrue(runtime.commands.isEmpty())
        handy.handleRequest(Json.obj("requestId" to "id", "action" to "status")) { replies += it }
        assertEquals(Json.message("handy/result", Json.obj("requestId" to "id", "available" to true, "running" to true)), replies.single())
        assertFalse(replies.single().getAsJsonObject("payload").has("error"))
        assertEquals("failure", Status(true, false, "failure").payload("id").get("error").asString)
    }
}
