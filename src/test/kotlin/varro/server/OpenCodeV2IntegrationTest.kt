package varro.server

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in released-binary test. Every server gets a separate HOME, XDG roots and database. */
class OpenCodeV2IntegrationTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test(timeout = 180_000) fun `released server supports authenticated lifecycle history and pending input`() {
        val binary = System.getenv("VARRO_OPENCODE_TEST_BINARY")
        assumeTrue("Set VARRO_OPENCODE_TEST_BINARY to a released v2 CLI", !binary.isNullOrBlank())
        val root = temporary.newFolder().toPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val providerRequests = java.util.concurrent.atomic.AtomicInteger()
        val providerInputs = CopyOnWriteArrayList<com.google.gson.JsonObject>()
        val oneShotOutput = AtomicReference("OpenJet stream verified.")
        val streamGate = AtomicReference<java.util.concurrent.CountDownLatch?>()
        val provider = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val providerExecutor = java.util.concurrent.Executors.newCachedThreadPool { runnable -> Thread(runnable, "fixture-provider").apply { isDaemon = true } }
        provider.executor = providerExecutor
        provider.createContext("/") { exchange ->
            providerRequests.incrementAndGet()
            val input = Json.parse(exchange.requestBody.bufferedReader().readText()).asJsonObject
            providerInputs.add(input)
            if (input.str("model") == "fixture-error") {
                val bytes = Json.stringify(Json.obj("error" to Json.obj("message" to "Fixture child execution failed", "type" to "invalid_request_error"))).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            val usage = Json.obj("prompt_tokens" to 10, "completion_tokens" to 3, "total_tokens" to 13)
            val response = if (input.bool("stream") == true) {
                streamGate.get()?.await(20, TimeUnit.SECONDS)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                val chunks = listOf(
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture", "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj("content" to oneShotOutput.get()), "finish_reason" to null))),
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture", "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj(), "finish_reason" to "stop")), "usage" to usage))
                chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion", "created" to 1, "model" to "fixture", "choices" to listOf(
                    Json.obj("index" to 0, "message" to Json.obj("role" to "assistant", "content" to oneShotOutput.get()), "finish_reason" to "stop")), "usage" to usage).toString()
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        provider.start()
        Files.writeString(workspace.resolve("opencode.json"), Json.stringify(Json.obj("model" to "fixture/fixture", "provider" to Json.obj("fixture" to Json.obj(
            "npm" to "@ai-sdk/openai-compatible", "name" to "Fixture", "options" to Json.obj("baseURL" to "http://127.0.0.1:${provider.address.port}/v1", "apiKey" to "fixture-only"),
             "models" to Json.obj("fixture" to Json.obj("name" to "Fixture", "cost" to Json.obj("input" to 2, "output" to 8), "limit" to Json.obj("context" to 32000, "output" to 1000))))))))
        val environment = System.getenv().filterKeys { it.equals("PATH", true) || it.equals("SystemRoot", true) } + mapOf(
            "HOME" to root.toString(), "USERPROFILE" to root.toString(), "LOCALAPPDATA" to root.resolve("local").toString(),
            "APPDATA" to root.resolve("appdata").toString(), "TMPDIR" to root.toString(), "TEMP" to root.toString(),
            "XDG_CONFIG_HOME" to root.resolve("config").toString(), "XDG_DATA_HOME" to root.resolve("data").toString(),
            "XDG_STATE_HOME" to root.resolve("state").toString(), "XDG_CACHE_HOME" to root.resolve("cache").toString(),
            "OPENCODE_DB" to root.resolve("test.db").toString(), "VARRO_TEST_STATE_ROOT" to root.toString(),
            "OPENCODE_DISABLE_FREE_MODELS" to "true",
        )
        val installedVersion = OpenCodeCli({ binary!! }, { workspace.toString() }, environment).readInstalledVersion()
            ?: error("Could not verify the released fixture CLI version")
        System.getenv("VARRO_OPENCODE_TEST_VERSION")?.let { assertEquals(it, installedVersion) }
        if (OpenCodeCli.compareVersions(installedVersion, "2.0.27") >= 0) {
            // Keep the legacy project config, and exercise stateless generation with a native V2 provider.
            val global = Files.createDirectories(root.resolve("config/opencode"))
            Files.writeString(global.resolve("opencode.json"), Json.stringify(Json.obj(
                "\$schema" to "https://opencode.ai/config.json", "providers" to Json.obj("fixture" to Json.obj(
                    "package" to "@opencode/ai/providers/openai-compatible",
                    "settings" to Json.obj("baseURL" to "http://127.0.0.1:${provider.address.port}/v1", "apiKey" to "fixture-only"),
                    "models" to Json.obj(*listOf("fixture", "fixture-command", "fixture-error").map {
                        it to Json.obj("name" to it, "limit" to Json.obj("context" to 32000, "output" to 1000))
                    }.toTypedArray()),
                )),
            )))
        }
        if (OpenCodeCli.compareVersions(installedVersion, "2.0.29") >= 0) {
            val commands = Files.createDirectories(workspace.resolve(".opencode/commands"))
            Files.writeString(commands.resolve("fixture-audit.md"), "---\ndescription: Audit fixture\nagent: general\nmodel: fixture/fixture-command\nsubagent: true\n---\nAudit \$ARGUMENTS. Do not call tools.\n")
            Files.writeString(commands.resolve("fixture-launch-error.md"), "---\nagent: fixture-agent-does-not-exist\nsubagent: true\n---\nReply.\n")
            Files.writeString(commands.resolve("fixture-child-error.md"), "---\nagent: general\nmodel: fixture/fixture-error\nsubagent: true\n---\nReply.\n")
            // Native policies must not be undone by the older-server custom-model fallback.
            val config = Json.parse(Files.readString(workspace.resolve("opencode.json"))).asJsonObject
            config.add("providers", Json.obj("openai" to Json.obj("models" to Json.obj("policy-fixture" to Json.obj("name" to "Denied fixture")))))
            config.add("policies", Json.array(listOf(Json.obj("action" to "provider.use", "resource" to "openai", "effect" to "deny"))))
            Files.writeString(workspace.resolve("opencode.json"), Json.stringify(config))
        }
        val port = ServerSocket(0).use { it.localPort }
        val password = AtomicReference<String?>()
        val parser = OpenCodeStartupOutput { password.set(it) }
        val process = ProcessBuilder(binary!!, "serve", "--port", port.toString()).directory(workspace.toFile()).redirectErrorStream(true).apply {
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
        val events = CopyOnWriteArrayList<com.google.gson.JsonElement>()
        val permissionStore = varro.store.VarroStore()
        fun newTransport() = OpenCodeTransport({ "http://127.0.0.1:$port" }, { workspace.toString() },
            { ServerStatus.Running("http://127.0.0.1:$port", EventStreamState.HEALTHY) }, { false }, {}, events::add,
            { password.get()?.let(OpenCodeConnection::authorization) }, root.resolve("annotations"),
            initialSavedPermissions = permissionStore.serverMemoryPermissions,
            persistSavedPermissions = { permissionStore.serverMemoryPermissions = it })
        val transport = newTransport()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!transport.checkHealth() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue(synchronized(output) { output.toString() }, transport.checkHealth())
            assertEquals(2, transport.apiVersion)
            val version = transport.readHealthInfo().version ?: error("Released server did not report its version")
            assertEquals(installedVersion, version)
            System.getenv("VARRO_OPENCODE_TEST_VERSION")?.let { assertEquals(it, version) }
            if (OpenCodeCli.compareVersions(version, "2.1.1") >= 0) {
                val capabilities = OpenCodeApiCapabilities.read(transport.request("GET", "/openapi.json").data)
                assertTrue(capabilities.sessionArchiving)
                assertTrue(capabilities.savedPermissionEditing)
            }
            assertNotNull(password.get())
            if (OpenCodeCli.compareVersions(version, "2.1.2") >= 0) verifyFreeModelExclusions(transport, root)
            if (OpenCodeCli.compareVersions(version, "2.2.0") >= 0) {
                val requestsBefore = providerRequests.get()
                val native = varro.host.NativeDecisions({ transport.apiVersion }, { "" }, transport::request)
                val nativeStatus = native.status()
                assertEquals(nativeStatus.toString(), true, nativeStatus.bool("supported"))
                assertNotNull(nativeStatus.arr("models")); assertTrue(nativeStatus.get("model").isJsonNull)
                val limits = transport.request("GET", "/api/experimental/usage/limits", options =
                    RequestOptions(directory = workspace.toString(), maxResponseBytes = 1024 * 1024)).data.asObjectOrNull()!!
                assertTrue(limits.get("data").isJsonNull)
                assertEquals(workspace.toString(), limits.obj("location").str("directory"))
                val nonChat = Files.createDirectory(root.resolve("non-chat-models"))
                Files.writeString(nonChat.resolve("opencode.json"), Json.stringify(Json.obj("providers" to Json.obj("non-chat-fixture" to Json.obj(
                    "package" to "@opencode/ai/providers/openai-compatible",
                    "settings" to Json.obj("baseURL" to "http://127.0.0.1:1/v1", "apiKey" to "fixture-only"),
                    "models" to Json.obj("picture" to Json.obj("type" to "image"), "reviewer" to Json.obj("type" to "decision")),
                )))))
                for (route in listOf("/provider", "/config/providers")) {
                    val catalog = transport.request("GET", route, options = RequestOptions(directory = nonChat.toString())).data.asObjectOrNull()
                    val providers = catalog.arr(if (route == "/provider") "all" else "providers")!!
                    val provider = providers.map { it.asJsonObject }.firstOrNull { it.str("id") == "non-chat-fixture" }
                    assertTrue(provider.obj("models")?.size() in listOf(null, 0))
                }
                assertEquals(requestsBefore, providerRequests.get())
            }
            if (OpenCodeCli.compareVersions(version, "2.0.29") >= 0) {
                val config = transport.request("GET", "/config").data.asObjectOrNull()
                assertTrue(config.arr("policies")!!.any { it.asObjectOrNull().str("resource") == "openai" })
                val providers = transport.request("GET", "/provider").data.asObjectOrNull()
                assertFalse(providers.arr("all")!!.any { it.asObjectOrNull().str("id") == "openai" })
                assertFalse(providers.arr("connected")!!.strings().contains("openai"))
                assertFalse(transport.request("GET", "/config/providers").data.asObjectOrNull().arr("providers")!!.any { it.asObjectOrNull().str("id") == "openai" })
            }
            val beforeGeneration = transport.request("GET", "/session").data
            val generationCalls = mutableListOf<String>()
            val oneShot = varro.host.OneShotGeneration { method, path, body, options ->
                generationCalls += "$method $path"
                try { transport.request(method, path, body, options) }
                catch (failure: OpenCodeRequestException) { generationCalls += failure.message.orEmpty(); throw failure }
            }
            val generated = oneShot.generate(2, "Return a short summary",
                Json.obj("providerID" to "fixture", "modelID" to "fixture"), RequestOptions(directory = workspace.toString()))
            if (OpenCodeCli.compareVersions(version, "2.0.27") >= 0) {
                assertEquals("One-shot requests: $generationCalls", "OpenJet stream verified.", generated)
                assertEquals(1, providerRequests.get())
                assertNull(oneShot.generate(2, "Must not reach the provider", Json.obj("providerID" to "fixture", "modelID" to "missing"),
                    RequestOptions(directory = workspace.toString())))
                assertEquals(1, providerRequests.get())
                if (OpenCodeCli.compareVersions(version, "2.2.0") >= 0) {
                    assertTrue(generationCalls.contains("POST /api/experimental/generate/text"))
                    assertTrue(generationCalls.contains("404 Model not found: fixture/missing"))
                }
            } else {
                assertNull(generated)
                assertEquals(0, providerRequests.get())
            }
            assertEquals(beforeGeneration, transport.request("GET", "/session").data)
            for (name in listOf("日本語 🚀", "literal%2Fdirectory")) {
                val directory = Files.createDirectory(workspace.resolve(name)).toString()
                assertEquals(directory, transport.request("GET", "/path", options = RequestOptions(directory = directory)).data.asObjectOrNull().str("directory"))
            }
            assertTrue(transport.request("GET", "/session/status").data!!.isJsonObject)
            transport.startEventStream()
            val session = transport.request("POST", "/session", Json.obj("title" to "OpenJet isolated test")).data.asObjectOrNull()!!
            val id = session.str("id")!!
            assertEquals(workspace.toString(), session.str("directory"))
            assertEquals("2", session.str("version"))
            assertFalse(session.bool("sharingSupported")!!)
            val historyBeforeStop = transport.request("GET", "/session/$id/message").data
            val inboxBeforeStop = transport.request("GET", "/api/session/$id/inbox").data
            val callsBeforeStop = providerRequests.get()
            val shell = transport.request("POST", "/api/shell", Json.obj(
                "command" to if (System.getProperty("os.name").startsWith("Windows")) "ping -n 30 127.0.0.1 > NUL" else "sleep 30",
                "cwd" to workspace.toString(), "metadata" to Json.obj("sessionID" to id),
            )).data.asObjectOrNull().obj("data")!!
            val shellID = shell.str("id")!!
            val processOptions = RequestOptions(directory = workspace.toString())
            try {
                assertEquals("running", shell.str("status"))
                assertTrue(transport.request("GET", "/session/$id/background-process", options = processOptions).data.asArrayOrNull()!!.any {
                    it.asObjectOrNull().str("id") == shellID
                })
                assertEquals(true, transport.request("DELETE", "/session/$id/background-process/$shellID", options = processOptions).data!!.asBoolean)
                assertFalse(transport.request("GET", "/session/$id/background-process", options = processOptions).data.asArrayOrNull()!!.any {
                    it.asObjectOrNull().str("id") == shellID
                })
                assertEquals(historyBeforeStop, transport.request("GET", "/session/$id/message").data)
                assertEquals(inboxBeforeStop, transport.request("GET", "/api/session/$id/inbox").data)
                assertEquals(callsBeforeStop, providerRequests.get())
            } finally {
                transport.request("DELETE", "/api/shell/$shellID")
            }
            assertTrue(transport.request("GET", "/agent").data!!.isJsonArray)
            assertTrue(transport.request("GET", "/provider").data.asObjectOrNull().arr("all") != null)
            val fixture = transport.request("GET", "/config/providers").data.asObjectOrNull().arr("providers")!!.map { it.asJsonObject }.first { it.str("id") == "fixture" }.obj("models").obj("fixture")
            assertEquals(2.0, fixture.obj("cost").num("input"))
            assertEquals(8.0, fixture.obj("cost").num("output"))
            assertEquals(32000, fixture.obj("limit").int("context"))
            transport.request("PATCH", "/session/$id", Json.obj("title" to "Renamed", "permission" to listOf(Json.obj("permission" to "bash", "pattern" to "*", "action" to "ask"))))
            assertEquals("Renamed", transport.request("GET", "/session/$id").data.asObjectOrNull().str("title"))
            verifyArchive(transport, root, events, id)
            transport.request("POST", "/session/$id/prompt_async", Json.obj("noReply" to true, "parts" to listOf(Json.obj("type" to "text", "text" to "Pending input only; no provider request"))))
            val messages = transport.request("GET", "/session/$id/message?limit=10", options = RequestOptions(captureNextCursor = true)).data!!.asJsonArray
            assertTrue(messages.any { it.asObjectOrNull().arr("parts")?.any { it.asObjectOrNull().str("text") == "Pending input only; no provider request" } == true })
            assertTrue(messages.any { it.asObjectOrNull().obj("info").str("pendingDelivery") == "steer" })
            val eventDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (events.none { it.asObjectOrNull().str("type") == "session.next.prompt.admitted" } && System.nanoTime() < eventDeadline) Thread.sleep(20)
            assertTrue(events.any { it.asObjectOrNull().str("type") == "session.next.prompt.admitted" })
            val answer = transport.request("POST", "/session/$id/message", Json.obj("model" to Json.obj("providerID" to "fixture", "modelID" to "fixture"),
                "agent" to "build", "followupTitle" to "Verify fixture",
                "parts" to listOf(Json.obj("type" to "text", "text" to "Reply using the fixture provider")))).data.asObjectOrNull()
            assertTrue(answer.arr("parts")!!.any { it.asObjectOrNull().str("text") == "OpenJet stream verified." })
            val completedHistory = transport.request("GET", "/session/$id/message").data!!.asJsonArray
            val sourceUserID = completedHistory.map { it.asJsonObject }.single { it.obj("info").str("id") == answer.obj("info").str("id") }.obj("info").str("parentID")!!
            assertEquals("Verify fixture", transport.request("GET", "/session/$id/message/$sourceUserID").data.asObjectOrNull().obj("info").str("followupTitle"))
            if (OpenCodeCli.compareVersions(version, "2.0.27") >= 0) {
                val historyBefore = transport.request("GET", "/session/$id/message").data
                val followupSource = Json.obj("assistantMessageId" to answer.obj("info").str("id"), "userMessageId" to sourceUserID,
                    "userPrompt" to "Reply using the fixture provider", "answer" to "OpenJet stream verified.")
                val expectedFollowups = Json.obj("summary" to "The fixture response is complete. A regression test can preserve it.",
                    "actions" to listOf(Json.obj("title" to "Add fixture test", "prompt" to "Add a focused fixture regression test.")))
                oneShotOutput.set(expectedFollowups.toString())
                val beforeCalls = providerRequests.get()
                val followupCache = varro.host.FollowupCache(root.resolve("followups/cache.json"))
                val followupCalls = CopyOnWriteArrayList<String>()
                try {
                    varro.host.FollowupService({ transport.apiVersion }, { method, path, body, options ->
                        // Diagnostics contain only this isolated deterministic fixture's data.
                        val response = transport.request(method, path, body, options)
                        followupCalls += "$method $path -> ${if (path == "/openapi.json") "specification" else response.data}"
                        response
                    }, followupCache).use { service ->
                        val selectedModel = Json.obj("providerID" to "fixture", "modelID" to "fixture")
                        val suggested = service.suggest(id, followupSource, selectedModel, workspace.toString(), true)
                        assertEquals("Follow-up fixture requests: ${followupCalls.joinToString("\n")}", expectedFollowups, suggested)
                        assertEquals(expectedFollowups, service.suggest(id, followupSource, selectedModel, workspace.toString()))
                        service.dismiss(id, followupSource, workspace.toString())
                        service.invalidate()
                        assertEquals(varro.protocol.Followups.empty(), service.suggest(id, followupSource, selectedModel, workspace.toString(), true))
                    }
                    assertEquals(beforeCalls + 1, providerRequests.get())
                    assertEquals(historyBefore, transport.request("GET", "/session/$id/message").data)
                } finally { oneShotOutput.set("OpenJet stream verified.") }
            }
            // The HTTP response can finish before the independent SSE reader delivers the delta.
            waitFor("Text delta for $id was not delivered over SSE") {
                events.any { it.asObjectOrNull().str("type") == "session.next.text.delta" && it.asObjectOrNull().obj("properties").str("sessionID") == id }
            }
            if (OpenCodeCli.compareVersions(version, "2.0.29") >= 0) {
                verifySideQuestions(transport, workspace.toString(), providerRequests, streamGate)
                verifyDelegatedCommands(transport, providerInputs)
            }
            for (samplingDelay in listOf(0L, 75L, 250L)) {
                val steeringSession = transport.request("POST", "/session", Json.obj("title" to "Isolated steering resume fixture")).data.asObjectOrNull().str("id")!!
                val gate = java.util.concurrent.CountDownLatch(1)
                streamGate.set(gate)
                val requestCount = providerRequests.get()
                try {
                    transport.request("POST", "/session/$steeringSession/prompt_async", Json.obj("model" to Json.obj("providerID" to "fixture", "modelID" to "fixture"),
                        "parts" to listOf(Json.obj("type" to "text", "text" to "Wait for cancellation."))))
                    waitFor { providerRequests.get() > requestCount }
                    transport.request("POST", "/session/$steeringSession/prompt_async", Json.obj("messageID" to "msg_pending_steer", "delivery" to "steer",
                        "parts" to listOf(Json.obj("type" to "text", "text" to "Keep this steering pending."))))
                    transport.request("POST", "/session/$steeringSession/abort")
                    waitFor {
                        transport.request("GET", "/session/$steeringSession/message").data!!.asJsonArray.any {
                            it.asObjectOrNull().obj("info").obj("error").str("name") in listOf("aborted", "MessageAbortedError")
                        }
                    }
                    gate.countDown()
                    streamGate.set(null)
                    transport.request("POST", "/api/session/$steeringSession/prompt", Json.obj("id" to "msg_still_queued", "text" to "Leave this queued prompt parked.",
                        "delivery" to "queue", "resume" to false))
                    assertTrue(transport.request("POST", "/session/$steeringSession/resume-steering").data!!.asBoolean)
                    waitFor {
                        val status = transport.request("GET", "/session/status").data.asObjectOrNull().obj(steeringSession)
                        val history = transport.request("GET", "/session/$steeringSession/message").data!!.asJsonArray
                        (status == null || status.str("type") == "idle") && history.any {
                            it.asObjectOrNull().arr("parts")?.any { part -> part.asObjectOrNull().str("text") == "OpenJet stream verified." } == true
                        }
                    }
                    Thread.sleep(samplingDelay)
                    val history = transport.request("GET", "/session/$steeringSession/message").data!!.asJsonArray.map { it.asJsonObject }
                    assertNull(history.single { it.obj("info").str("id") == "msg_pending_steer" }.obj("info").str("pendingDelivery"))
                    assertEquals(2, history.count { it.obj("info").str("role") == "user" && it.obj("info").str("pendingDelivery") == null })
                    assertEquals("queue", history.single { it.obj("info").str("id") == "msg_still_queued" }.obj("info").str("pendingDelivery"))
                } finally {
                    gate.countDown()
                    streamGate.set(null)
                    transport.request("POST", "/session/$steeringSession/abort")
                    transport.request("DELETE", "/session/$steeringSession")
                }
            }
            val reopened = transport.request("GET", "/session/$id/message?limit=20").data!!.asJsonArray
            assertTrue(reopened.any { it.asObjectOrNull().obj("info").str("id") == answer.obj("info").str("id") })
            val helper = transport.request("POST", "/session/$id/message", Json.obj("system" to "Generate a short summary", "parts" to listOf(Json.obj("type" to "text", "text" to "Summarize")))).data.asObjectOrNull()
            assertEquals("OpenJet stream verified.", helper.arr("parts")!![0].asJsonObject.str("text"))
            transport.request("POST", "/session/$id/abort", Json.obj())
            val pausedMessage = transport.request("GET", "/session/$id/message?limit=1").data!!.asJsonArray.last().asJsonObject.obj("info").str("id")!!
            val pauseMetadata = Json.obj("varro" to Json.obj("pauses" to listOf(Json.obj("messageId" to pausedMessage, "pausedAt" to System.currentTimeMillis()))))
            transport.request("PATCH", "/session/$id", Json.obj("metadata" to pauseMetadata))
            assertEquals(pauseMetadata, transport.request("GET", "/session/$id").data.asObjectOrNull().obj("metadata"))
            val waiting = java.util.concurrent.CompletableFuture.supplyAsync {
                transport.request("POST", "/api/session/$id/permission", Json.obj("action" to "shell", "resources" to listOf("fixture permission only"), "save" to listOf("fixture permission only"), "metadata" to Json.obj()))
            }
            var permission: com.google.gson.JsonObject? = null
            val permissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (permission == null && System.nanoTime() < permissionDeadline) {
                permission = transport.request("GET", "/permission").data!!.asJsonArray.mapNotNull { it.asObjectOrNull() }.firstOrNull { it.str("sessionID") == id }
                if (permission == null) Thread.sleep(20)
            }
            assertEquals("bash", permission.str("permission"))
            transport.request("POST", "/permission/${permission.str("id")}/reply", Json.obj("reply" to "always"))
            waiting.get(5, TimeUnit.SECONDS)
            assertEquals(0, transport.request("GET", "/permission").data!!.asJsonArray.size())
            verifySavedPermission(transport, id, ::newTransport)
            val form = transport.request("POST", "/api/session/$id/form", Json.obj("title" to "Fixture question", "fields" to listOf(Json.obj("key" to "choice", "type" to "string", "title" to "Choose",
                "options" to listOf(Json.obj("value" to "accepted", "label" to "Accept")))))).data.asObjectOrNull().obj("data")!!
            val pendingForm = transport.request("GET", "/question").data!!.asJsonArray.single().asJsonObject
            assertEquals("native-form", pendingForm.str("kind"))
            assertEquals("accepted", pendingForm.obj("form").arr("fields")!!.single().asJsonObject.arr("options")!!.single().asJsonObject.str("value"))
            try {
                transport.request("POST", "/question/${form.str("id")}/reply", Json.obj("answers" to listOf(listOf("Accept"))))
                fail("Legacy positional answers must not acknowledge a native form")
            } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("keyed, typed")) }
            assertEquals(1, transport.request("GET", "/question").data!!.asJsonArray.size())
            transport.request("POST", "/question/${form.str("id")}/reply", Json.obj("kind" to "native-form", "sessionID" to id, "answer" to Json.obj("choice" to "accepted")))
            assertEquals(0, transport.request("GET", "/question").data!!.asJsonArray.size())
            val fork = transport.request("POST", "/session/$id/fork", Json.obj()).data.asObjectOrNull().str("id")!!
            val tail = transport.request("GET", "/session/$fork/message").data!!.asJsonArray.last().asJsonObject.obj("info").str("id")!!
            transport.request("DELETE", "/session/$fork/message/$tail")
            transport.request("DELETE", "/session/$fork")
            val legacyPath = root.resolve("legacy.db")
            Class.forName("org.sqlite.JDBC")
            java.sql.DriverManager.getConnection("jdbc:sqlite:$legacyPath").use { database -> database.createStatement().use { sql ->
                sql.execute("CREATE TABLE session(id TEXT,title TEXT,directory TEXT,parent_id TEXT,time_created INTEGER,time_updated INTEGER)")
                sql.execute("CREATE TABLE message(id TEXT,session_id TEXT,time_created INTEGER,data TEXT)")
                sql.execute("CREATE TABLE part(id TEXT,message_id TEXT,session_id TEXT,time_created INTEGER,data TEXT)")
                database.prepareStatement("INSERT INTO session VALUES('ses_legacy','Original',?,NULL,1,2)").use { it.setString(1, workspace.toString()); it.executeUpdate() }
                sql.execute("INSERT INTO message VALUES('msg_original','ses_legacy',1,'{\"role\":\"user\",\"time\":{\"created\":1}}')")
                sql.execute("INSERT INTO part VALUES('part_original','msg_original','ses_legacy',1,'{\"type\":\"text\",\"text\":\"Legacy fixture prompt\"}')")
            } }
            val original = Files.readAllBytes(legacyPath)
            val importer = varro.host.LegacySessionImport(legacyPath) { method, path, body -> transport.request(method, path, body).data }
            val imported = importer.importCopy(importer.list(workspace.toString()).single())
            val importedMessages = transport.request("GET", "/session/$imported/message").data!!.asJsonArray
            assertEquals("Legacy fixture prompt", importedMessages[0].asJsonObject.arr("parts")!![0].asJsonObject.str("text"))
            assertArrayEquals(original, Files.readAllBytes(legacyPath))
            transport.request("DELETE", "/session/$imported")
            transport.request("DELETE", "/session/$id")
            assertFalse(transport.request("GET", "/session").data!!.asJsonArray.any { it.asObjectOrNull().str("id") == id })
            if (OpenCodeCli.compareVersions(version, "2.0.32") >= 0) verifyAttachedQuotaCredentials(transport, root)
        } finally {
            streamGate.getAndSet(null)?.countDown()
            transport.dispose()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue("Isolated OpenCode fixture did not stop", process.waitFor(5, TimeUnit.SECONDS))
            }
            reader.join(1000)
            provider.stop(0)
            providerExecutor.shutdownNow()
        }
    }

    private fun verifyFreeModelExclusions(transport: OpenCodeTransport, root: java.nio.file.Path) {
        val directory = Files.createDirectory(root.resolve("native-free-models")).toString()
        val ids = listOf("opencode", "opencode-go")
        Files.writeString(java.nio.file.Path.of(directory, "opencode.json"), Json.stringify(Json.obj(
            "providers" to Json.obj(*ids.map { id -> id to Json.obj(
                "package" to "@opencode/ai/providers/openai-compatible",
                "settings" to Json.obj("baseURL" to "http://127.0.0.1:1/v1", "apiKey" to "fixture-only"),
                "models" to Json.obj("free-fixture" to Json.obj("cost" to Json.obj("input" to 0, "output" to 0))),
            ) }.toTypedArray()),
        )))
        val options = RequestOptions(directory = directory)
        val models = transport.request("GET", "/api/model", options = options).data.asObjectOrNull().arr("data")!!
        assertTrue(models.any { it.asObjectOrNull().str("providerID") == "fixture" && it.asObjectOrNull().str("id") == "fixture" })
        assertFalse(models.any { it.asObjectOrNull().str("providerID") in ids && it.asObjectOrNull().str("id") == "free-fixture" })
        for (route in listOf("/provider", "/config/providers")) {
            val providers = transport.request("GET", route, options = options).data.asObjectOrNull()
                .arr(if (route == "/provider") "all" else "providers")!!
            for (id in ids) assertFalse("$route restored $id/free-fixture",
                providers.map { it.asJsonObject }.firstOrNull { it.str("id") == id }.obj("models")?.has("free-fixture") == true)
        }
    }

    private fun verifyArchive(transport: OpenCodeTransport, root: java.nio.file.Path, events: List<com.google.gson.JsonElement>, id: String) {
        val native = OpenCodeApiCapabilities.read(transport.request("GET", "/openapi.json").data).sessionArchiving
        val state = OpenCodeV2SessionState(root.resolve("annotations"))
        state.update(id, Json.obj("time" to Json.obj("archived" to 10)))
        val before = transport.request("GET", "/session/$id").data.asObjectOrNull()
        assertEquals(if (native) null else 10.0, before.obj("time").num("archived"))
        val archived = transport.request("PATCH", "/session/$id", Json.obj("time" to Json.obj("archived" to 100))).data.asObjectOrNull()
        assertNotNull(archived.obj("time").num("archived"))
        if (native) {
            assertTrue(archived.obj("time").num("archived")!! > 100)
            waitFor { events.any { it.asObjectOrNull().str("type") == "session.archived" && it.asObjectOrNull().obj("properties").str("sessionID") == id } }
            assertTrue(transport.request("GET", "/session?archived=true").data!!.asJsonArray.any { it.asObjectOrNull().str("id") == id })
            // Another client restores native state. Old annotations must not hide it.
            transport.request("PATCH", "/api/session/$id", Json.obj("archived" to false))
            assertNull(transport.request("GET", "/session/$id").data.asObjectOrNull().obj("time").num("archived"))
            assertEquals(10.0, state.read(id).obj("time").num("archived"))
        }
        transport.request("PATCH", "/session/$id", Json.obj("time" to Json.obj("archived" to null)))
        assertNull(transport.request("GET", "/session/$id").data.asObjectOrNull().obj("time").num("archived"))
        if (native) waitFor { events.any { it.asObjectOrNull().str("type") == "session.unarchived" && it.asObjectOrNull().obj("properties").str("sessionID") == id } }
    }

    private fun verifySavedPermission(transport: OpenCodeTransport, sessionID: String, reload: () -> OpenCodeTransport) {
        // Recreate the host before ever listing managed rules. Approval must already have saved native IDs.
        val restored = reload()
        try {
            assertTrue(restored.checkHealth())
            val native = OpenCodeApiCapabilities.read(transport.request("GET", "/openapi.json").data).savedPermissionEditing
            if (native) {
                assertEquals(1, restored.savedPermissions.handle("GET", sessionID, null, RequestOptions()).arr("rules")!!.size())
            }
            verifySavedPermission(if (native) restored else transport, sessionID)
        } finally { restored.dispose() }
    }

    private fun verifySavedPermission(transport: OpenCodeTransport, sessionID: String) {
        val options = RequestOptions()
        val before = transport.savedPermissions.handle("GET", sessionID, null, options)
        assertTrue(before.bool("supported") == true)
        val rule = before.arr("rules")!!.single().asJsonObject
        assertEquals("bash", rule.str("permission"))
        assertEquals("fixture permission only", rule.str("pattern"))
        if (rule.str("id")!!.startsWith("legacy:")) {
            assertEquals(false, rule.bool("retractable"))
            return
        }
        if (rule.bool("editable") == true) {
            val changed = transport.savedPermissions.handle("PATCH", sessionID,
                Json.obj("id" to rule.str("id"), "permission" to "bash", "pattern" to "fixture edited only"), options)
            assertEquals("fixture edited only", changed.arr("rules")!!.single().asJsonObject.str("pattern"))
            val native = transport.request("GET", "/api/permission/saved?projectID=${rule.str("projectID")}").data.asObjectOrNull().arr("data")!!
            assertEquals("fixture edited only", native.single().asJsonObject.str("resource"))
            // Track a second client's edit by native ID, not the old scope.
            transport.request("PATCH", "/api/permission/saved/${rule.str("id")}", Json.obj("resource" to "fixture external edit only"))
            assertEquals("fixture external edit only", transport.savedPermissions.handle("GET", sessionID, null, options).arr("rules")!!.single().asJsonObject.str("pattern"))
        }
        transport.savedPermissions.handle("DELETE", sessionID, Json.obj("id" to rule.str("id")), options)
        assertEquals(0, transport.savedPermissions.handle("GET", sessionID, null, options).arr("rules")!!.size())
    }

    private fun verifyAttachedQuotaCredentials(transport: OpenCodeTransport, root: java.nio.file.Path) {
        // All credentials are synthetic, in this fixture's server database. OpenAI is denied
        // by the fixture policy, and quota HTTP is stubbed, so no provider receives them.
        val ids = mutableListOf<String>()
        fun create(access: String, account: String, method: String = "chatgpt-browser") {
            val created = transport.request("POST", "/api/credential", Json.obj("integrationID" to "openai", "activate" to true,
                "value" to Json.obj("type" to "oauth", "methodID" to method, "access" to access, "refresh" to "fixture-unused-refresh",
                    "expires" to System.currentTimeMillis() + 86_400_000, "metadata" to Json.obj("accountID" to account)))).data.asObjectOrNull().obj("data")!!
            ids += created.str("id")!!
        }
        try {
            create("fixture-inactive-token", "inactive-account")
            create("fixture-active-token", "active-account")
            val host = Files.createDirectories(root.resolve("attached-quota-host"))
            val credentials = varro.host.quota.QuotaCredentials(host, emptyMap())
            Files.createDirectories(credentials.authPath.parent)
            Files.writeString(credentials.authPath, "invalid local auth")
            Files.writeString(credentials.v2DatabasePath, "invalid local database")
            var polls = 0
            varro.host.ProviderQuotaBackend(credentials, { method, path, body, directory ->
                if (path == "/config/providers") Json.obj("providers" to listOf(Json.obj("id" to "openai", "models" to Json.obj())))
                else transport.request(method, path, body, RequestOptions(directory = directory)).data
            }, varro.host.quota.QuotaHttp { request ->
                polls++
                assertEquals("Bearer fixture-active-token", request.headers["Authorization"])
                assertEquals("active-account", request.headers["ChatGPT-Account-Id"])
                varro.host.quota.QuotaResponse(200, """{"rate_limit":{"primary_window":{"used_percent":20}}}""".toByteArray())
            }, apiVersion = { 2 }, attachOnly = { true }).use { backend ->
                val status = backend.get("openai", null, null)
                assertEquals("available", status.str("status")); assertEquals(1, polls)
                assertFalse(Json.stringify(status).contains("fixture-active-token"))
                create("fixture-sharing-token", "sharing-account", "chatgpt-token-sharing")
                val unsupported = backend.get("openai", null, null)
                assertEquals("unsupported", unsupported.str("status")); assertEquals(1, polls)
                assertTrue(unsupported.str("note")!!.contains("Codex browser"))
            }
        } finally { ids.reversed().forEach { transport.request("DELETE", "/api/credential/$it") } }
    }

    private fun verifySideQuestions(transport: OpenCodeTransport, directory: String,
        providerRequests: java.util.concurrent.atomic.AtomicInteger,
        streamGate: AtomicReference<java.util.concurrent.CountDownLatch?>) {
        val id = transport.request("POST", "/session", Json.obj("title" to "Isolated side question fixture",
            "model" to Json.obj("providerID" to "fixture", "modelID" to "fixture"))).data.asObjectOrNull().str("id")!!
        val questions = varro.host.SideQuestions(transport::request, { transport.apiVersion }, { true }, { 1 }, { listOf(directory) })
        fun ask(history: List<com.google.gson.JsonObject> = emptyList()) = questions.ask("/session/$id/side-question",
            Json.obj("question" to "Why?", "history" to history), directory) { false }
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            transport.request("POST", "/session/$id/message", Json.obj("parts" to listOf(Json.obj("type" to "text", "text" to "Establish context."))))
            transport.request("POST", "/session/$id/prompt_async", Json.obj("noReply" to true, "delivery" to "queue",
                "parts" to listOf(Json.obj("type" to "text", "text" to "Keep this queued."))))
            val before = transport.request("GET", "/session/$id").data.asObjectOrNull()
            val history = transport.request("GET", "/session/$id/message").data
            val inbox = transport.request("GET", "/api/session/$id/inbox").data
            val count = providerRequests.get()
            assertEquals("OpenJet stream verified.", ask().asObjectOrNull().str("text"))
            assertEquals("OpenJet stream verified.", ask(listOf(Json.obj("question" to "Why?", "answer" to "Because."))).asObjectOrNull().str("text"))
            assertEquals(count + 2, providerRequests.get())
            assertEquals(history, transport.request("GET", "/session/$id/message").data)
            assertEquals(inbox, transport.request("GET", "/api/session/$id/inbox").data)
            val after = transport.request("GET", "/session/$id").data.asObjectOrNull()
            assertEquals(before.obj("model"), after.obj("model"))
            assertEquals(before.str("agent"), after.str("agent"))
            // Generation must also complete independently of a stalled execution.
            streamGate.set(gate)
            val beforeBusy = providerRequests.get()
            transport.request("POST", "/session/$id/prompt_async", Json.obj("parts" to listOf(Json.obj("type" to "text", "text" to "Wait for the fixture gate."))))
            waitFor { providerRequests.get() > beforeBusy }
            assertEquals("busy", transport.request("GET", "/session/status").data.asObjectOrNull().obj(id).str("type"))
            assertEquals("OpenJet stream verified.", ask().asObjectOrNull().str("text"))
            assertEquals("busy", transport.request("GET", "/session/status").data.asObjectOrNull().obj(id).str("type"))
        } finally {
            gate.countDown()
            streamGate.set(null)
            transport.request("POST", "/session/$id/abort")
            transport.request("DELETE", "/session/$id")
        }
    }

    private fun verifyDelegatedCommands(transport: OpenCodeTransport, providerInputs: List<com.google.gson.JsonObject>) {
        waitFor { transport.request("GET", "/command").data!!.asJsonArray.any { it.asObjectOrNull().str("name") == "fixture-audit" } }
        val command = transport.request("GET", "/command").data!!.asJsonArray.single { it.asObjectOrNull().str("name") == "fixture-audit" }
        assertEquals(Json.obj("name" to "fixture-audit", "description" to "Audit fixture", "source" to "command", "template" to "", "hints" to emptyList<Any>()), command)
        for (phase in listOf("completed", "launch", "error")) {
            val id = transport.request("POST", "/session", Json.obj("title" to "Isolated command $phase fixture",
                "model" to Json.obj("providerID" to "fixture", "modelID" to "fixture"))).data.asObjectOrNull().str("id")!!
            var childID: String? = null
            try {
                val before = transport.request("GET", "/session/$id").data.asObjectOrNull()
                val inputCount = providerInputs.size
                val payload = Json.obj("command" to when (phase) { "completed" -> "fixture-audit"; "launch" -> "fixture-launch-error"; else -> "fixture-child-error" },
                    "arguments" to "src/auth \"error handling\"", "parts" to listOf(Json.obj("type" to "file", "mime" to "text/plain", "filename" to "context.txt", "url" to "data:text/plain;base64,Y29udGV4dA==")))
                if (phase == "launch") {
                    assertTrue(assertThrows(OpenCodeRequestException::class.java) {
                        transport.request("POST", "/session/$id/command", payload)
                    }.message!!.contains("Agent not found"))
                    assertEquals(0, transport.request("GET", "/session/$id/children").data!!.asJsonArray.size())
                    continue
                }
                transport.request("POST", "/session/$id/command", payload)
                fun results() = transport.request("GET", "/session/$id/message").data!!.asJsonArray.flatMap {
                    it.asObjectOrNull().arr("parts")!!.map { part -> part.asJsonObject }
                }.filter { it.obj("subagentResult") != null }
                waitFor { results().isNotEmpty() }
                val child = transport.request("GET", "/session/$id/children").data!!.asJsonArray.single().asJsonObject
                childID = child.str("id")!!
                assertEquals(id, child.str("parentID"))
                val controls = transport.request("GET", "/session/$childID/child-task-controls").data.asObjectOrNull()
                assertEquals(id, controls.str("parentID"))
                assertEquals(true, controls.bool("stopChild"))
                assertEquals(false, controls.bool("backgroundEligible"))
                assertNotNull(transport.request("POST", "/session/$childID/interrupt-child").data.asObjectOrNull().bool("interrupted"))
                assertEquals("general", child.str("agent"))
                assertEquals(if (phase == "completed") "fixture-command" else "fixture-error", child.obj("model").str("modelID"))
                val result = results().single().obj("subagentResult")
                assertEquals(childID, result.str("sessionID"))
                assertEquals(phase, result.str("state"))
                if (phase == "completed") {
                    val messages = transport.request("GET", "/session/$childID/message").data!!.asJsonArray
                    val prompt = messages.first { it.asObjectOrNull().obj("info").str("role") == "user" }.asJsonObject.arr("parts")!!
                    assertTrue(prompt.any { it.asObjectOrNull().str("text")?.contains("Audit src/auth \"error handling\". Do not call tools.") == true })
                    assertTrue(prompt.any { it.asObjectOrNull().str("filename") == "context.txt" })
                    assertEquals(1, providerInputs.drop(inputCount).count { it.str("model") == "fixture-command" })
                }
                val after = transport.request("GET", "/session/$id").data.asObjectOrNull()
                assertEquals(before.obj("model"), after.obj("model"))
                assertEquals(before.str("agent"), after.str("agent"))
                assertEquals(1, results().size)
            } finally {
                childID?.let { transport.request("DELETE", "/session/$it") }
                transport.request("DELETE", "/session/$id")
            }
        }
    }

    private fun waitFor(message: String = "Released-server fixture did not reach the expected state", condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assertTrue(message, System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }
}
