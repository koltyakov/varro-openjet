package varro.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.CatalogOwners
import varro.host.EventAccesses
import varro.host.GrantedEventRouter
import varro.host.HostEventRouting
import varro.host.RestProxy
import varro.host.SessionCatalog
import varro.host.SessionGrants
import varro.host.SessionLocations
import varro.host.SessionTransfers
import varro.protocol.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in released-binary test: a running conversation moves into a project worktree, a subagent
 * there asks for a permission, and the origin window's host receives, lists and answers it in the
 * destination. A second window must not receive it. Every server gets a separate HOME, XDG roots
 * and database.
 */
class OpenCodeV2MovedSubagentIntegrationTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test(timeout = 180_000) fun `released server routes a moved conversation's subagent permission to its destination`() = withServer { server ->
        val transport = server.transport
        val origin = server.origin
        val other = server.other
        val created = server.created
        val originOptions = server.originOptions
        val destination = server.worktree("moved-subagent")
        val destinationOptions = RequestOptions(directory = destination)

        val origins = server.window(listOf(origin.toString()))
        val bystander = server.window(listOf(other.toString()))
        server.connect(origins)

        // A conversation starts in the origin and is still generating when it moves.
        val conversation = transport.request("POST", "/session", Json.obj("title" to "Moved subagent fixture",
            "model" to Json.obj("providerID" to "fixture", "modelID" to "fixture")), originOptions).data.asObjectOrNull()!!
        val id = conversation.str("id")!!
        created += id
        origins.transfers.observe(conversation)
        val gate = CountDownLatch(1)
        server.conversationGate.set(gate)
        val before = server.conversationRequests.get()
        transport.request("POST", "/session/$id/prompt_async", Json.obj("parts" to listOf(Json.obj("type" to "text",
            "text" to "Keep working. Do not call tools."))), originOptions)
        waitFor("The conversation did not start generating") { server.conversationRequests.get() > before }
        assertEquals("busy", transport.request("GET", "/session/status", options = originOptions).data.asObjectOrNull().obj(id).str("type"))
        transport.request("POST", "/api/session/$id/move", Json.obj("directory" to destination), originOptions)
        // OpenCode 2.4.2 applies a running conversation's move at its next safe boundary, after this turn.
        gate.countDown()
        server.conversationGate.set(null)
        val movedDiagnostics = { "${origins.debug} ${origins.events.map { it.type + " in " + it.workspaceDirectory }.distinct().takeLast(30)} destination=$destination" }
        waitForWith({ "The origin window did not retain the moved conversation: " + movedDiagnostics() }) {
            origins.transfers.list().any { it.str("id") == id && WorkspacePaths.isSame(it.str("directory"), destination) }
        }
        assertEquals(true, origins.transfers.get(id).obj("transfer").bool("available"))
        assertTrue(bystander.transfers.list().isEmpty())
        waitFor("The moved conversation did not finish its turn") {
            transport.request("GET", "/session/status", options = destinationOptions).data.asObjectOrNull().obj(id)?.str("type") in listOf(null, "idle")
        }

        // A subagent starts in the destination and asks for a permission there.
        val subagent = CountDownLatch(1)
        server.subagentGate.set(subagent)
        val command = CompletableFuture.supplyAsync({
            transport.request("POST", "/session/$id/command", Json.obj("command" to "fixture-audit", "arguments" to "the move"), destinationOptions)
        }, server.hostExecutor)
        var child: JsonObject? = null
        waitFor("The subagent was not created") {
            child = transport.request("GET", "/session/$id/children", options = destinationOptions).data.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
            child != null
        }
        val childID = child.str("id")!!
        created += childID
        assertEquals(id, child.str("parentID"))
        assertTrue(WorkspacePaths.isSame(destination, child.str("directory")))
        waitFor("The subagent did not start generating") { server.subagentRequests.get() > 0 }
        // The server records the request and answers at once; the subagent's tool call would wait on it.
        val ask = transport.request("POST", "/api/session/$childID/permission", Json.obj("action" to "shell",
            "resources" to listOf("fixture subagent only"), "save" to listOf("fixture subagent only"), "metadata" to Json.obj()),
            destinationOptions).data.asObjectOrNull().obj("data")!!
        assertEquals("ask", ask.str("effect"))

