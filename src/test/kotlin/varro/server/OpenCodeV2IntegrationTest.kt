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
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture", "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj("content" to "OpenJet stream verified."), "finish_reason" to null))),
                    Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion.chunk", "created" to 1, "model" to "fixture", "choices" to listOf(Json.obj("index" to 0, "delta" to Json.obj(), "finish_reason" to "stop")), "usage" to usage))
                chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                Json.obj("id" to "chatcmpl-fixture", "object" to "chat.completion", "created" to 1, "model" to "fixture", "choices" to listOf(
                    Json.obj("index" to 0, "message" to Json.obj("role" to "assistant", "content" to "OpenJet stream verified."), "finish_reason" to "stop")), "usage" to usage).toString()
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
        val transport = OpenCodeTransport({ "http://127.0.0.1:$port" }, { workspace.toString() },
            { ServerStatus.Running("http://127.0.0.1:$port", EventStreamState.HEALTHY) }, { false }, {}, events::add,
            { password.get()?.let(OpenCodeConnection::authorization) }, root.resolve("annotations"))
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!transport.checkHealth() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue(synchronized(output) { output.toString() }, transport.checkHealth())
            assertEquals(2, transport.apiVersion)
            val version = transport.readHealthInfo().version ?: error("Released server did not report its version")
            assertEquals(installedVersion, version)
            System.getenv("VARRO_OPENCODE_TEST_VERSION")?.let { assertEquals(it, version) }
            assertNotNull(password.get())
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
            assertTrue(transport.request("GET", "/agent").data!!.isJsonArray)
            assertTrue(transport.request("GET", "/provider").data.asObjectOrNull().arr("all") != null)
            val fixture = transport.request("GET", "/config/providers").data.asObjectOrNull().arr("providers")!!.map { it.asJsonObject }.first { it.str("id") == "fixture" }.obj("models").obj("fixture")
            assertEquals(2.0, fixture.obj("cost").num("input"))
            assertEquals(8.0, fixture.obj("cost").num("output"))
            assertEquals(32000, fixture.obj("limit").int("context"))
            transport.request("PATCH", "/session/$id", Json.obj("title" to "Renamed", "permission" to listOf(Json.obj("permission" to "bash", "pattern" to "*", "action" to "ask"))))
            assertEquals("Renamed", transport.request("GET", "/session/$id").data.asObjectOrNull().str("title"))
            transport.request("POST", "/session/$id/prompt_async", Json.obj("noReply" to true, "parts" to listOf(Json.obj("type" to "text", "text" to "Pending input only; no provider request"))))
            val messages = transport.request("GET", "/session/$id/message?limit=10", options = RequestOptions(captureNextCursor = true)).data!!.asJsonArray
            assertTrue(messages.any { it.asObjectOrNull().arr("parts")?.any { it.asObjectOrNull().str("text") == "Pending input only; no provider request" } == true })
            assertTrue(messages.any { it.asObjectOrNull().obj("info").str("pendingDelivery") == "steer" })
            val eventDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (events.none { it.asObjectOrNull().str("type") == "session.next.prompt.admitted" } && System.nanoTime() < eventDeadline) Thread.sleep(20)
            assertTrue(events.any { it.asObjectOrNull().str("type") == "session.next.prompt.admitted" })
            val answer = transport.request("POST", "/session/$id/message", Json.obj("model" to Json.obj("providerID" to "fixture", "modelID" to "fixture"),
                "parts" to listOf(Json.obj("type" to "text", "text" to "Reply using the fixture provider")))).data.asObjectOrNull()
            assertTrue(answer.arr("parts")!!.any { it.asObjectOrNull().str("text") == "OpenJet stream verified." })
            assertTrue(events.any { it.asObjectOrNull().str("type") == "session.next.text.delta" })
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
            transport.request("POST", "/permission/${permission.str("id")}/reply", Json.obj("reply" to "once"))
            waiting.get(5, TimeUnit.SECONDS)
            assertEquals(0, transport.request("GET", "/permission").data!!.asJsonArray.size())
            val form = transport.request("POST", "/api/session/$id/form", Json.obj("title" to "Fixture question", "fields" to listOf(Json.obj("key" to "choice", "type" to "string", "title" to "Choose",
                "options" to listOf(Json.obj("value" to "accepted", "label" to "Accept")))))).data.asObjectOrNull().obj("data")!!
            assertEquals(1, transport.request("GET", "/question").data!!.asJsonArray.size())
            transport.request("POST", "/question/${form.str("id")}/reply", Json.obj("answers" to listOf(listOf("Accept"))))
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

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assertTrue("Released-server fixture did not reach the expected state", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }
}
