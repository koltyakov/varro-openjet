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

    @Test fun `released server supports authenticated lifecycle history and pending input`() {
        val binary = System.getenv("VARRO_OPENCODE_TEST_BINARY")
        assumeTrue("Set VARRO_OPENCODE_TEST_BINARY to a released v2 CLI", !binary.isNullOrBlank())
        val root = temporary.newFolder().toPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val providerRequests = java.util.concurrent.atomic.AtomicInteger()
        val provider = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/") { exchange ->
            providerRequests.incrementAndGet()
            val input = Json.parse(exchange.requestBody.bufferedReader().readText()).asJsonObject
            val usage = Json.obj("prompt_tokens" to 10, "completion_tokens" to 3, "total_tokens" to 13)
            val response = if (input.bool("stream") == true) {
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
        val port = ServerSocket(0).use { it.localPort }
        val password = AtomicReference<String?>()
        val parser = OpenCodeStartupOutput { password.set(it) }
        val process = ProcessBuilder(binary!!, "serve", "--port", port.toString()).directory(workspace.toFile()).redirectErrorStream(true).apply {
            val path = environment()["PATH"].orEmpty()
            environment().clear()
            environment().putAll(mapOf("PATH" to path, "HOME" to root.toString(), "XDG_CONFIG_HOME" to root.resolve("config").toString(),
                "XDG_DATA_HOME" to root.resolve("data").toString(), "XDG_STATE_HOME" to root.resolve("state").toString(),
                "XDG_CACHE_HOME" to root.resolve("cache").toString(), "OPENCODE_DB" to root.resolve("test.db").toString()))
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
            assertNotNull(password.get())
            val beforeGeneration = transport.request("GET", "/session").data
            val oneShot = varro.host.OneShotGeneration(transport::request)
            assertNull(oneShot.generate(2, "Return a short summary",
                Json.obj("providerID" to "fixture", "modelID" to "fixture"), RequestOptions(directory = workspace.toString())))
            assertEquals(0, providerRequests.get())
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
            transport.dispose()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(1000)
            provider.stop(0)
        }
    }
}