        // The origin window receives the subagent's prompt through the inherited grant; another window does not.
        waitFor("The origin window did not receive the subagent's permission") {
            origins.delivered.any { it.type == "permission.asked" && it.properties.str("sessionID") == childID }
        }
        val asked = origins.delivered.first { it.type == "permission.asked" && it.properties.str("sessionID") == childID }
        assertTrue(WorkspacePaths.isSame(destination, asked.workspaceDirectory))
        val grant = origins.grants.grant(childID) ?: error("The subagent did not inherit the conversation's grant")
        assertEquals(id, grant.owner)
        assertTrue(WorkspacePaths.isSame(destination, grant.directory))
        assertTrue(bystander.delivered.none { it.properties.str("sessionID") in setOf(id, childID) })
        val requestID = asked.properties.str("id")!!
        assertEquals(ask.str("id"), requestID)

        // OpenCode lists the request only in the destination; the host's snapshot includes it.
        assertTrue(transport.request("GET", "/permission", options = originOptions).data.asArrayOrNull()!!.none { it.asObjectOrNull().str("id") == requestID })
        val snapshot = origins.proxy().send("GET", "/permission")!!.asJsonArray
        assertTrue(snapshot.toString(), snapshot.any { it.asObjectOrNull().str("id") == requestID && it.asObjectOrNull().str("sessionID") == childID })
        assertTrue(bystander.proxy().send("GET", "/permission")!!.asJsonArray.none { it.asObjectOrNull().str("id") == requestID })

        // After a reconnect the adapter no longer knows the request. Without folder routing a reply
        // looks in the origin and fails; the host sends it to the prompt's folder instead.
        val reconnected = server.newTransport(origin)
        try {
            assertTrue(reconnected.checkHealth())
            val unrouted = assertThrows(Exception::class.java) {
                reconnected.request("POST", "/permission/$requestID/reply", Json.obj("reply" to "once"), originOptions)
            }
            assertTrue("${unrouted::class.java.name}: ${unrouted.message} (api ${reconnected.apiVersion})", unrouted.message.orEmpty().contains("no longer pending"))
            assertTrue(transport.request("GET", "/permission", options = destinationOptions).data.asArrayOrNull()!!
                .any { it.asObjectOrNull().str("id") == requestID })
            assertEquals(true, origins.proxy(reconnected).send("POST", "/permission/$requestID/reply", Json.obj("reply" to "once"))?.asBoolean)
        } finally { reconnected.dispose() }
        waitFor("The destination still lists the answered permission") {
            transport.request("GET", "/permission", options = destinationOptions).data.asArrayOrNull()!!.none { it.asObjectOrNull().str("id") == requestID }
        }
        waitFor("The origin window did not receive the reply") {
            origins.delivered.any { it.type in setOf("permission.replied", "permission.v2.replied") && it.properties.toString().contains(requestID) }
        }

