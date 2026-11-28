package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.util.concurrent.Executor

/** Drives [RestProxy.handleRequest] against a recording backend, with real transfer, grant and catalog state. */
class RestProxyRoutingTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private data class Call(val method: String, val path: String, val directory: String?, val unscoped: Boolean)

    private inner class Fixture(scope: String = "directory") {
        val calls = mutableListOf<Call>()
        var lastBody: JsonElement? = null
        var rejectionGuidance = PermissionReviewPolicy.guidance("default", "")
        val pending = mutableMapOf<String, String>()
        val records = mutableMapOf<String, JsonObject>()
        val observed = mutableMapOf<String, String>()
        var verified = true
        var responder: (String, String, String?) -> JsonElement? = { _, _, _ -> JsonArray() }
        var nextCursor: (String) -> String? = { null }
        val responses = mutableListOf<JsonObject>()
        val store = varro.store.VarroStore().apply { sessionHistoryScope = scope }

        private val backend = object : RestProxy.Backend {
            override val apiVersion = 2
            override fun request(method: String, path: String, body: JsonElement?, options: RequestOptions): OpenCodeResponse {
                options.checkCancelled()
                calls += Call(method, path, options.directory, options.unscoped)
                lastBody = body
                return OpenCodeResponse(responder(method, path, options.directory), if (options.captureNextCursor) nextCursor(path) else null)
            }
            override fun workspaceDirectory() = "/origin"
            override fun url() = "http://fixture.local:4096"
            override fun requestGeneration() = 1
            override fun isRunning() = true
            override fun isAttachOnly() = false
            override fun observedSessionDirectories() = observed.toMap()
            override fun pendingAttentionSessionId(requestId: String) = pending[requestId]
            override fun pendingAttentionSessionIds(): Collection<String> = pending.values.toSet()
            override fun savedPermissions(method: String, sessionID: String?, body: JsonElement?, options: RequestOptions) = error("Unexpected saved permissions")
            override fun activateDirectory(directory: String?) = error("Unexpected activation")
        }

        private val workspace = object : RestProxy.Workspace {
            override val basePath = "/origin"
            override fun folders() = emptyList<String>()
            override fun trashJournal(): java.nio.file.Path = temporary.root.toPath().resolve("trash.json")
            override fun citationSymbols(body: JsonElement?, directory: String?, authorize: (String?, String) -> Boolean, isCancelled: () -> Boolean) = error("Unexpected citations")
            override fun matchCopiedSelection(text: String, plainTextOnly: Boolean) = error("Unexpected selection")
            override fun problems() = error("Unexpected problems")
        }

        val transfers = SessionTransfers(JsonArray(), {}, { backend.url() }, { listOf("/origin") },
            { id -> records[id]?.deepCopy() }, {}, authorizeWorktree = { verified }, knownWorktree = { verified })
        val grants = SessionGrants({ listOf("/origin") }, { transfers.list() },
            { id -> transfers.get(id)?.obj("transfer")?.bool("available") == true },
            { observed[it] }, { id -> records[id]?.deepCopy() }, { 1 }, Executor { it.run() })
        val owners = CatalogOwners()
        val catalog = SessionCatalog("/origin", { listOf("/origin") }, { store.sessionHistoryScope },
            { Json.obj("id" to "prj_repo", "vcs" to "git") }, { id -> records[id]?.deepCopy() }, { 1 }, Executor { it.run() },
            grants::lineage, owners).also { owners.register(it) }

        private val services = java.lang.reflect.Proxy.newProxyInstance(RestProxy.HostServices::class.java.classLoader,
            arrayOf(RestProxy.HostServices::class.java)) { _, method, _ ->
            if (method.name == "clearFollowups") null else error("Unexpected host service ${method.name}")
        } as RestProxy.HostServices

        val proxy = RestProxy(backend, workspace, store, services, { responses += it },
            transfers = transfers, grants = grants, catalog = catalog, rejectionGuidance = { rejectionGuidance })

        private var nextId = 1

        /** Sends one request and returns its `data`, or throws its `error`. */
        fun send(method: String, path: String, body: JsonElement? = null): JsonElement? {
            val id = nextId++
            proxy.handleRequest(Json.obj("id" to id, "method" to method, "path" to path).apply { body?.let { add("body", it) } })
            val payload = responses.single { it.obj("payload").int("id") == id }.obj("payload")!!
            payload.str("error")?.let { throw IllegalStateException(it) }
            return payload.get("data")
        }

        fun session(id: String, directory: String, parent: String? = null, projectID: String = "prj_repo") =
            Json.obj("id" to id, "projectID" to projectID, "version" to "2", "title" to id, "directory" to directory,
                "time" to Json.obj("created" to 1000, "updated" to 2000)).apply { parent?.let { addProperty("parentID", it) } }

        /** A conversation that moved from `/origin` to `/dest`, with a subagent running there. */
        fun moved() {
            records["ses_conv"] = session("ses_conv", "/origin")
            transfers.observe(records.getValue("ses_conv"))
            records["ses_conv"] = session("ses_conv", "/dest")
            assertTrue(transfers.moved("ses_conv", "/dest", "/origin"))
            grants.observe(session("ses_child", "/dest", "ses_conv"))
            records["ses_child"] = session("ses_child", "/dest", "ses_conv")
        }

        fun last(method: String, prefix: String) = calls.last { it.method == method && it.path.startsWith(prefix) }
    }

    @Test fun `prompt forwarding applies the current rejection setting without changing existing system text`() {
        val fixture = Fixture()
        val body = Json.obj("system" to "Existing system text.")
        val route = "/session/ses_local/prompt_async"
        fixture.send("POST", route, body)
        val default = fixture.lastBody.asObjectOrNull().str("system")!!
        assertTrue(default.startsWith("Existing system text."))
        assertTrue(default.contains("If the rejection feedback or a later user message asks for a different approach"))

        fixture.rejectionGuidance = PermissionReviewPolicy.guidance("custom", "  Ask what to try next.  ")
        fixture.send("POST", route, body)
        assertTrue(fixture.lastBody.asObjectOrNull().str("system")!!.endsWith("Ask what to try next."))
        assertEquals(PermissionReviewPolicy.guidance("default", ""), PermissionReviewPolicy.guidance("custom", " "))

        fixture.rejectionGuidance = PermissionReviewPolicy.guidance("off", "Ignore this")
        fixture.send("POST", route, body)
        val disabled = fixture.lastBody.asObjectOrNull().str("system")!!
        assertFalse(disabled.contains("If the rejection feedback"))
        assertTrue(disabled.startsWith("Existing system text."))
        assertTrue(disabled.contains("File citations"))
        assertEquals("Existing system text.", body.str("system"))
    }

    @Test fun `a moved conversation and its subagents are read in their destination`() {
        val fixture = Fixture()
        fixture.moved()
        fixture.send("GET", "/session/ses_child/message?limit=20")
        assertEquals("/dest", fixture.last("GET", "/session/ses_child/message").directory)
        fixture.send("GET", "/session/ses_child")
        assertEquals("/dest", fixture.last("GET", "/session/ses_child").directory)
        fixture.send("GET", "/session/ses_conv")
        val conversation = fixture.last("GET", "/session/ses_conv")
        assertTrue(conversation.directory == "/dest" || conversation.path.contains("directory=%2Fdest"))
        // Sessions without a grant keep the workspace scope.
        fixture.send("GET", "/session/ses_local")
        assertNull(fixture.last("GET", "/session/ses_local").directory)
        fixture.grants.observe(fixture.session("ses_stray", "/elsewhere", "ses_conv"))
        fixture.send("GET", "/session/ses_stray")
        assertNull(fixture.last("GET", "/session/ses_stray").directory)
        // A child's read can name its destination; an unrelated session cannot.
        fixture.send("GET", "/session/ses_child/message?directory=%2Fdest")
        assertThrows(IllegalStateException::class.java) { fixture.send("GET", "/session/ses_local/message?directory=%2Fdest") }
    }

    @Test fun `subagent writes follow the moved conversation's writability`() {
        val fixture = Fixture()
        fixture.moved()
        fixture.verified = false
        val message = assertThrows(IllegalStateException::class.java) { fixture.send("POST", "/session/ses_child/abort", Json.obj()) }.message
        assertEquals("Could not verify the current location of this conversation. Refresh and try again.", message)
        assertTrue(fixture.calls.none { it.method == "POST" })
        fixture.verified = true
        fixture.send("POST", "/session/ses_child/abort", Json.obj())
        assertEquals("/dest", fixture.last("POST", "/session/ses_child/abort").directory)
        // A local session's writes are unaffected by retained links.
        fixture.send("POST", "/session/ses_local/abort", Json.obj())
        assertNull(fixture.last("POST", "/session/ses_local/abort").directory)
    }

    @Test fun `permission and question replies are sent to the prompt's folder`() {
        val fixture = Fixture(scope = "project")
        fixture.moved()
        fixture.catalog.observe(fixture.session("ses_tree", "/worktrees/feature"))
        fixture.pending += mapOf("per_child" to "ses_child", "que_child" to "ses_child", "per_local" to "ses_local", "per_tree" to "ses_tree")
        fixture.send("POST", "/permission/per_child/reply", Json.obj("reply" to "once"))
        assertEquals("/dest", fixture.last("POST", "/permission/per_child/reply").directory)
        fixture.send("POST", "/question/que_child/reply", Json.obj("answers" to listOf(listOf("Yes"))))
        assertEquals("/dest", fixture.last("POST", "/question/que_child/reply").directory)
        fixture.send("POST", "/question/que_child/reject")
        assertEquals("/dest", fixture.last("POST", "/question/que_child/reject").directory)
        fixture.send("POST", "/permission/per_tree/reply", Json.obj("reply" to "once"))
        assertEquals("/worktrees/feature", fixture.last("POST", "/permission/per_tree/reply").directory)
        fixture.send("POST", "/permission/per_local/reply", Json.obj("reply" to "once"))
        assertNull(fixture.last("POST", "/permission/per_local/reply").directory)
        // Without a verified link the child has no grant, so its reply keeps the workspace scope.
        fixture.verified = false
        fixture.send("POST", "/permission/per_child/reply", Json.obj("reply" to "once"))
        assertNull(fixture.last("POST", "/permission/per_child/reply").directory)
    }

    @Test fun `pending snapshots add only granted requests from destinations and reject inconclusive ancestry`() {
        val fixture = Fixture()
        fixture.moved()
        fixture.records["ses_foreign"] = fixture.session("ses_foreign", "/dest")
        // A request listed in the destination for a session whose canonical record is elsewhere is unrelated.
        fixture.records["ses_local"] = fixture.session("ses_local", "/origin")
        fixture.responder = { method, path, directory ->
            when {
                method == "GET" && path == "/permission" && directory == null -> Json.array(listOf(Json.obj("id" to "per_local", "sessionID" to "ses_local")))
                method == "GET" && path == "/permission" && directory == "/dest" -> Json.array(listOf(
                    Json.obj("id" to "per_child", "sessionID" to "ses_child"), Json.obj("id" to "per_foreign", "sessionID" to "ses_foreign"),
                    Json.obj("id" to "per_local", "sessionID" to "ses_local")))
                method == "GET" && path == "/permission" -> Json.array(emptyList<Any>())
                else -> JsonArray()
            }
        }
        val snapshot = fixture.send("GET", "/permission")!!.asJsonArray.map { it.asJsonObject.str("id") }
        assertEquals(listOf("per_local", "per_child"), snapshot)
        fixture.records.remove("ses_late")
        fixture.responder = { method, path, directory ->
            if (method == "GET" && path == "/permission" && directory == "/dest") Json.array(listOf(Json.obj("id" to "per_late", "sessionID" to "ses_late")))
            else JsonArray()
        }
        assertEquals("Could not resolve pending request session ancestry",
            assertThrows(IllegalStateException::class.java) { fixture.send("GET", "/permission") }.message)
        // Without retained, catalog or active destinations, the snapshot is the workspace's own:
        // one server-wide active-session read, and no other folder is queried.
        val plain = Fixture()
        plain.send("GET", "/question")
        assertEquals(listOf(Call("GET", "/question", null, false), Call("GET", "/api/session/active", null, true)), plain.calls)
    }

    /** A server-wide session list served in cursor pages, newest first. */
    private class ServerList(private val pages: List<List<JsonObject>>, private val trailingCursor: Boolean = false) {
        val requested = mutableListOf<String>()
        fun answer(path: String): JsonElement? {
            if (!path.startsWith("/experimental/session")) return null
            requested += path
            val cursor = Regex("cursor=([^&]+)").find(path)?.groupValues?.get(1)
            val index = cursor?.removePrefix("c")?.toInt() ?: 0
            return Json.array(pages.getOrElse(index) { emptyList() })
        }
        /** The next cursor after a page, as OpenCode reports it; a trailing cursor may follow the last page. */
        fun next(path: String): String? {
            val index = Regex("cursor=c(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
            return when {
                index + 1 < pages.size -> "c${index + 1}"
                trailingCursor && index < pages.size -> "c${index + 1}"
                else -> null
            }
        }
    }

    private fun Fixture.serve(list: ServerList) {
        responder = { _, path, _ -> list.answer(path) ?: JsonArray() }
        nextCursor = list::next
    }

    private fun Fixture.foreign(prefix: String, count: Int) = (0 until count).map { session("${prefix}_$it", "/elsewhere/$prefix$it", projectID = "prj_other") }

    private fun Fixture.page(limit: Int) = send("GET", "/session?limit=$limit")!!.asJsonObject

    @Test fun `scoped catalog pages read further server-wide pages until the page is full`() {
        val fixture = Fixture(scope = "project")
        val list = ServerList(listOf(
            fixture.foreign("a", 1_000),
            fixture.foreign("b", 997) + listOf(fixture.session("ses_n1", "/origin/n1"), fixture.session("ses_n2", "/origin/n2"),
                fixture.session("ses_t1", "/worktrees/one")),
            (1..5).map { fixture.session("ses_late$it", "/origin/late$it") },
            listOf(fixture.session("ses_unread", "/origin/unread")),
        ))
        fixture.serve(list)
        val page = fixture.page(4)
        assertEquals(4, page.arr("items")!!.size())
        assertEquals(true, page.bool("hasMore"))
        assertNull(page.get("incomplete"))
        // The third page fills it: the fourth is never read.
        assertEquals(listOf("/experimental/session?limit=1000", "/experimental/session?limit=1000&cursor=c1",
            "/experimental/session?limit=1000&cursor=c2"), list.requested)
        assertTrue(fixture.calls.filter { it.path.startsWith("/experimental/session") }.all { it.unscoped })
    }

    @Test fun `scoped catalog pages end with the list, including a trailing empty page`() {
        val fixture = Fixture(scope = "descendants")
        val list = ServerList(listOf(
            listOf(fixture.session("ses_n1", "/origin/n1")) + fixture.foreign("a", 999),
            listOf(fixture.session("ses_n2", "/origin/n2")),
        ), trailingCursor = true)
        fixture.serve(list)
        val page = fixture.page(10)
        assertEquals(setOf("ses_n1", "ses_n2"), page.arr("items")!!.map { it.asJsonObject.str("id") }.toSet())
        assertEquals(false, page.bool("hasMore"))
        assertEquals(3, list.requested.size)
    }

    @Test fun `a scoped catalog read stops at its page bound and reports itself incomplete`() {
        val fixture = Fixture(scope = "project")
        val list = ServerList((0 until 25).map { index -> fixture.foreign("p$index", 1_000) } +
            listOf(listOf(fixture.session("ses_deep", "/origin/deep"))))
        fixture.serve(list)
        val page = fixture.page(10)
        assertEquals(0, page.arr("items")!!.size())
        assertEquals(true, page.bool("hasMore"))
        assertEquals(true, page.bool("incomplete"))
        assertEquals(20, list.requested.size)
    }

    @Test fun `paging counts only sessions this window owns and shows`() {
        val fixture = Fixture(scope = "project")
        val apps = SessionCatalog("/origin/apps", { listOf("/origin/apps") }, { "directory" }, { null }, { null }, { 1 }, Executor { it.run() },
            owners = fixture.owners)
        fixture.owners.register(apps)
        fixture.store.hideSession("ses_hidden")
        val list = ServerList(listOf(
            (0 until 400).map { fixture.session("ses_apps$it", "/origin/apps") } + listOf(fixture.session("ses_hidden", "/origin/h"),
                fixture.session("ses_mine1", "/origin/one")) + fixture.foreign("a", 598),
            // The same session again after an update while paging, and the other window's sessions on every page.
            listOf(fixture.session("ses_mine1", "/origin/one"), fixture.session("ses_mine2", "/worktrees/two")) +
                (0 until 998).map { fixture.session("ses_apps_b$it", "/origin/apps") },
            listOf(fixture.session("ses_mine3", "/origin/three")),
        ))
        fixture.serve(list)
        val page = fixture.page(2)
        assertEquals(listOf("ses_mine1", "ses_mine2"), page.arr("items")!!.map { it.asJsonObject.str("id") }.sortedBy { it })
        assertEquals(true, page.bool("hasMore"))
        // Hidden, duplicate and other-window rows did not count toward the page.
        assertEquals(3, list.requested.size)
        assertNull(apps.ownedDirectory("ses_mine2"))
        assertEquals("/worktrees/two", fixture.catalog.ownedDirectory("ses_mine2"))
        assertFalse(fixture.catalog.ownsSession("ses_apps0"))
    }

    @Test fun `pending snapshots recover prompts in owned folders however long ago they were seen`() {
        val fixture = Fixture(scope = "project")
        fixture.catalog.loadProjects()
        // An old folder, then more than 64 newer ones: the old one is no longer among the recent folders.
        fixture.catalog.observe(fixture.session("ses_old", "/origin/old"))
        (0 until 80).forEach { fixture.catalog.observe(fixture.session("ses_recent$it", "/origin/recent$it")) }
        assertFalse("/origin/old" in fixture.catalog.ownedDirectories())
        fixture.records["ses_restart"] = fixture.session("ses_restart", "/worktrees/restart")
        fixture.records["ses_unrelated"] = fixture.session("ses_unrelated", "/elsewhere", projectID = "prj_other")
        fixture.records["ses_apps"] = fixture.session("ses_apps", "/origin/apps")
        val apps = SessionCatalog("/origin/apps", { listOf("/origin/apps") }, { "directory" }, { null }, { null }, { 1 }, Executor { it.run() },
            owners = fixture.owners)
        fixture.owners.register(apps)
        // A prompt this host saw before the webview reloaded, in a folder with no other activity.
        fixture.catalog.observe(fixture.session("ses_known", "/worktrees/known"))
        fixture.pending["per_known"] = "ses_known"
        val active = listOf("ses_old", "ses_restart", "ses_unrelated", "ses_apps")
        fixture.responder = { method, path, directory ->
            when {
                path == "/api/session/active" -> Json.obj("data" to Json.obj(*active.map { it to Json.obj("type" to "running") }.toTypedArray()))
                method == "GET" && path == "/session/ses_restart" -> fixture.records["ses_restart"]
                method == "GET" && path == "/session/ses_unrelated" -> fixture.records["ses_unrelated"]
                method == "GET" && path == "/session/ses_apps" -> fixture.records["ses_apps"]
                path == "/permission" -> Json.array(when (directory) {
                    "/origin/old" -> listOf(Json.obj("id" to "per_old", "sessionID" to "ses_old"))
                    "/worktrees/restart" -> listOf(Json.obj("id" to "per_restart", "sessionID" to "ses_restart"))
                    "/worktrees/known" -> listOf(Json.obj("id" to "per_known", "sessionID" to "ses_known"))
                    "/elsewhere" -> listOf(Json.obj("id" to "per_unrelated", "sessionID" to "ses_unrelated"))
                    "/origin/apps" -> listOf(Json.obj("id" to "per_apps", "sessionID" to "ses_apps"))
                    else -> emptyList()
                })
                else -> JsonArray()
            }
        }
        val snapshot = fixture.send("GET", "/permission")!!.asJsonArray.map { it.asJsonObject.str("id") }.toSet()
        assertEquals(setOf("per_old", "per_restart", "per_known"), snapshot)
        // Another project's folder and another window's exact folder are never queried.
        val queried = fixture.calls.filter { it.path == "/permission" }.mapNotNull { it.directory }.toSet()
        assertFalse("/elsewhere" in queried || "/origin/apps" in queried)
        // Located sessions are not read again.
        val reads = fixture.calls.count { it.path.startsWith("/session/ses_") }
        fixture.send("GET", "/permission")
        assertEquals(reads, fixture.calls.count { it.path.startsWith("/session/ses_") })
    }

    @Test fun `an active session that cannot be located rejects the pending snapshot`() {
        val fixture = Fixture(scope = "project")
        fixture.responder = { _, path, _ ->
            when (path) {
                "/api/session/active" -> Json.obj("data" to Json.obj("ses_lost" to Json.obj("type" to "running")))
                "/session/ses_lost" -> Json.obj("id" to "ses_other", "directory" to "/origin/lost")
                else -> JsonArray()
            }
        }
        assertEquals("Could not resolve pending request session ancestry",
            assertThrows(IllegalStateException::class.java) { fixture.send("GET", "/permission") }.message)
    }

    @Test fun `scoped catalog pages list nested and worktree sessions this window owns`() {
        val fixture = Fixture(scope = "project")
        val other = SessionCatalog("/origin/apps", { listOf("/origin/apps") }, { "directory" }, { null }, { null }, { 1 }, Executor { it.run() },
            owners = fixture.owners)
        fixture.owners.register(other)
        fixture.responder = { _, path, _ ->
            when {
                path.startsWith("/experimental/session") -> Json.array(listOf(
                    fixture.session("ses_root", "/origin"), fixture.session("ses_nested", "/origin/nested"),
                    fixture.session("ses_apps", "/origin/apps"), fixture.session("ses_tree", "/worktrees/feature"),
                    fixture.session("ses_foreign", "/elsewhere", projectID = "prj_other")))
                else -> JsonArray()
            }
        }
        val page = fixture.send("GET", "/session?limit=10")!!.asJsonObject
        assertEquals(setOf("ses_root", "ses_nested", "ses_tree"), page.arr("items")!!.map { it.asJsonObject.str("id") }.toSet())
        assertEquals(false, page.bool("hasMore"))
        val listing = fixture.calls.single { it.path.startsWith("/experimental/session") }
        assertTrue(listing.unscoped)
        assertEquals("/experimental/session?limit=1000", listing.path)
        // Their prompts are then owned by this window.
        assertEquals("/worktrees/feature", fixture.catalog.ownedDirectory("ses_tree"))
        assertNull(other.ownedDirectory("ses_tree"))

        // The `directory` scope keeps OpenCode's exact listing.
        fixture.store.sessionHistoryScope = "directory"
        fixture.calls.clear()
        fixture.send("GET", "/session?limit=10")
        assertEquals(listOf("/session?limit=10"), fixture.calls.map { it.path })
    }
}
