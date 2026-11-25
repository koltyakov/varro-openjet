package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenCodeV2BackgroundJobsTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private fun job(kind: String = "shell", owner: String = "ses_root", status: String = "running") = Json.obj(
        "kind" to kind, "id" to "job_$kind", "sessionID" to owner, "status" to status, "time" to Json.obj("started" to 10),
    ).apply {
        when (kind) {
            "shell" -> { addProperty("shellID", "sh_one"); addProperty("command", "npm test") }
            "subagent" -> { addProperty("childSessionID", "ses_child"); addProperty("agent", "explore"); addProperty("description", "Inspect project") }
            "image" -> { addProperty("path", "/repo/image.png"); addProperty("model", "fixture/image") }
            "video" -> addProperty("path", "/repo/video.mp4")
        }
    }
    private fun shell() = Json.obj("id" to "sh_one", "status" to "running", "command" to "npm test",
        "metadata" to Json.obj("sessionID" to "ses_root"), "time" to Json.obj("started" to 10))

    private inner class Fixture(initial: List<JsonObject> = emptyList()) {
        var jobs: JsonElement = Json.array(initial)
        var shells = listOf(shell())
        var active = Json.obj()
        var jobFailure: String? = null
        var cancelFailure: String? = null
        var ancestryFailure: String? = null
        var onJobs: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, String>>()
        val sessions = mutableMapOf(
            "ses_root" to Json.obj("id" to "ses_root", "location" to Json.obj("directory" to "/repo")),
            "ses_child" to Json.obj("id" to "ses_child", "parentID" to "ses_root", "location" to Json.obj("directory" to "/repo")),
            "ses_nested" to Json.obj("id" to "ses_nested", "parentID" to "ses_child", "location" to Json.obj("directory" to "/nested")),
            "ses_other" to Json.obj("id" to "ses_other", "location" to Json.obj("directory" to "/other")),
        )
        val adapter = OpenCodeV2Adapter({ method, path, _, _ ->
            synchronized(calls) { calls.add(path); requests.add(method to path) }
            OpenCodeResponse(Json.obj("data" to when (val route = path.substringBefore('?')) {
                "/api/session/active" -> active
                "/api/shell" -> shells
                "/api/shell/sh_one" -> shell()
                "/api/experimental/job" -> {
                    val captured = jobs.deepCopy()
                    onJobs?.invoke()
                    jobFailure?.let { error(it) }
                    captured
                }
                "/api/session/ses_root/interrupt" -> true
                else -> if (Regex("/api/experimental/job/[^/]+/cancel").matches(route)) {
                    cancelFailure?.let { error(it) }
                    null
                } else {
                    ancestryFailure?.let { error(it) }
                    sessions[route.removePrefix("/api/session/")] ?: error("404 session not found")
                }
            }))
        }, OpenCodeV2SessionState(temporary.newFolder().toPath())).apply { setServerVersion("2.2.6") }
        fun status(options: RequestOptions = RequestOptions(directory = "/repo")) = adapter.request("GET", "/session/status", null, options).data.asObjectOrNull()!!
        fun event(type: String, data: JsonObject) = adapter.events(Json.obj("type" to type, "data" to data, "location" to Json.obj("directory" to "/repo")))
    }

    @Test fun `native shell subagent image and Code Mode jobs restore waiting for owners and ancestors`() {
        for (kind in listOf("shell", "subagent", "image", "codemode", "video")) {
            val fixture = Fixture(listOf(job(kind, "ses_nested"), job(kind, "ses_other")))
            fixture.shells = emptyList()
            val status = fixture.status()
            assertEquals(setOf("ses_root", "ses_child"), status.keySet())
            assertEquals(true, status.obj("ses_root").bool("background"))
            assertEquals(10L, status.obj("ses_root").long("backgroundStartedAt"))
            if (kind == "codemode") assertEquals("Code Mode script", status.obj("ses_root").str("backgroundCommand"))
            if (kind == "video") assertEquals("/repo/video.mp4", status.obj("ses_root").str("backgroundCommand"))
            assertEquals(1, fixture.calls.count { it == "/api/session/ses_root" })
            assertEquals(1, fixture.calls.count { it == "/api/session/ses_nested" })
        }
    }

    @Test fun `Code Mode titles and native idle preserve waiting until consumption`() {
        for (outcome in listOf("running", "completed", "error", "cancelled")) {
            val fixture = Fixture(listOf(job("codemode", status = outcome).apply { addProperty("title", "Check the build") }))
            fixture.shells = emptyList()
            if (outcome == "cancelled") {
                assertEquals(Json.obj(), fixture.status())
                continue
            }
            assertEquals("Check the build", fixture.status().obj("ses_root").str("backgroundCommand"))
            val idle = fixture.event("session.status.updated", Json.obj("sessionID" to "ses_root", "status" to Json.obj("type" to "idle"))).single()
            assertEquals(true, idle.obj("properties").obj("status").bool("background"))
            assertEquals("busy", idle.obj("properties").obj("status").str("type"))
        }
    }

    @Test fun `waiting work is labelled by kind with the subagent session`() {
        for ((kind, expected) in listOf("shell" to null, "subagent" to "subagent", "codemode" to "task", "video" to "task", "image" to "task")) {
            val fixture = Fixture(listOf(job(kind)))
            fixture.shells = emptyList()
            val status = fixture.status().obj("ses_root")!!
            assertEquals(kind, "busy", status.str("type"))
            assertEquals(kind, expected, status.str("backgroundKind"))
            assertEquals(kind, if (kind == "subagent") "ses_child" else null, status.str("backgroundSessionID"))
            if (expected == null) assertFalse(status.has("backgroundKind"))
            val idle = fixture.event("session.status.updated", Json.obj("sessionID" to "ses_root", "status" to Json.obj("type" to "idle"))).single()
                .obj("properties").obj("status")!!
            assertEquals(kind, true, idle.bool("background"))
            assertEquals(kind, expected, idle.str("backgroundKind"))
            assertEquals(kind, if (kind == "subagent") "ses_child" else null, idle.str("backgroundSessionID"))
            if (expected == null) assertFalse(idle.has("backgroundKind") || idle.has("backgroundSessionID"))
        }
    }

    @Test fun `mixed shells and subagents describe the work named by the status`() {
        val status = Fixture(listOf(job("subagent"), job())).status().obj("ses_root")!!
        assertEquals("Inspect project", status.str("backgroundCommand"))
        assertEquals("subagent", status.str("backgroundKind"))
        assertEquals("ses_child", status.str("backgroundSessionID"))
    }

    @Test fun `shell exit after subagent completion keeps the shell command without a kind`() {
        val work = OpenCodeV2BackgroundWork { 0 }
        work.observe("shell.created", Json.obj("info" to shell()), "/repo")
        work.reconcile(listOf(shell()), emptySet(), "/repo", work.snapshotVersion(), nativeJobs = mapOf("ses_root" to listOf(job("subagent"))))
        assertEquals(BackgroundWorkSummary("Inspect project", "subagent", "ses_child"), work.describe("ses_root"))
        work.reconcile(listOf(shell()), emptySet(), "/repo", work.snapshotVersion(), nativeJobs = emptyMap())
        work.observe("shell.exited", Json.obj("id" to "sh_one"), "/repo")
        assertTrue(work.isWaiting("ses_root"))
        assertEquals(BackgroundWorkSummary("npm test"), work.describe("ses_root"))
    }

    @Test fun `native empty snapshot excludes ordinary shells and cancelled jobs`() {
        assertEquals(Json.obj(), Fixture().status())
        assertEquals(Json.obj(), Fixture(listOf(job(status = "cancelled"))).status())
    }

    @Test fun `completed and errored jobs wait until consumption and completion handoff`() {
        for (outcome in listOf("completed", "error")) for (kind in listOf("image", "codemode", "video")) {
            var now = 0L
            val work = OpenCodeV2BackgroundWork { now }
            val jobs = mapOf("ses_root" to listOf(job(kind, status = outcome)))
            assertEquals(listOf("ses_root"), work.reconcile(emptyList(), emptySet(), "/repo", work.snapshotVersion(), nativeJobs = jobs))
            now = 60000
            work.reconcile(emptyList(), emptySet(), "/repo", work.snapshotVersion(), nativeJobs = jobs)
            assertTrue(work.isWaiting("ses_root"))
            work.reconcile(emptyList(), emptySet(), "/repo", work.snapshotVersion(), nativeJobs = emptyMap())
            now += 2000
            work.reconcile(emptyList(), emptySet(), "/repo", work.snapshotVersion(), nativeJobs = emptyMap())
            assertFalse(work.isWaiting("ses_root"))
        }
    }

    @Test fun `active execution is busy without background flag`() {
        val fixture = Fixture(listOf(job("image")))
        fixture.active = Json.obj("ses_root" to Json.obj("type" to "running"))
        assertEquals(Json.obj("ses_root" to Json.obj("type" to "busy")), fixture.status())
    }

    @Test fun `service choices exclude only shells and survive descendant reload`() {
        val fixture = Fixture(listOf(job(), job("subagent")))
        fixture.adapter.request("PATCH", "/session/ses_root/background-process/sh_one", Json.obj("service" to true), RequestOptions(directory = "/repo"))
        assertEquals("Inspect project", fixture.status().obj("ses_root").str("backgroundCommand"))
        fixture.jobs = Json.array(listOf(job()))
        fixture.event("session.step.started", Json.obj("sessionID" to "ses_root"))
        assertEquals("idle", fixture.status().obj("ses_root").str("type"))
        fixture.sessions["ses_root"]!!.apply { addProperty("parentID", "ses_child"); obj("location")!!.addProperty("directory", "/nested") }
        fixture.sessions["ses_child"]!!.remove("parentID")
        fixture.shells = emptyList()
        fixture.adapter.reset(); fixture.adapter.setServerVersion("2.2.6")
        assertEquals(Json.obj(), fixture.status())
    }

    @Test fun `older unidentified and prerelease servers do not probe native jobs`() {
        for (version in listOf(null, "2.0.5", "2.2.5", "2.2.6-beta.1")) {
            val fixture = Fixture()
            fixture.adapter.setServerVersion(version)
            assertEquals(true, fixture.status().obj("ses_root").bool("background"))
            assertFalse(fixture.calls.contains("/api/experimental/job"))
        }
    }

    @Test fun `missing route falls back until reconnect or version change`() {
        for (reconnect in listOf(true, false)) {
            val fixture = Fixture()
            fixture.jobFailure = "404 route not found"
            fixture.status(); fixture.status()
            assertEquals(1, fixture.calls.count { it == "/api/experimental/job" })
            fixture.jobFailure = null
            if (reconnect) fixture.event("server.connected", Json.obj()) else fixture.adapter.setServerVersion("2.2.7")
            fixture.event("session.step.started", Json.obj("sessionID" to "ses_root"))
            assertEquals(Json.obj(), fixture.status())
            assertEquals(2, fixture.calls.count { it == "/api/experimental/job" })
        }
    }

    @Test fun `authentication transport malformed and ancestry errors preserve pending work`() {
        for (failure in listOf("401 Unauthorized", "500 unavailable", "network failed", "ancestry", "malformed", "malformed-video", "malformed-session")) {
            val fixture = Fixture(listOf(job("image")))
            fixture.status()
            when (failure) {
                "ancestry" -> fixture.ancestryFailure = "503 ancestry unavailable"
                "malformed" -> fixture.jobs = Json.array(listOf(Json.obj("kind" to "image")))
                "malformed-video" -> fixture.jobs = Json.array(listOf(job("video").apply { remove("path") }))
                "malformed-session" -> fixture.sessions["ses_root"]!!.remove("location")
                else -> fixture.jobFailure = failure
            }
            assertThrows(Exception::class.java) { fixture.status() }
            val event = fixture.event("session.execution.succeeded", Json.obj("sessionID" to "ses_root")).single()
            assertEquals(true, event.obj("properties").obj("status").bool("background"))
        }
    }

    @Test fun `cyclic ancestry is bounded and deleted sessions are ignored`() {
        val fixture = Fixture(listOf(job("image", "ses_nested"), job("subagent", "ses_deleted")))
        fixture.sessions["ses_root"]!!.addProperty("parentID", "ses_nested")
        assertEquals(setOf("ses_root", "ses_child"), fixture.status().keySet())
        assertEquals(1, fixture.calls.count { it == "/api/session/ses_root" })
    }

    @Test fun `live shell creation exit interruption and reset win over in flight jobs`() {
        for (mutation in listOf("create", "exit", "interrupt", "reset", "cancel")) {
            val fixture = Fixture(if (mutation == "create") emptyList() else listOf(job()))
            fixture.shells = emptyList()
            if (mutation == "exit") fixture.event("shell.created", Json.obj("info" to shell()))
            var cancelled = false
            fixture.onJobs = {
                when (mutation) {
                    "create" -> { fixture.event("shell.created", Json.obj("info" to shell())); fixture.event("session.execution.succeeded", Json.obj("sessionID" to "ses_root")) }
                    "exit" -> fixture.event("shell.exited", Json.obj("id" to "sh_one"))
                    "interrupt" -> fixture.event("session.execution.interrupted", Json.obj("sessionID" to "ses_root"))
                    "reset" -> fixture.adapter.reset()
                    "cancel" -> cancelled = true
                }
            }
            when (mutation) {
                "reset" -> assertThrows(IllegalStateException::class.java) { fixture.status() }
                "cancel" -> assertThrows(CancellationException::class.java) { fixture.status(RequestOptions(directory = "/repo", isCancelled = { cancelled })) }
                "create" -> assertEquals(true, fixture.status().obj("ses_root").bool("background"))
                else -> assertEquals(Json.obj(), fixture.status())
            }
        }
    }

    @Test fun `native video titles take precedence over the output path`() {
        val fixture = Fixture(listOf(job("video").apply { addProperty("title", "Render the demo") }))
        fixture.shells = emptyList()
        assertEquals("Render the demo", fixture.status().obj("ses_root").str("backgroundCommand"))
    }

    private val list = "GET" to "/api/experimental/job?sessionID=ses_root&descendants=true&status=running"
    private val interrupt = "POST" to "/api/session/ses_root/interrupt?resume=false"
    private val deleteShell = "DELETE" to "/api/shell/sh_one?location%5Bdirectory%5D=%2Frepo"
    private fun cancel(id: String) = "POST" to "/api/experimental/job/$id/cancel"
    private fun Fixture.stop(): List<Pair<String, String>> {
        val from = requests.size
        assertEquals(true, adapter.request("POST", "/session/ses_root/abort", Json.obj(), RequestOptions(directory = "/repo")).data?.asBoolean)
        return requests.drop(from)
    }
    private fun Fixture.backgroundPending() = event("session.status.updated", Json.obj("sessionID" to "ses_root", "status" to Json.obj("type" to "idle")))
        .single().obj("properties").obj("status").bool("background") == true

    @Test fun `Stop cancels running jobs including descendants instead of deleting their shells on 2_4`() {
        val fixture = Fixture(listOf(job(), job("codemode"), job("video", "ses_child"), job("image", status = "completed")))
        fixture.adapter.setServerVersion("2.4.0")
        assertEquals(true, fixture.status().obj("ses_root").bool("background"))
        assertEquals(listOf(list, cancel("job_shell"), cancel("job_codemode"), cancel("job_video"), interrupt), fixture.stop())
        assertFalse(fixture.backgroundPending())
    }

    @Test fun `Stop keeps a service shell job running while cancelling other work`() {
        val fixture = Fixture(listOf(job(), job("video")))
        fixture.adapter.setServerVersion("2.4.1")
        fixture.adapter.request("PATCH", "/session/ses_root/background-process/sh_one", Json.obj("service" to true), RequestOptions(directory = "/repo"))
        val status = fixture.status().obj("ses_root")
        assertEquals(true, status.bool("background"))
        assertEquals(1L, status.long("backgroundServices"))
        assertEquals(listOf(list, cancel("job_video"), interrupt), fixture.stop())
        assertEquals(1, fixture.adapter.backgroundServiceCount("ses_root"))
    }

    @Test fun `older and prerelease servers keep shell-only Stop without probing cancellation`() {
        for (version in listOf("2.2.6", "2.3.2", "2.4.0-beta.1")) {
            val fixture = Fixture(listOf(job(), job("video")))
            fixture.adapter.setServerVersion(version)
            assertEquals(true, fixture.status().obj("ses_root").bool("background"))
            assertEquals(listOf(deleteShell, interrupt), fixture.stop())
        }
    }

    @Test fun `missing job routes fall back to deleting shells`() {
        for (missing in listOf("list", "cancel")) {
            val fixture = Fixture(listOf(job()))
            fixture.adapter.setServerVersion("2.4.0")
            assertEquals(true, fixture.status().obj("ses_root").bool("background"))
            if (missing == "list") fixture.jobFailure = "404 Not Found" else fixture.cancelFailure = "404 Not Found"
            val expected = if (missing == "list") listOf(list, deleteShell, interrupt) else listOf(list, cancel("job_shell"), deleteShell, interrupt)
            assertEquals(expected, fixture.stop())
        }
    }

    @Test fun `failed cancellation is reported instead of interrupting`() {
        val fixture = Fixture(listOf(job("video")))
        fixture.adapter.setServerVersion("2.4.0")
        assertEquals(true, fixture.status().obj("ses_root").bool("background"))
        fixture.cancelFailure = "500 Storage is read-only"
        val from = fixture.requests.size
        val failure = assertThrows(Exception::class.java) {
            fixture.adapter.request("POST", "/session/ses_root/abort", Json.obj(), RequestOptions(directory = "/repo"))
        }
        assertTrue(failure.message.orEmpty().contains("500 Storage is read-only"))
        assertFalse(fixture.requests.drop(from).contains(interrupt))
    }

    @Test fun `Stop only interrupts a running session without listing its jobs`() {
        val fixture = Fixture(listOf(job("video")))
        fixture.adapter.setServerVersion("2.4.0")
        fixture.active = Json.obj("ses_root" to Json.obj("type" to "running"))
        assertEquals("busy", fixture.status().obj("ses_root").str("type"))
        assertEquals(listOf(interrupt), fixture.stop())
    }

    @Test fun `older empty snapshot cannot replace a newer native job snapshot`() {
        val fixture = Fixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.onJobs = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val older = executor.submit<JsonObject> { fixture.status() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            fixture.onJobs = null
            fixture.jobs = Json.array(listOf(job("subagent")))
            assertEquals(true, fixture.status().obj("ses_root").bool("background"))
            release.countDown()
            assertEquals(true, older.get(5, TimeUnit.SECONDS).obj("ses_root").bool("background"))
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