        subagent.countDown()
        server.subagentGate.set(null)
        command.get(60, TimeUnit.SECONDS)
        waitFor("The subagent result did not reach the moved conversation") {
            transport.request("GET", "/session/$id/message", options = destinationOptions).data!!.asJsonArray.any { message ->
                message.asObjectOrNull().arr("parts")!!.any { it.asObjectOrNull().obj("subagentResult").str("sessionID") == childID }
            }
        }
    }

    @Test(timeout = 180_000) fun `released server lists and routes nested and worktree sessions to the one window that owns them`() = withServer { server ->
        val transport = server.transport
        val origin = server.origin.toString()
        val docs = Files.createDirectories(server.origin.resolve("docs")).toString()
        val packageFolder = Files.createDirectories(server.origin.resolve("packages/app")).toString()
        val tree = server.worktree("catalog-tree")
        val owners = CatalogOwners()
        // One window has the project open with the project-wide history scope; another has a package folder open exactly.
        val project = server.window(listOf(origin), owners).apply { store.sessionHistoryScope = "project" }
        val pkg = server.window(listOf(packageFolder), owners)
        server.connect(project)

        val rootSession = server.session(origin, "Root fixture")
        val nestedSession = server.session(docs, "Nested fixture")
        val treeSession = server.session(tree, "Worktree fixture")
        val packageSession = server.session(packageFolder, "Package fixture")
        val foreignSession = server.session(server.other.toString(), "Unrelated fixture")

        // OpenCode lists sessions by exact directory, so the scoped catalog reads the server-wide list.
        val page = project.proxy().send("GET", "/session?limit=100")!!.asJsonObject.arr("items")!!.map { it.asJsonObject.str("id") }.toSet()
        val names = mapOf(rootSession to "root", nestedSession to "nested", treeSession to "tree", packageSession to "package", foreignSession to "foreign")
        assertEquals("${transport.request("GET", "/experimental/session?limit=50", options = RequestOptions(unscoped = true)).data}",
            setOf("root", "nested", "tree"), page.mapNotNull(names::get).toSet())
        // Two-session server pages exercise OpenCode's own cursor: the owned sessions span several pages.
        val paged = project.proxy(pageSize = 2).send("GET", "/session?limit=10")!!.asJsonObject
        assertEquals(setOf("root", "nested", "tree"), paged.arr("items")!!.mapNotNull { names[it.asJsonObject.str("id")] }.toSet())
        assertEquals(false, paged.bool("hasMore"))
        val first = project.proxy(pageSize = 2).send("GET", "/session?limit=2")!!.asJsonObject
        assertEquals(2, first.arr("items")!!.size())
        assertEquals(true, first.bool("hasMore"))
        assertEquals(setOf(packageSession), pkg.proxy().send("GET", "/session?limit=100")!!.asJsonObject.arr("items")!!.map { it.asJsonObject.str("id") }.toSet())

        // 2.4.0 model reads no longer wait for config plugins, so wait for each location's effective build-agent rules.
        for (directory in listOf(docs, tree, packageFolder)) waitFor("Build agent rules did not load in $directory") {
            OpenCodeV2Adapter.objects(transport.request("GET", "/api/agent", options = RequestOptions(directory = directory)).data.asObjectOrNull()?.get("data"))
                .firstOrNull { it.str("id") == "build" }?.arr("permissions").orEmpty().mapNotNull { it.asObjectOrNull() }
                .any { it.str("action") == "shell" && it.str("resource") == "*" && it.str("effect") == "ask" }
        }
        fun ask(session: String, directory: String) = transport.request("POST", "/api/session/$session/permission", Json.obj("action" to "shell",
            "resources" to listOf("fixture catalog only"), "save" to listOf("fixture catalog only"), "metadata" to Json.obj()),
            RequestOptions(directory = directory)).data.asObjectOrNull().obj("data")!!.also { assertEquals("ask", it.str("effect")) }.str("id")!!
        val nestedAsk = ask(nestedSession, docs)
        val treeAsk = ask(treeSession, tree)
        val packageAsk = ask(packageSession, packageFolder)
        fun Window.asked(request: String) = delivered.any { it.type == "permission.asked" && it.properties.str("id") == request }
        waitFor("The project window did not receive its nested and worktree prompts") { project.asked(nestedAsk) && project.asked(treeAsk) }
        waitFor("The package window did not receive its own prompt") { pkg.asked(packageAsk) }
        // Each prompt reaches exactly one window.
        assertFalse(project.asked(packageAsk))
        assertFalse(pkg.asked(nestedAsk) || pkg.asked(treeAsk))

        val snapshot = project.proxy().send("GET", "/permission")!!.asJsonArray.map { it.asJsonObject.str("id") }.toSet()
        assertTrue(snapshot.toString(), snapshot.containsAll(listOf(nestedAsk, treeAsk)))
        assertFalse(packageAsk in snapshot)
        assertTrue(transport.request("GET", "/permission", options = server.originOptions).data.asArrayOrNull()!!
            .none { it.asObjectOrNull().str("id") in setOf(nestedAsk, treeAsk) })

        // After a reconnect, replies still reach each prompt's own folder.
        val reconnected = server.newTransport(server.origin)
        try {
            assertTrue(reconnected.checkHealth())
            assertTrue(assertThrows(Exception::class.java) {
                reconnected.request("POST", "/permission/$treeAsk/reply", Json.obj("reply" to "once"), server.originOptions)
            }.message.orEmpty().contains("no longer pending"))
            for (request in listOf(nestedAsk, treeAsk)) {
                assertEquals(true, project.proxy(reconnected).send("POST", "/permission/$request/reply", Json.obj("reply" to "once"))?.asBoolean)
            }
        } finally { reconnected.dispose() }
        for ((request, directory) in listOf(nestedAsk to docs, treeAsk to tree)) {
            waitFor("The answered permission is still pending in $directory") {
                transport.request("GET", "/permission", options = RequestOptions(directory = directory)).data.asArrayOrNull()!!
                    .none { it.asObjectOrNull().str("id") == request }
            }
        }
        assertEquals(true, pkg.proxy().send("POST", "/permission/$packageAsk/reply", Json.obj("reply" to "once"))?.asBoolean)
    }

    @Test(timeout = 180_000) fun `released server recovers a blocked tool prompt in an owned folder after a restart`() = withServer { server ->
        val transport = server.transport
        val origin = server.origin.toString()
        val folder = Files.createDirectories(server.origin.resolve("tools/old")).toString()
        val before = server.window(listOf(origin)).apply { store.sessionHistoryScope = "project" }
        server.connect(before)
        waitFor("Build agent rules did not load in $folder") {
            OpenCodeV2Adapter.objects(transport.request("GET", "/api/agent", options = RequestOptions(directory = folder)).data.asObjectOrNull()?.get("data"))
                .firstOrNull { it.str("id") == "build" }?.arr("permissions").orEmpty().mapNotNull { it.asObjectOrNull() }
                .any { it.str("action") == "shell" && it.str("resource") == "*" && it.str("effect") == "ask" }
        }
        val id = transport.request("POST", "/session", Json.obj("title" to "Blocked tool fixture", "agent" to "build",
            "model" to Json.obj("providerID" to "fixture", "modelID" to "fixture-tool")), RequestOptions(directory = folder))
            .data.asObjectOrNull().str("id")!!.also(server.created::add)
        transport.request("POST", "/session/$id/prompt_async", Json.obj("parts" to listOf(Json.obj("type" to "text", "text" to "Run it."))),
            RequestOptions(directory = folder))
        var request: JsonObject? = null
        waitFor("The tool call did not raise a permission") {
            request = transport.request("GET", "/permission", options = RequestOptions(directory = folder)).data.asArrayOrNull()
                ?.firstOrNull { it.asObjectOrNull().str("sessionID") == id }?.asObjectOrNull()
            request != null
        }
        val requestID = request.str("id")!!
        // The blocked session is among the server-wide active sessions.
        assertTrue(transport.request("GET", "/api/session/active", options = RequestOptions(unscoped = true)).data.asObjectOrNull()
            .obj("data")!!.has(id))

        // After a restart nothing about the folder is known: no events, records or pending prompts.
        val restarted = server.newTransport(server.origin)
        try {
            assertTrue(restarted.checkHealth())
            val after = Window(restarted, server.url, listOf(origin), server.hostExecutor, CatalogOwners()).apply { store.sessionHistoryScope = "project" }
            assertTrue(after.catalog.ownedDirectories().isEmpty())
            val proxy = after.proxy(restarted)
            val snapshot = proxy.send("GET", "/permission")!!.asJsonArray
            assertTrue(snapshot.toString(), snapshot.any { it.asObjectOrNull().str("id") == requestID })
            assertEquals("/permission scoped to the origin omits it", true, restarted.request("GET", "/permission", options = server.originOptions)
                .data.asArrayOrNull()!!.none { it.asObjectOrNull().str("id") == requestID })
            // A window with the folder outside its catalog does not adopt it.
            val unrelated = Window(restarted, server.url, listOf(server.other.toString()), server.hostExecutor, CatalogOwners())
            assertTrue(unrelated.proxy(restarted).send("GET", "/permission")!!.asJsonArray.none { it.asObjectOrNull().str("id") == requestID })
        } finally { restarted.dispose() }
        // The recovered prompt is answered in its folder and the turn completes.
        assertEquals(true, before.proxy().send("POST", "/permission/$requestID/reply", Json.obj("reply" to "once"))?.asBoolean)
        waitFor("The blocked session did not finish after its reply") {
            transport.request("GET", "/api/session/active", options = RequestOptions(unscoped = true)).data.asObjectOrNull().obj("data")?.has(id) == false
        }
    }

    /** One isolated released server, its fixture provider and the project it serves. */
    private inner class Server(
        val root: Path, val origin: Path, val other: Path, val url: String, val transport: OpenCodeTransport,
        val hostExecutor: java.util.concurrent.ExecutorService, private val listeners: CopyOnWriteArrayList<(JsonElement) -> Unit>,
        val newTransport: (Path) -> OpenCodeTransport,
        val conversationGate: AtomicReference<CountDownLatch?>, val subagentGate: AtomicReference<CountDownLatch?>,
        val conversationRequests: java.util.concurrent.atomic.AtomicInteger, val subagentRequests: java.util.concurrent.atomic.AtomicInteger,
    ) {
        val created = mutableListOf<String>()
        val originOptions = RequestOptions(directory = origin.toString())

        fun worktree(name: String): String {
            val projectID = transport.request("GET", "/project/current", options = originOptions).data.asObjectOrNull().str("id")!!
            return transport.request("POST", "/api/worktree", Json.obj("projectID" to projectID,
                "directory" to root.resolve("worktrees").toString(), "name" to name), originOptions).data.asObjectOrNull().str("directory")!!
        }

        fun window(roots: List<String>, owners: CatalogOwners = CatalogOwners()) = Window(transport, url, roots, hostExecutor, owners).also {
            listeners += it::onEvent
        }

        fun connect(first: Window) {
            transport.startEventStream()
            waitFor("Event stream did not connect") { first.events.any { it.type == "server.connected" } }
        }

        fun session(directory: String, title: String): String =
            transport.request("POST", "/session", Json.obj("title" to title, "agent" to "build"), RequestOptions(directory = directory))
                .data.asObjectOrNull().str("id")!!.also(created::add)
    }

    private fun withServer(test: (Server) -> Unit) {
        val binary = System.getenv("VARRO_OPENCODE_TEST_BINARY")
        assumeTrue("Set VARRO_OPENCODE_TEST_BINARY to a released v2 CLI", !binary.isNullOrBlank())
        val root = temporary.newFolder().toPath().toRealPath()
        val origin = Files.createDirectory(root.resolve("project"))
        val other = Files.createDirectory(root.resolve("other-window"))

        val conversationGate = AtomicReference<CountDownLatch?>()
        val subagentGate = AtomicReference<CountDownLatch?>()
        val conversationRequests = java.util.concurrent.atomic.AtomicInteger()
        val subagentRequests = java.util.concurrent.atomic.AtomicInteger()
        val provider = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val providerExecutor = Executors.newCachedThreadPool { Thread(it, "fixture-provider").apply { isDaemon = true } }
        provider.executor = providerExecutor
        provider.createContext("/") { exchange ->
            val input = Json.parse(exchange.requestBody.bufferedReader().readText()).asJsonObject
            val subagent = input.str("model") == "fixture-command"
            if (subagent) subagentRequests.incrementAndGet() else conversationRequests.incrementAndGet()
            if (input.bool("stream") == true) (if (subagent) subagentGate else conversationGate).get()?.await(60, TimeUnit.SECONDS)
            val usage = Json.obj("prompt_tokens" to 10, "completion_tokens" to 3, "total_tokens" to 13)
            // The tool model calls the shell once, so the server raises a real, blocking permission.
            val toolCall = input.str("model") == "fixture-tool" && input.arr("messages").orEmpty().none { it.asObjectOrNull().str("role") == "tool" }
            val response = if (toolCall) {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                val call = Json.obj("index" to 0, "id" to "call_fixture", "type" to "function", "function" to Json.obj("name" to "shell",
                    "arguments" to Json.stringify(Json.obj("command" to "echo recovered", "description" to "Recovered fixture command"))))
                listOf(
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture",
                        "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj("tool_calls" to listOf(call)), "finish_reason" to null))),
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture",
                        "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj(), "finish_reason" to "tool_calls")), "usage" to usage),
                ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            } else if (input.bool("stream") == true) {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                listOf(
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture",
                        "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj("content" to "Fixture reply."), "finish_reason" to null))),
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture",
                        "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj(), "finish_reason" to "stop")), "usage" to usage),
                ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion", "created" to 1, "model" to "fixture", "choices" to listOf(
                    Json.obj("index" to 0, "message" to Json.obj("role" to "assistant", "content" to "Fixture reply."), "finish_reason" to "stop")),
                    "usage" to usage).toString()
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        provider.start()
        val baseURL = "http://127.0.0.1:${provider.address.port}/v1"

        // Committed project configuration, so the worktree destination has the same provider, command and rules.
        Files.writeString(origin.resolve("opencode.json"), Json.stringify(Json.obj(
            "model" to "fixture/fixture",
            "provider" to Json.obj("fixture" to Json.obj("npm" to "@ai-sdk/openai-compatible", "name" to "Fixture",
                "options" to Json.obj("baseURL" to baseURL, "apiKey" to "fixture-only"),
                "models" to Json.obj(*listOf("fixture", "fixture-command", "fixture-tool").map {
                    it to Json.obj("name" to it, "tool_call" to true, "limit" to Json.obj("context" to 32000, "output" to 1000))
                }.toTypedArray()))),
            "permissions" to listOf(Json.obj("action" to "shell", "resource" to "*", "effect" to "ask")),
        )))
        val commands = Files.createDirectories(origin.resolve(".opencode/commands"))
        Files.writeString(commands.resolve("fixture-audit.md"),
            "---\ndescription: Audit fixture\nagent: general\nmodel: fixture/fixture-command\nsubagent: true\n---\nAudit \$ARGUMENTS. Do not call tools.\n")
        val global = Files.createDirectories(root.resolve("config/opencode"))
        Files.writeString(global.resolve("opencode.json"), Json.stringify(Json.obj(
            "\$schema" to "https://opencode.ai/config.json", "providers" to Json.obj("fixture" to Json.obj(
                "package" to "@opencode/ai/providers/openai-compatible",
                "settings" to Json.obj("baseURL" to baseURL, "apiKey" to "fixture-only"),
                "models" to Json.obj(*listOf("fixture", "fixture-command", "fixture-tool").map {
                    it to Json.obj("name" to it, "limit" to Json.obj("context" to 32000, "output" to 1000))
                }.toTypedArray()))))))
        git(root, origin, "init", "--quiet", "--template=")
        git(root, origin, "add", "-A")
        git(root, origin, "-c", "user.name=Varro test", "-c", "user.email=varro-test@example.invalid",
            "-c", "core.hooksPath=${Files.createDirectory(root.resolve("empty-git-hooks"))}", "-c", "commit.gpgsign=false",
            "commit", "--quiet", "-m", "Moved subagent fixture")

        val environment = System.getenv().filterKeys { it.equals("PATH", true) || it.equals("SystemRoot", true) } + mapOf(
            "HOME" to root.toString(), "USERPROFILE" to root.toString(), "LOCALAPPDATA" to root.resolve("local").toString(),
            "APPDATA" to root.resolve("appdata").toString(), "TMPDIR" to root.toString(), "TEMP" to root.toString(),
            "XDG_CONFIG_HOME" to root.resolve("config").toString(), "XDG_DATA_HOME" to root.resolve("data").toString(),
            "XDG_STATE_HOME" to root.resolve("state").toString(), "XDG_CACHE_HOME" to root.resolve("cache").toString(),
            "OPENCODE_DB" to root.resolve("test.db").toString(), "VARRO_TEST_STATE_ROOT" to root.toString(),
            "OPENCODE_DISABLE_FREE_MODELS" to "true",
        )
        val installedVersion = OpenCodeCli({ binary!! }, { origin.toString() }, environment).readInstalledVersion()
            ?: error("Could not verify the released fixture CLI version")
        System.getenv("VARRO_OPENCODE_TEST_VERSION")?.let { assertEquals(it, installedVersion) }

        val port = ServerSocket(0).use { it.localPort }
        val password = AtomicReference<String?>()
        val parser = OpenCodeStartupOutput { password.set(it) }
        val process = ProcessBuilder(binary!!, "serve", "--port", port.toString()).directory(origin.toFile()).redirectErrorStream(true).apply {
            environment().clear()
            environment().putAll(environment)
        }.start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.reader().use { stream ->
                val buffer = CharArray(4096)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    synchronized(output) { output.append(parser.write(String(buffer, 0, size))) }
                }
            }
        }.apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:$port"
        val hostExecutor = Executors.newCachedThreadPool { Thread(it, "fixture-host").apply { isDaemon = true } }
        val listeners = CopyOnWriteArrayList<(JsonElement) -> Unit>()
        fun newTransport(directory: Path) = OpenCodeTransport({ url }, { directory.toString() },
            { ServerStatus.Running(url, EventStreamState.HEALTHY) }, { false }, {}, { event -> listeners.forEach { it(event) } },
            { password.get()?.let(OpenCodeConnection::authorization) }, root.resolve("annotations-${directory.fileName}"))
        val transport = newTransport(origin)
        val server = Server(root, origin, other, url, transport, hostExecutor, listeners, ::newTransport,
            conversationGate, subagentGate, conversationRequests, subagentRequests)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!transport.checkHealth() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue(synchronized(output) { output.toString() }, transport.checkHealth())
            assertEquals(2, transport.apiVersion)
            assertEquals(installedVersion, transport.readHealthInfo().version)
            test(server)
        } finally {
            conversationGate.get()?.countDown()
            subagentGate.get()?.countDown()
            server.created.reversed().forEach { runCatching { transport.request("DELETE", "/session/$it") } }
            transport.dispose()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue("Isolated OpenCode fixture did not stop", process.waitFor(5, TimeUnit.SECONDS))
            }
            reader.join(1000)
            provider.stop(0)
            providerExecutor.shutdownNow()
            hostExecutor.shutdownNow()
        }
    }

    /** One IDE window's host state, wired as `VarroProjectService` wires it. */
    private inner class Window(private val transport: OpenCodeTransport, private val url: String, val roots: List<String>,
        private val executor: java.util.concurrent.ExecutorService, owners: CatalogOwners) {
        val events = CopyOnWriteArrayList<ParsedServerEvent>()
        @Volatile var debug = ""
        val delivered = CopyOnWriteArrayList<ParsedServerEvent>()
        val store = varro.store.VarroStore()
        private val connection = "fixture"
        private val locations = SessionLocations(transport::request, { transport.apiVersion }, { connection }, { roots })
        val transfers = SessionTransfers(JsonArray(), {}, { url }, { roots },
            { id -> transport.request("GET", "/session/$id", options = RequestOptions(unscoped = true)).data }, {},
            authorizeWorktree = locations::authorizeTransferred, knownWorktree = locations::knownTransferred)
        private fun read(id: String) = transport.request("GET", "/session/$id", options = RequestOptions(unscoped = true, timeoutMs = 5_000)).data.asObjectOrNull()
        val grants = SessionGrants({ roots }, { transfers.list() }, { id -> transfers.get(id)?.obj("transfer")?.bool("available") == true },
            { transport.observedSessionDirectory(it) }, ::read, { connection }, executor)
        val catalog = SessionCatalog(roots.first(), { roots }, { store.sessionHistoryScope },
            { root -> SessionCatalog.currentProject({ path, directory -> transport.request("GET", path, options = RequestOptions(directory = directory)).data }, root) },
            ::read, { connection }, executor, grants::lineage, owners).also { owners.register(it) }
        private val router = GrantedEventRouter<ParsedServerEvent>(EventAccesses(grants, catalog), { false },
            { event -> (event.properties.str("id") ?: event.properties.str("requestID"))?.let(transport::isPendingAttentionRequest) == true },
            { delivered += it })
        private val routing = HostEventRouting({ roots }, grants, catalog, router)

        fun onEvent(event: JsonElement) {
            val parsed = ServerEvents.parse(event) ?: return
            events += parsed
            val info = parsed.properties.obj("info")
            val sessionId = parsed.properties.text("sessionID") ?: info.text("id") ?: parsed.properties.obj("part").text("sessionID")
            if (parsed.type == "session.next.moved") {
                val directory = parsed.properties.obj("location").text("directory") ?: return
                val id = parsed.properties.text("sessionID") ?: return
                executor.execute {
                    val owns = transfers.owns(id, parsed.workspaceDirectory)
                    debug += "moved(owns=$owns, origin=${parsed.workspaceDirectory}, to=$directory)=" +
                        runCatching { if (owns) transfers.moved(id, directory, parsed.workspaceDirectory) else false }
                }
                return
            }
            routing.route(parsed, parsed.type, parsed.properties, parsed.workspaceDirectory, sessionId)
        }

        fun proxy(backend: OpenCodeTransport = transport, pageSize: Int = 1_000) = Proxy(backend, transport, this, pageSize)
    }

    /** A `RestProxy` for one window, sending through `requests` while pending prompts come from the live stream. */
    private inner class Proxy(private val requests: OpenCodeTransport, private val stream: OpenCodeTransport, private val window: Window,
        pageSize: Int) {
        private val responses = CopyOnWriteArrayList<JsonObject>()
        private val backend = object : RestProxy.Backend {
            override val apiVersion get() = requests.apiVersion
            // Each window's own transport defaults to that window's folder.
            override fun request(method: String, path: String, body: JsonElement?, options: RequestOptions) = requests.request(method, path, body,
                if (options.directory == null && !options.unscoped) options.copy(directory = window.roots.first()) else options)
            override fun workspaceDirectory() = window.roots.first()
            override fun url() = "fixture"
            override fun requestGeneration() = 1
            override fun isRunning() = true
            override fun isAttachOnly() = false
            override fun observedSessionDirectories() = stream.observedSessionDirectories()
            override fun pendingAttentionSessionId(requestId: String) = stream.pendingAttentionSessionId(requestId)
            override fun pendingAttentionSessionIds(): Collection<String> = stream.pendingAttentionSessionIds()
            override fun savedPermissions(method: String, sessionID: String?, body: JsonElement?, options: RequestOptions) = error("Unexpected saved permissions")
            override fun activateDirectory(directory: String?) = error("Unexpected activation")
        }
        private val workspace = object : RestProxy.Workspace {
            override val basePath = window.roots.first()
            override fun folders() = emptyList<String>()
            override fun trashJournal(): Path = temporary.root.toPath().resolve("trash-${window.roots.first().hashCode()}.json")
            override fun citationSymbols(body: JsonElement?, directory: String?, authorize: (String?, String) -> Boolean, isCancelled: () -> Boolean) = error("Unexpected citations")
            override fun matchCopiedSelection(text: String, plainTextOnly: Boolean) = error("Unexpected selection")
            override fun problems() = error("Unexpected problems")
        }
        private val services = java.lang.reflect.Proxy.newProxyInstance(RestProxy.HostServices::class.java.classLoader,
            arrayOf(RestProxy.HostServices::class.java)) { _, method, _ -> error("Unexpected host service ${method.name}") } as RestProxy.HostServices
        private val proxy = RestProxy(backend, workspace, window.store, services, { responses += it },
            transfers = window.transfers, grants = window.grants, catalog = window.catalog, scopedCatalogPage = pageSize)
        private var next = 1

        fun send(method: String, path: String, body: JsonElement? = null): JsonElement? {
            val id = next++
            proxy.handleRequest(Json.obj("id" to id, "method" to method, "path" to path).apply { body?.let { add("body", it) } })
            val payload = responses.single { it.obj("payload").int("id") == id }.obj("payload")!!
            payload.str("error")?.let { error(it) }
            return payload.get("data")
        }
    }

    private fun git(root: Path, directory: Path, vararg arguments: String) {
        val builder = ProcessBuilder(listOf("git") + arguments).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().apply {
            keys.filter { it.startsWith("GIT_", ignoreCase = true) }.toList().forEach(::remove)
            put("HOME", root.resolve("home").toString())
            put("XDG_CONFIG_HOME", root.resolve("config").toString())
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", root.resolve("empty-gitconfig").toString())
            put("GIT_TERMINAL_PROMPT", "0")
        }
        val process = builder.start()
        try {
            assertTrue("Fixture Git timed out", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun waitForWith(message: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            if (System.nanoTime() >= deadline) fail(message())
            Thread.sleep(50)
        }
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            assertTrue(message, System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }
}
