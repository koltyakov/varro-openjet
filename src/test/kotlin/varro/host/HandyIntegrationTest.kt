package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.host.HandyIntegration.Action
import varro.host.HandyIntegration.CommandResult
import varro.protocol.*
import java.nio.file.Path
import java.util.concurrent.Executor
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND

class HandyIntegrationTest {
    private class Runtime(os: String = "Mac OS X", environment: Map<String, String> = emptyMap()) : HandyIntegration.Runtime(os, "/fixture/no-home", environment) {
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
    private fun integration(runtime: Runtime, path: String = "", local: Boolean = true, executor: Executor = Executor { it.run() }) =
        HandyIntegration({ path }, { local }, runtime, executor)

    @Test fun `remote and disposed hosts never discover or activate Handy`() {
        val runtime = Runtime()
        val remote = integration(runtime, local = false)
        val disposed = integration(runtime).apply { dispose() }
        for (action in Action.entries) for (handy in listOf(remote, disposed)) {
            assertFalse(handy.request(action).join().available)
            assertEquals("idle", handy.request(action).join().state)
        }
        assertTrue(runtime.inspected.isEmpty()); assertTrue(runtime.commands.isEmpty())
    }

    @Test fun `status checks only installation and current user processes`() {
        val runtime = Runtime()
        val status = integration(runtime).request(Action.STATUS).join()
        assertTrue(status.available); assertTrue(status.running); assertEquals("idle", status.state); assertEquals(0L, status.revision)
        assertEquals(listOf(Path.of("/Applications/Handy.app/Contents/MacOS/Handy")), runtime.inspected)
        assertEquals(listOf(listOf("/usr/bin/pgrep", "-u", "501", "-x", "Handy|handy")), runtime.commands)
    }

    @Test fun `missing and stopped apps never execute dictation commands`() {
        val runtime = Runtime().apply { executable = { false } }
        val handy = integration(runtime)
        assertFalse(handy.request(Action.START).join().available)
        assertTrue(runtime.commands.isEmpty())
        runtime.executable = { true }; runtime.execute = { CommandResult(1) }
        assertFalse(handy.request(Action.STATUS).join().running)
        assertTrue(handy.request(Action.START).join().error!!.contains("Start Handy"))
        assertTrue(runtime.commands.all { it.first() == "/usr/bin/pgrep" })
    }

    @Test fun `explicit intent uses fixed arguments and fallback state without logs`() {
        val path = "/custom/Handy with spaces;safe"
        val runtime = Runtime(); val handy = integration(runtime, path)
        assertEquals("recording", handy.request(Action.START).join().state)
        assertEquals(listOf(path, "--toggle-transcription"), runtime.commands.last())
        val count = runtime.commands.size
        assertEquals("recording", handy.request(Action.START).join().state)
        assertEquals(count + 1, runtime.commands.size) // inspection only, no duplicate toggle
        assertEquals("idle", handy.request(Action.STOP).join().state)
        assertEquals(listOf(path, "--toggle-transcription"), runtime.commands.last())
        handy.request(Action.START).join()
        assertEquals("idle", handy.request(Action.CANCEL).join().state)
        assertEquals(listOf(path, "--cancel"), runtime.commands.last())
        val stopped = runtime.commands.size
        handy.request(Action.STOP).join(); handy.request(Action.CANCEL).join()
        assertEquals(stopped + 2, runtime.commands.size)
    }

    @Test fun `configured paths must be absolute with no fallback and discovery is bounded`() {
        val runtime = Runtime()
        assertTrue(integration(runtime, "relative/handy").request(Action.STATUS).join().error!!.contains("absolute"))
        assertTrue(runtime.inspected.isEmpty())
        runtime.executable = { false }
        assertFalse(integration(runtime, " /missing/Handy ").request(Action.START).join().available)
        assertEquals(listOf(Path.of("/missing/Handy")), runtime.inspected)
        runtime.executable = { runtime.time = 2_000_000_000L; true }
        assertTrue(integration(runtime).request(Action.START).join().error!!.contains("discovery timed out"))
        assertTrue(runtime.commands.isEmpty())
    }

    @Test fun `platform discovery ignores relative PATH entries and uses no shell`() {
        val linux = Runtime("Linux", mapOf("PATH" to ".:/custom/bin:/custom/bin")).apply {
            executable = { it == Path.of("/custom/bin/handy") }
        }
        assertTrue(integration(linux).request(Action.START).join().available)
        assertFalse(linux.inspected.contains(Path.of("handy")))
        assertEquals(1, linux.inspected.count { it == Path.of("/custom/bin/handy") })
        val mac = Runtime().apply { executable = { it == Path.of("/fixture/no-home/Applications/Handy.app/Contents/MacOS/Handy") } }
        assertTrue(integration(mac).request(Action.STATUS).join().available)
        val windows = Runtime("Windows 11", mapOf("LOCALAPPDATA" to "/fixture/local")).apply {
            execute = { CommandResult(0, "\"handy.exe\",\"123\",\"Console\",\"1\",\"12 K\"\r\n") }
        }
        assertTrue(integration(windows).request(Action.STATUS).join().running)
        assertEquals(listOf("tasklist.exe", "/FI", "IMAGENAME eq handy.exe", "/FO", "CSV", "/NH"), windows.commands.single())
    }

