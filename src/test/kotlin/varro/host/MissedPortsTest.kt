package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import varro.store.VarroStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MissedPortsTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private fun item(id: String = "queued", owner: String = "sidebar", session: String = "s") =
        Json.obj("id" to id, "ownerViewId" to owner, "sessionId" to session, "text" to "do work", "paused" to false)

    @Test fun `queue admissions survive crashes and suppress stale renderer snapshots`() {
        val path = temporary.root.toPath().resolve("queue.json")
        val initial = Json.array(listOf(item()))
        val queue = QueuedDispatches(path, initial)
        val lease = queue.claim("sidebar", "s", "queued", false)!!
        assertNull(queue.claim("editor", "s", "queued", false))
        val request = Json.obj("method" to "POST", "path" to "/session/s/prompt_async",
            "body" to Json.obj("messageID" to "msg_unique"),
            "queuedMessageDispatch" to Json.obj("itemId" to "queued", "lease" to lease))
        assertFalse(queue.admit("editor", request))
        assertTrue(queue.admit("sidebar", request))
        assertFalse(queue.admit("sidebar", request))
        val recovered = QueuedDispatches(path, JsonArray())
        assertTrue(recovered.messages()[0].asJsonObject.bool("paused")!!)
        assertNull(recovered.claim("sidebar", "s", "queued", true))
        recovered.update("sidebar", JsonArray())
        assertEquals(1, recovered.messages().size())
        recovered.recover { Json.array(listOf(Json.obj("info" to Json.obj("id" to "msg_unique", "role" to "user")))) }
        assertEquals(0, recovered.messages().size())
        recovered.update("sidebar", initial)
        assertEquals(0, recovered.messages().size())
        assertEquals(0, QueuedDispatches(path, initial).messages().size())
    }

    @Test fun `queue keeps owner isolation and retires completed session claims`() {
        val initial = Json.array(listOf(item(), item("editor-item", "editor", "other")))
        val queue = QueuedDispatches(temporary.root.toPath().resolve("queue.json"), initial)
        queue.update("sidebar", Json.array(listOf(item(), item("stolen", "editor"))))
        assertEquals(listOf("editor-item", "queued"), queue.messages().map { it.asJsonObject.str("id") })
        val lease = queue.claim("sidebar", "s", "queued", false)!!
        queue.release("sidebar", "s", "queued", lease + 1)
        assertEquals(lease, queue.claim("sidebar", "s", "queued", false))
        val request = Json.obj("method" to "POST", "path" to "/session/s/prompt_async", "body" to JsonObject(),
            "queuedMessageDispatch" to Json.obj("itemId" to "queued", "lease" to lease))
        assertTrue(queue.admit("sidebar", request))
        assertNotNull(request.obj("body").str("messageID"))
        queue.complete(request, true)
        queue.update("sidebar", Json.array(listOf(item("next"))))
        assertNotNull(queue.claim("sidebar", "s", "next", false))
    }

    @Test fun `uncertain queue failure remains paused after transport failure and restart`() {
        val path = temporary.root.toPath().resolve("queue.json")
        val queue = QueuedDispatches(path, Json.array(listOf(item())))
        val lease = queue.claim("sidebar", "s", "queued", false)!!
        val request = Json.obj("method" to "POST", "path" to "/session/s/prompt_async", "body" to JsonObject(),
            "queuedMessageDispatch" to Json.obj("itemId" to "queued", "lease" to lease))
        assertTrue(queue.admit("sidebar", request))
        queue.complete(request, false)
        val recovered = QueuedDispatches(path, JsonArray())
        recovered.recover { error("server offline") }
        assertEquals(1, recovered.messages().size())
        assertNull(recovered.claim("sidebar", "s", "queued", true))
    }

    @Test fun `recycle retains the complete tree and restore preserves session identities`() {
        val store = VarroStore().apply {
            completedSessionUnreadIds = listOf("root", "child", "other")
            sessionUnreadState = Json.obj(
                "root" to Json.obj("unread" to true),
                "child" to Json.obj("unread" to true),
                "other" to Json.obj("unread" to true),
            )
        }
        val sessions = linkedMapOf("root" to Json.obj("id" to "root", "directory" to "/workspace", "time" to JsonObject()),
            "child" to Json.obj("id" to "child", "parentID" to "root", "directory" to "/workspace", "time" to Json.obj("archived" to 50)))
        val deleted = mutableListOf<String>()
        val trash = SessionTrash(store, { method, path, body, _ ->
            val id = path.split('/')[2]
            when {
                method == "DELETE" -> { deleted.add(id); Json.toElement(true) }
                path.endsWith("/children") -> Json.array(if (id == "root") listOf(sessions["child"]) else emptyList())
                method == "PATCH" -> sessions[id]!!.apply { add("time", body.asObjectOrNull().obj("time")) }.deepCopy()
                else -> sessions[id]!!.deepCopy()
            }
        }, { 1000 })
        trash.recycle("root", "/workspace")
        assertTrue(deleted.isEmpty())
        assertEquals(2, store.recycleBin[0].asJsonObject.arr("sessions")!!.size())
        assertEquals(setOf("other"), store.sessionUnreadState.keySet())
        assertEquals(listOf("other"), store.completedSessionUnreadIds)
        trash.remove("root", true)
        assertEquals(0L, sessions["root"].obj("time").long("archived"))
        assertEquals(50L, sessions["child"].obj("time").long("archived"))
        assertEquals(0, store.recycleBin.size())
        trash.recycle("root", "/workspace")
        trash.empty()
        assertEquals(listOf("root"), deleted)
    }

    @Test fun `unread reconciliation removes sessions missing from the catalog`() {
        val store = VarroStore().apply {
            sessionUnreadState = Json.obj(
                "current" to Json.obj("kind" to "completed", "unread" to true),
                "plan" to Json.obj("kind" to "plan-ready", "unread" to true),
                "deleted" to Json.obj("kind" to "completed", "unread" to true),
            )
        }
        store.completedSessionUnreadIds = listOf("current", "deleted")
        store.retainSessionUnreadState(setOf("current", "plan"))
        assertEquals(listOf("current"), store.completedSessionUnreadIds)
        assertEquals(setOf("current", "plan"), store.sessionUnreadState.keySet())
        store.removeSessionUnreadState(listOf("current"))
        assertEquals(setOf("plan"), store.sessionUnreadState.keySet())
    }

    @Test fun `failed restore and failed permanent deletion retain tombstones`() {
        val store = VarroStore().apply { recycleBin = Json.array(listOf(Json.obj("rootID" to "old",
            "sessions" to Json.array(listOf(Json.obj("id" to "old")))))) }
        val trash = SessionTrash(store, { _, _, _, _ -> error("503 unavailable") })
        assertThrows(IllegalStateException::class.java) { trash.remove("old", true) }
        assertEquals(1, store.recycleBin.size())
        assertThrows(IllegalStateException::class.java) { trash.empty() }
        assertEquals(1, store.recycleBin.size())
    }

    @Test fun `expired retained session is deleted before the tombstone is removed`() {
        val store = VarroStore().apply { recycleBin = Json.array(listOf(Json.obj("rootID" to "expired",
            "expiresAt" to 5, "sessions" to JsonArray()))) }
        var removed = false
        val trash = SessionTrash(store, { method, path, _, _ ->
            assertEquals("DELETE", method); assertEquals("/session/expired", path)
            assertEquals(1, store.recycleBin.size()); removed = true; Json.toElement(true)
        }, { 10 })
        assertEquals(0, trash.list().size())
        assertTrue(removed)
    }

    @Test fun `judge uses deny-all helper permissions and cleans up after failure`() {
        val calls = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        val judge = PermissionJudge({ "provider/model" }, { method, path, body, timeout ->
            calls.add("$method $path")
            assertTrue(timeout in 1..20000)
            when ("$method $path") {
                "POST /session" -> {
                    val rules = body.asObjectOrNull().arr("permission")!!
                    assertEquals("deny", rules[0].asJsonObject.str("action"))
                    Json.obj("id" to "helper")
                }
                "POST /session/helper/message" -> {
                    assertEquals("model", body.asObjectOrNull().obj("model").str("modelID"))
                    error("timeout")
                }
                else -> Json.toElement(true)
            }
        }, hidden::add)
        val result = judge.judge(Json.obj("permission" to Json.obj("type" to "bash", "sessionID" to "s", "title" to "npm test")))
        assertEquals("ask", result.str("decision"))
        assertEquals(listOf("helper"), hidden)
        assertEquals("DELETE /session/helper", calls.last())
    }

    @Test fun `judge parses structured contracts and rejects malformed decisions`() {
        listOf("structured", "structured_output", "structuredOutput").forEach { key ->
            assertEquals("allow", PermissionJudge.parseDecision(Json.obj("info" to Json.obj(key to
                Json.obj("decision" to "allow", "reason" to "Local tests", "actionSummary" to "Run local tests")))).str("decision"))
        }
        assertEquals("ask", PermissionJudge.parseDecision(Json.obj("parts" to Json.array(listOf(
            Json.obj("type" to "text", "text" to "Sure, allow it"))))).str("decision"))
        val judge = PermissionJudge({ "" }, { _, _, _, _ -> fail("External paths must not reach a model"); null }, {})
        assertEquals("ask", judge.judge(Json.obj("permission" to Json.obj("permission" to "external_directory", "sessionID" to "s"))).str("decision"))
    }

    @Test fun `permission save applies server rules before storing and rejects invalid actions`() {
        val store = VarroStore()
        val rules = Json.array(listOf(Json.obj("permission" to "bash", "pattern" to "npm test", "action" to "allow")))
        val service = PermissionService(store) { method, path, body, directory ->
            assertEquals("PATCH", method); assertEquals("/session/s", path); assertEquals("/workspace", directory)
            assertEquals(0, store.permissionRules.size())
            assertEquals(rules, body.asObjectOrNull().arr("permission"))
            Json.obj("permission" to rules)
        }
        assertEquals(rules, service.sessionRules("s", rules, "/workspace"))
        assertEquals(rules, store.permissionRules.arr("s"))
        val failed = PermissionService(VarroStore()) { _, _, _, _ -> error("offline") }
        assertThrows(IllegalStateException::class.java) { failed.sessionRules("s", rules, null) }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionService.validateRules(Json.array(listOf(Json.obj("permission" to "bash", "pattern" to "*", "action" to "approve"))))
        }
    }

    @Test fun `permission allow validates pending session and leaves the reply to the webview`() {
        val calls = mutableListOf<String>()
        val service = PermissionService(VarroStore()) { method, path, _, _ ->
            calls.add("$method $path")
            when (path) {
                "/permission" -> Json.array(listOf(Json.obj("id" to "p", "sessionID" to "s", "permission" to "bash", "always" to listOf("npm test"))))
                "/session/s" -> Json.obj("permission" to JsonArray())
                else -> Json.toElement(true)
            }
        }
        assertThrows(IllegalStateException::class.java) { service.allow(Json.obj("sessionId" to "wrong", "permissionId" to "p"), false, null) }
        val rules = service.allow(Json.obj("sessionId" to "s", "permissionId" to "p"), false, null)
        assertEquals("npm test", rules[0].asJsonObject.str("pattern"))
        assertEquals("PATCH /session/s", calls.last())
        assertFalse(calls.any { it.startsWith("POST") })
    }

    @Test fun `permission configuration respects scalar capabilities`() {
        val input = Json.obj("bash" to Json.obj("npm test" to "allow"), "webfetch" to "ask")
        assertEquals(input, PermissionService.toConfig(PermissionService.fromConfig(input)))
        assertThrows(IllegalArgumentException::class.java) {
            PermissionService.toConfig(Json.array(listOf(Json.obj("permission" to "webfetch", "pattern" to "https://example.com", "action" to "allow"))))
        }
    }

    @Test fun `Markdown export includes tools reasoning attachments and safe code fences`() {
        val markdown = SessionTranscript.render(Json.obj("id" to "s", "title" to "A conversation"), Json.array(listOf(
            Json.obj("info" to Json.obj("role" to "user"), "parts" to listOf(Json.obj("type" to "text", "text" to "Please check"))),
            Json.obj("info" to Json.obj("role" to "assistant", "providerID" to "p", "modelID" to "m"), "parts" to listOf(
                Json.obj("type" to "reasoning", "text" to "Reasoning text"),
                Json.obj("type" to "tool", "tool" to "bash", "state" to Json.obj("status" to "completed", "input" to Json.obj("command" to "test"), "output" to "```\noutput")),
                Json.obj("type" to "file", "filename" to "report.pdf", "url" to "file:///report.pdf")),
            ))))
        assertTrue(markdown.startsWith("# A conversation"))
        listOf("## User", "## Assistant", "Reasoning text", "### Tool: bash", "````\n```\noutput\n````", "report.pdf", "Model: p/m").forEach { assertTrue(it, markdown.contains(it)) }
    }

    @Test fun `Ralph command evidence overrides false PASS reports without guessing repeated commands`() {
        val iteration = Json.obj("filesChanged" to JsonArray())
        val messages = Json.array(listOf(Json.obj("info" to Json.obj("id" to "a", "role" to "assistant", "tokens" to Json.obj("input" to 10, "output" to 3)),
            "parts" to listOf(Json.obj("type" to "tool", "id" to "tool", "tool" to "bash", "state" to Json.obj("status" to "completed",
                "input" to Json.obj("command" to "npm test"), "metadata" to Json.obj("exit" to 1))),
                Json.obj("type" to "text", "text" to "test: PASS\nfmt: SKIPPED - no files")))))
        RalphRunner.applyReport(iteration, "child", messages)
        assertEquals("fail", iteration.obj("verification").str("test"))
        assertEquals("pass", iteration.obj("verificationEvidence").obj("test").str("reportedVerdict"))
        assertEquals("failed", RalphRunner.verificationStatus(iteration.obj("verification")!!))
        RalphRunner.applyReport(iteration, "child", messages)
        assertEquals(13L, iteration.obj("tokens").long("total"))
        assertEquals("unverified", RalphRunner.verificationStatus(RalphRunner.parseVerification("test: SKIPPED")))
        assertEquals("fail", RalphRunner.parseVerification("- **test**: PASS\n1. test: FAIL").str("test"))
    }

    private fun config() = Json.obj("managerSessionId" to "manager", "workspaceDirectory" to "/workspace",
        "planDocPath" to "plan.md", "iterations" to 1, "promptTemplate" to "Implement {{planContent}}", "permissionMode" to "full")

    @Test fun `Ralph executes primary verification and repair with durable prompt ids`() {
        val saved = AtomicReference(JsonObject())
        val done = CountDownLatch(1)
        val created = AtomicInteger()
        val prompts = mutableListOf<String>()
        val history = mutableMapOf<String, JsonArray>()
        val runner = RalphRunner(JsonObject(), "/workspace", saved::set, { state ->
            if (state.obj("runs").obj("manager").str("status") in setOf("done", "failed", "incomplete")) done.countDown()
        }, { method, path, body, directory ->
            assertEquals("/workspace", directory)
            when {
                path == "/session" -> Json.obj("id" to "child${created.incrementAndGet()}")
                path == "/session/status" -> JsonObject()
                path.endsWith("/prompt_async") -> {
                    val child = path.split('/')[2]
                    val promptId = body.asObjectOrNull().str("messageID")!!
                    assertEquals(promptId, saved.get().obj("manager").arr("iterations")!!.last().asJsonObject.str("promptId"))
                    prompts.add(path)
                    val text = when (prompts.size) { 1 -> "Implemented item"; 2 -> "test: FAIL - error"; else -> "test: PASS" }
                    history[child] = Json.array(listOf(Json.obj("info" to Json.obj("id" to promptId, "role" to "user")),
                        Json.obj("info" to Json.obj("id" to "a${prompts.size}", "role" to "assistant", "parentID" to promptId,
                            "time" to Json.obj("completed" to 1), "finish" to "stop"), "parts" to listOf(Json.obj("type" to "text", "text" to text)))))
                    Json.toElement(true)
                }
                path.endsWith("/message") -> history[path.split('/')[2]]
                else -> error("Unexpected $method $path")
            }
        }, { _, _ -> "- [ ] Work" }, pollMs = 1)
        try {
            runner.handle("ralph/start", Json.obj("config" to config()))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val run = saved.get().obj("manager")!!
            assertEquals(Json.stringify(run), "incomplete", run.str("status")) // plan remains unchecked
            assertEquals(3, prompts.size)
            assertEquals(2, created.get())
            assertEquals("passed", run.arr("iterations")!![0].asJsonObject.str("status"))
        } finally { runner.close() }
    }

    @Test fun `Ralph recovery waits for persisted prompt instead of sending twice`() {
        val config = config()
        val run = Json.obj("config" to config, "status" to "running", "iterations" to listOf(Json.obj(
            "index" to 1, "childSessionId" to "child", "status" to "running", "phase" to "verification",
            "promptId" to "persisted", "promptStartedAt" to System.currentTimeMillis(), "filesChanged" to JsonArray())))
        val done = CountDownLatch(1)
        val runner = RalphRunner(Json.obj("manager" to run), "/workspace", {}, { state ->
            if (state.obj("runs").obj("manager").str("status") == "done") done.countDown()
        }, { method, path, _, _ ->
            assertEquals("GET", method)
            if (path == "/session/status") JsonObject() else Json.array(listOf(
                Json.obj("info" to Json.obj("id" to "persisted", "role" to "user")),
                Json.obj("info" to Json.obj("id" to "a", "parentID" to "persisted", "role" to "assistant", "time" to Json.obj("completed" to 1)),
                    "parts" to listOf(Json.obj("type" to "text", "text" to "test: PASS")))))
        }, { _, _ -> "DONE" }, pollMs = 1)
        try { runner.reattach(); assertTrue(done.await(5, TimeUnit.SECONDS)) } finally { runner.close() }
    }

    @Test fun `Ralph pause aborts children and never sends verification after cancellation`() {
        val sent = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val snapshot = AtomicReference(JsonObject())
        val promptCount = AtomicInteger()
        val runner = RalphRunner(JsonObject(), "/workspace", snapshot::set, {}, { _, path, _, _ ->
            when {
                path == "/session" -> Json.obj("id" to "child")
                path.endsWith("/prompt_async") -> { promptCount.incrementAndGet(); sent.countDown(); Json.toElement(true) }
                path.endsWith("/abort") -> { aborted.countDown(); Json.toElement(true) }
                path == "/session/status" -> Json.obj("child" to Json.obj("type" to "busy"))
                else -> JsonArray()
            }
        }, { _, _ -> "Work remains" }, pollMs = 1)
        try {
            runner.handle("ralph/start", Json.obj("config" to config()))
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            runner.handle("ralph/pause", Json.obj("managerSessionId" to "manager"))
            assertTrue(aborted.await(5, TimeUnit.SECONDS))
            assertEquals("paused", snapshot.get().obj("manager").str("status"))
            assertEquals("aborted", snapshot.get().obj("manager").arr("iterations")!![0].asJsonObject.str("status"))
            assertEquals(1, promptCount.get())
        } finally { runner.close() }
    }

    @Test fun `Ralph rejects runs bound to another workspace`() {
        val runner = RalphRunner(JsonObject(), "/workspace", {}, {}, { _, _, _, _ -> fail("Must not dispatch"); null }, { _, _ -> "DONE" })
        try {
            val config = config().apply { addProperty("workspaceDirectory", "/other") }
            assertThrows(IllegalArgumentException::class.java) { runner.handle("ralph/start", Json.obj("config" to config)) }
        } finally { runner.close() }
    }

    @Test fun `idle maintenance respects disabled busy quiet retry and update intervals`() {
        var time = System.currentTimeMillis() + 100_000_000L
        var enabled = false
        var idle = true
        var succeeded = false
        var attempts = 0
        val maintenance = varro.server.IdleMaintenance({ enabled }, { idle }, { attempts++; succeeded }, { time })
        maintenance.tick(); time += 120000; maintenance.tick()
        assertEquals(0, attempts)
        enabled = true
        maintenance.tick(); time += 30000; idle = false; maintenance.tick()
        idle = true; maintenance.tick(); time += 59999; maintenance.tick()
        assertEquals(0, attempts)
        time++; maintenance.tick()
        assertEquals(1, attempts)
        time += 14 * 60000; maintenance.tick()
        assertEquals(1, attempts)
        time += 60000; succeeded = true; maintenance.tick()
        assertEquals(2, attempts)
        time += 5 * 60 * 60000; maintenance.tick()
        assertEquals(2, attempts)
        time += 60 * 60000; maintenance.tick()
        assertEquals(3, attempts)
    }

    @Test fun `definitively rejected queued sends can be retried with a fresh lease`() {
        val queue = QueuedDispatches(temporary.root.toPath().resolve("queue.json"), Json.array(listOf(item())))
        val lease = queue.claim("sidebar", "s", "queued", false)!!
        val request = Json.obj("method" to "POST", "path" to "/session/s/prompt_async", "body" to JsonObject(),
            "queuedMessageDispatch" to Json.obj("itemId" to "queued", "lease" to lease))
        assertTrue(queue.admit("sidebar", request))
        queue.complete(request, false, rejected = true)
        queue.update("sidebar", Json.array(listOf(item())))
        val next = queue.claim("sidebar", "s", "queued", false)!!
        assertTrue(next > lease)
        assertFalse(queue.admit("sidebar", request))
    }

    @Test fun `queued retry history reads validate a lease without consuming admission`() {
        val item = item().apply { addProperty("messageId", "msg_attempt") }
        val queue = QueuedDispatches(temporary.root.toPath().resolve("queue.json"), Json.array(listOf(item)))
        val lease = queue.claim("sidebar", "s", "queued", false)!!
        val request = Json.obj("method" to "GET", "path" to "/session/s/message?limit=200", "body" to Json.obj("messageID" to "msg_attempt"),
            "queuedMessageDispatch" to Json.obj("itemId" to "queued", "lease" to lease))
        assertTrue(queue.admit("sidebar", request))
        assertTrue(queue.admit("sidebar", request))
        queue.release("sidebar", "s", "queued", lease)
        assertFalse(queue.admit("sidebar", request))
        assertNotNull(queue.claim("sidebar", "s", "queued", false))
    }

    @Test fun `project permissions replace removed rules and preserve other JSONC settings`() {
        val file = temporary.newFile("opencode.jsonc").toPath()
        java.nio.file.Files.writeString(file, """
            {
              // Keep the provider setting, including comment-like strings.
              "provider": { "endpoint": "https://example.com/*path*/", },
              "permission": { "bash": { "*": "allow", }, },
            }
        """.trimIndent())
        val config = ProjectPermissionConfig(file.parent)
        assertEquals("allow", config.read()[0].asJsonObject.str("action"))
        config.write(JsonArray())
        val written = Json.parse(java.nio.file.Files.readString(file)).asJsonObject
        assertEquals("https://example.com/*path*/", written.obj("provider").str("endpoint"))
        assertEquals(0, written.obj("permission")!!.size())
        assertEquals(0, config.read().size())
    }

    @Test fun `project permission conversion refuses rule reordering that broadens access`() {
        val rules = Json.array(listOf(
            Json.obj("permission" to "bash", "pattern" to "secret", "action" to "deny"),
            Json.obj("permission" to "*", "pattern" to "*", "action" to "allow"),
            Json.obj("permission" to "bash", "pattern" to "delete", "action" to "deny")))
        assertThrows(IllegalArgumentException::class.java) { PermissionService.toConfig(rules) }
    }
}
