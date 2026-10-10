package varro.server

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCodeV2BackgroundProcessTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private fun shell(id: String = "shell_one", sessionID: String = "ses_one", command: String = "npm test") = Json.obj(
        "id" to id, "metadata" to Json.obj("sessionID" to sessionID), "status" to "running", "command" to command,
        "cwd" to "/one", "pid" to 42, "time" to Json.obj("started" to 1000),
    )

    @Test fun `services persist across adapters and do not block or get stopped by abort`() {
        val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
        val calls = mutableListOf<String>()
        var active = Json.obj()
        val shells = listOf(shell(), shell("service", command = "npm run dev"), shell("other", "ses_other"))
        fun adapter() = OpenCodeV2Adapter({ method, path, _, _ ->
            calls.add("$method $path")
            OpenCodeResponse(when {
                path == "/api/session/active" -> Json.obj("data" to active)
                path.substringBefore('?') == "/api/shell" -> Json.obj("data" to shells)
                path.substringBefore('?') == "/api/shell/service" -> Json.obj("data" to shells[1])
                else -> Json.obj()
            })
        }, state)
        val native = adapter()
        val options = RequestOptions(directory = "/one")
        native.request("PATCH", "/session/ses_one/background-process/service", Json.obj("service" to true), options)
        val listed = native.request("GET", "/session/ses_one/background-process", null, options).data.asArrayOrNull()!!
        assertEquals(2, listed.size())
        assertEquals(true, listed[1].asJsonObject.bool("service"))
        val restored = adapter()
        val status = restored.request("GET", "/session/status", null, options).data.asObjectOrNull().obj("ses_one")
        assertEquals(true, status.bool("background"))
        assertEquals("npm test", status.str("backgroundCommand"))
        assertEquals(1, status.int("backgroundServices"))
        restored.request("POST", "/session/ses_one/abort", null, options)
        assertTrue(calls.any { it.startsWith("DELETE /api/shell/shell_one?") })
        assertFalse(calls.any { it.startsWith("DELETE /api/shell/service") })
        val idle = restored.request("GET", "/session/status", null, options).data.asObjectOrNull().obj("ses_one")
        assertEquals("idle", idle.str("type"))
        assertEquals(1, idle.int("backgroundServices"))
        active = Json.obj("ses_one" to Json.obj("type" to "running"))
        val busy = restored.request("GET", "/session/status", null, options).data.asObjectOrNull().obj("ses_one")
        assertEquals("busy", busy.str("type"))
        assertEquals(1, busy.int("backgroundServices"))
        val event = restored.events(Json.obj("type" to "session.execution.succeeded", "data" to Json.obj("sessionID" to "ses_one"))).single()
        assertEquals(1, event.obj("properties").obj("status").int("backgroundServices"))
        native.request("PATCH", "/session/ses_one/background-process/service", Json.obj("service" to false), options)
        restored.request("GET", "/session/status", null, options)
        assertEquals(0, restored.backgroundServiceCount("ses_one"))
    }

    @Test fun `process actions enforce ownership booleans running state and cancellation`() {
        var process = shell()
        var cancelled = false
        var deletes = 0
        val native = OpenCodeV2Adapter({ method, _, _, _ ->
            if (method == "DELETE") deletes++
            OpenCodeResponse(Json.obj("data" to process))
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        for (method in listOf("PATCH", "DELETE", "GET")) {
            val path = "/session/ses_other/background-process/shell_one" + if (method == "GET") "/output" else ""
            assertThrows(IllegalStateException::class.java) { native.request(method, path, Json.obj("service" to true), RequestOptions()) }
        }
        assertEquals(0, deletes)
        assertThrows(IllegalStateException::class.java) {
            native.request("PATCH", "/session/ses_one/background-process/shell_one", Json.obj("service" to "true"), RequestOptions())
        }
        process = shell().apply { addProperty("status", "exited") }
        assertThrows(IllegalStateException::class.java) {
            native.request("PATCH", "/session/ses_one/background-process/shell_one", Json.obj("service" to true), RequestOptions())
        }
        cancelled = true
        assertThrows(CancellationException::class.java) {
            native.request("DELETE", "/session/ses_one/background-process/shell_one", null, RequestOptions(isCancelled = { cancelled }))
        }
        assertEquals(0, deletes)
    }

    @Test fun `logs read a bounded tail then incremental chunks with workspace scope`() {
        val calls = mutableListOf<String>()
        val native = OpenCodeV2Adapter({ _, path, _, _ ->
            calls.add(path)
            OpenCodeResponse(Json.obj("data" to when {
                path.contains("/output?") -> Json.obj("output" to "tail", "size" to 200000, "cursor" to 200000, "truncated" to false)
                else -> shell()
            }))
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        val path = "/session/ses_one/background-process/shell_one/output?directory=%2Fone"
        val first = native.request("GET", path, null, RequestOptions()).data.asObjectOrNull()
        assertEquals(true, first.bool("truncated"))
        assertEquals(listOf(
            "/api/shell/shell_one?location%5Bdirectory%5D=%2Fone",
            "/api/shell/shell_one/output?cursor=0&limit=1&location%5Bdirectory%5D=%2Fone",
            "/api/shell/shell_one/output?cursor=134464&limit=65536&location%5Bdirectory%5D=%2Fone",
        ), calls)
        calls.clear()
        native.request("GET", "$path&cursor=200000", null, RequestOptions())
        assertEquals(2, calls.size)
        assertTrue(calls.last().contains("cursor=200000&limit=65536"))
        for (cursor in listOf("-1", "1.5", "9007199254740992")) assertThrows(IllegalArgumentException::class.java) {
            native.request("GET", "$path&cursor=$cursor", null, RequestOptions())
        }
    }

    @Test fun `command summary survives exit handoff and workspace snapshots stay isolated`() {
        var now = 0L
        val work = OpenCodeV2BackgroundWork { now }
        val process = shell(command = "npm\n test " + "x".repeat(1000))
        work.observe("shell.created", Json.obj("info" to process), "/one")
        work.observe("session.execution.succeeded", Json.obj("sessionID" to "ses_one"), "/one")
        val command = work.command("ses_one")!!
        assertTrue(command.length <= 512)
        assertFalse(command.contains('\n'))
        work.observe("shell.exited", Json.obj("id" to "shell_one"), "/one")
        assertEquals(command, work.command("ses_one"))
        now = 3000
        work.reconcile(emptyList(), emptySet(), "/two", work.snapshotVersion())
        assertTrue(work.isWaiting("ses_one"))
        work.reconcile(emptyList(), emptySet(), "/one", work.snapshotVersion())
        assertFalse(work.isWaiting("ses_one"))
    }

    @Test fun `automatic reviews do not block status and manual choices beat late judgments`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
        val native = OpenCodeV2Adapter({ _, path, _, _ -> OpenCodeResponse(Json.obj("data" to when (path) {
            "/api/session/active" -> Json.obj()
            "/api/shell" -> listOf(shell())
            else -> shell()
        })) }, state, { _, _, _ ->
            started.countDown()
            try { release.await(5, TimeUnit.SECONDS); true } finally { finished.countDown() }
        })
        try {
            val initial = native.request("GET", "/session/status", null, RequestOptions()).data.asObjectOrNull().obj("ses_one")
            assertEquals(true, initial.bool("background"))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            native.request("PATCH", "/session/ses_one/background-process/shell_one", Json.obj("service" to false), RequestOptions())
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            val saved = OpenCodeV2SessionState(state.directory.resolve("background-services")).read("ses_one")
            assertEquals(false, saved.bool("shell_one"))
            assertEquals(true, saved.bool("manual:shell_one"))
            assertEquals(0, native.backgroundServiceCount("ses_one"))
        } finally { release.countDown(); native.reset() }
    }

    @Test fun `validated judgment detaches a service and publishes its status`() {
        val changed = CountDownLatch(1)
        val native = OpenCodeV2Adapter({ _, path, _, _ -> OpenCodeResponse(Json.obj("data" to when (path) {
            "/api/session/active" -> Json.obj()
            "/api/shell" -> listOf(shell(command = "npm run dev"))
            else -> shell()
        })) }, OpenCodeV2SessionState(temporary.newFolder().toPath()), { _, _, _ -> true }, { _, _ -> changed.countDown() })
        try {
            native.request("GET", "/session/status", null, RequestOptions())
            assertTrue(changed.await(2, TimeUnit.SECONDS))
            assertEquals(1, native.backgroundServiceCount("ses_one"))
            val status = native.request("GET", "/session/status", null, RequestOptions()).data.asObjectOrNull().obj("ses_one")
            assertEquals("idle", status.str("type"))
            assertEquals(1, status.int("backgroundServices"))
        } finally { native.reset() }
    }

    @Test fun `judgment uses the session model and one shot generation only`() {
        val calls = mutableListOf<String>()
        var verdict = "{\"blocking\":false}"
        var generated: JsonObject? = null
        val judge = BackgroundProcessJudge { method, path, body, options ->
            calls.add("$method $path")
            assertEquals(20000L, options.timeoutMs)
            OpenCodeResponse(when (path) {
                "/api/session/ses_one" -> Json.obj("data" to Json.obj("title" to "preview", "model" to Json.obj("providerID" to "cloud", "id" to "test", "variant" to "fast")))
                "/openapi.json" -> Json.obj("paths" to Json.obj("/api/experimental/generate" to Json.obj("post" to Json.obj())))
                "/api/experimental/generate" -> { generated = body.asObjectOrNull(); Json.obj("data" to Json.obj("text" to verdict)) }
                else -> error("Unexpected request $path")
            })
        }
        assertEquals(true, judge.classify(shell(), "/one") { false })
        assertEquals("test", generated.obj("model").str("id"))
        assertEquals("fast", generated.obj("model").str("variant"))
        assertEquals(listOf("GET /api/session/ses_one", "GET /openapi.json", "POST /api/experimental/generate"), calls)
        for (invalid in listOf("{\"blocking\":\"false\"}", "false", "Here is JSON: {\"blocking\":false}", "{}",
            "{'blocking':false}", "{blocking:false}", "{\"blocking\":false} trailing")) {
            verdict = invalid
            assertNull(judge.classify(shell(), "/one") { false })
        }
        verdict = "{\"blocking\":true}"
        assertEquals(false, judge.classify(shell(), "/one") { false })
        assertEquals(300000L, OpenCodeV2BackgroundServices.nextReviewAt(0, 0))
        assertEquals(600000L, OpenCodeV2BackgroundServices.nextReviewAt(0, 300000))
        assertEquals(3600000L, OpenCodeV2BackgroundServices.nextReviewAt(0, 1800000))
    }

    @Test fun `conditional annotation update preserves another editor manual choice`() {
        val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
        state.update("ses_one", Json.obj("shell_one" to false, "manual:shell_one" to true))
        state.update("ses_one", Json.obj("shell_one" to true), onlyIfMissing = "manual:shell_one")
        assertEquals(false, state.read("ses_one").bool("shell_one"))
        val cancelled = AtomicBoolean(true)
        assertThrows(CancellationException::class.java) {
            state.update("ses_one", Json.obj("shell_one" to true)) { if (cancelled.get()) throw CancellationException() }
        }
        assertEquals(false, state.read("ses_one").bool("shell_one"))
    }

    @Test fun `failed process stop leaves service tracking intact and successful stop clears it`() {
        var fail = true
        val native = OpenCodeV2Adapter({ method, _, _, _ ->
            if (method == "DELETE" && fail) error("Stop failed")
            OpenCodeResponse(Json.obj("data" to shell()))
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        native.request("PATCH", "/session/ses_one/background-process/shell_one", Json.obj("service" to true), RequestOptions())
        assertThrows(IllegalStateException::class.java) {
            native.request("DELETE", "/session/ses_one/background-process/shell_one", null, RequestOptions())
        }
        assertEquals(1, native.backgroundServiceCount("ses_one"))
        fail = false
        native.request("DELETE", "/session/ses_one/background-process/shell_one", null, RequestOptions())
        assertEquals(0, native.backgroundServiceCount("ses_one"))
    }

    @Test fun `annotation lock contention is cancellable and dead owners recover safely`() {
        val root = temporary.newFolder().toPath()
        val state = OpenCodeV2SessionState(root)
        val lock = root.resolve("ses_one.json.lock")
        java.nio.file.Files.createDirectory(lock)
        val owner = lock.resolve("${ProcessHandle.current().pid()}-${java.util.UUID.randomUUID()}")
        java.nio.file.Files.createFile(owner)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            state.update("ses_one", Json.obj("choice" to true)) { if (++checks > 2) throw CancellationException() }
        }
        assertTrue(java.nio.file.Files.exists(owner))
        assertEquals(1L, java.nio.file.Files.list(root).use { it.count() })
        java.nio.file.Files.delete(owner)
        // Use a PID beyond the supported OS PID range, not a potentially live process.
        java.nio.file.Files.createFile(lock.resolve("2147483647-${java.util.UUID.randomUUID()}"))
        state.update("ses_one", Json.obj("choice" to true))
        assertEquals(true, state.read("ses_one").bool("choice"))
        assertFalse(java.nio.file.Files.exists(lock))
    }
}