    @Test fun `inspection and activation failures never retry a mutation`() {
        val runtime = Runtime().apply { execute = { error("inspection denied") } }
        val handy = integration(runtime)
        assertTrue(handy.request(Action.START).join().error!!.contains("inspection denied"))
        assertEquals(1, runtime.commands.size)
        runtime.commands.clear()
        runtime.execute = { if (it.first() == "/usr/bin/pgrep") CommandResult(0, "123") else error("timed out") }
        assertTrue(handy.request(Action.START).join().error!!.contains("timed out"))
        assertEquals(2, runtime.commands.size)
        runtime.execute = { if (it.first() == "/usr/bin/pgrep") CommandResult(0, "123") else CommandResult(3) }
        assertTrue(handy.request(Action.START).join().error!!.contains("code 3"))
    }

    @Test fun `overlapping mutations are rejected but status cannot consume a deliberate click`() {
        val tasks = mutableListOf<Runnable>(); val runtime = Runtime()
        val handy = integration(runtime, executor = Executor { tasks += it })
        val status = handy.request(Action.STATUS)
        val start = handy.request(Action.START)
        tasks.removeAt(0).run()
        assertEquals("idle", status.join().state)
        assertEquals(1, tasks.size)
        val duplicate = handy.request(Action.START)
        tasks.removeAt(0).run()
        assertEquals("recording", start.join().state)
        assertTrue(duplicate.join().error!!.contains("pending"))
        assertEquals(3, runtime.commands.size)
    }

    @Test fun `executor rejection releases the command guard`() {
        var rejected = true; val runtime = Runtime()
        val handy = integration(runtime, executor = Executor { if (rejected) error("executor stopped") else it.run() })
        assertTrue(handy.request(Action.START).join().error!!.contains("executor stopped"))
        rejected = false
        assertTrue(handy.request(Action.STATUS).join().available)
    }

    @Test fun `protocol validates intent and returns lifecycle state and revision`() {
        val handy = integration(Runtime()); val replies = mutableListOf<com.google.gson.JsonObject>()
        for (payload in listOf(null, Json.obj(), Json.obj("requestId" to 1, "action" to "start"),
            Json.obj("requestId" to "", "action" to "status"), Json.obj("requestId" to "x".repeat(129), "action" to "status"),
            Json.obj("requestId" to "id", "action" to "toggle"), Json.obj("requestId" to "id", "action" to 1))) handy.handleRequest(payload) { replies += it }
        assertTrue(replies.isEmpty())
        handy.handleRequest(Json.obj("requestId" to "id", "action" to "start")) { replies += it }
        val payload = replies.single().obj("payload")!!
        assertEquals("id", payload.str("requestId")); assertEquals("recording", payload.str("state")); assertTrue(payload.long("revision")!! > 0)
    }

    @Test fun `portable lifecycle logs report external completion and prevent a stop from restarting recording`() {
        val root = Files.createTempDirectory("varro-handy-portable-")
        fun line(message: String) = "[2026-10-18][10:00:00][handy_app_lib::actions][INFO] $message\n"
        val log = root.resolve("Data/logs/handy.log")
        Files.createDirectories(log.parent); Files.writeString(root.resolve("portable"), "")
        Files.writeString(log, line("Text pasted successfully"))
        val runtime = Runtime().apply {
            execute = { command ->
                if (command.last() == "--toggle-transcription") Files.writeString(log,
                    "[2026-10-18][10:00:00][handy_app_lib::managers::audio][INFO] Recording started for binding default\n", APPEND)
                CommandResult(0, "123")
            }
        }
        val handy = integration(runtime, root.resolve("Handy").toString())
        try {
            assertEquals("idle", handy.request(Action.STATUS).join().state)
            val idle = java.util.concurrent.CountDownLatch(1)
            assertEquals("recording", handy.request(Action.START) { if (it.state == "idle") idle.countDown() }.join().state)
            Files.writeString(log, line("Text pasted successfully"), APPEND)
            assertTrue("Watcher must publish completion", idle.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val before = runtime.commands.size
            assertEquals("idle", handy.request(Action.STOP).join().state)
            assertEquals(before + 1, runtime.commands.size)
        } finally {
            handy.dispose()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
