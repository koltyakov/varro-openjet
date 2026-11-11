package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.nio.file.Files
import java.util.Base64

class UpstreamHostAlignmentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `new host routes reject method and query escalation`() {
        for ((method, path) in listOf("GET" to "/varro/startup-diagnostics", "POST" to "/varro/session/s/followups/session")) {
            assertTrue(ApiRoutes.isAllowed(method, path))
            assertTrue(ApiRoutes.isAllowed(method, "$path?directory=%2Fwork"))
            assertFalse(ApiRoutes.isAllowed("DELETE", path))
            assertFalse(ApiRoutes.isAllowed(method, "$path?all=true"))
        }
    }

    @Test fun `follow-up session links persist across hosts and never accept model-authored session IDs`() {
        val path = temporary.newFolder().toPath().resolve("cache.json")
        val writer = FollowupCache(path)
        val key = "a".repeat(64); val digest = "b".repeat(64)
        val suggestions = Followups.suggestions("""{"summary":"Tests remain.","actions":[{"title":"Test","prompt":"Add focused tests."}]}""")
        writer.claim(key, digest, "c".repeat(64)); writer.complete(key, digest, suggestions)
        val action = suggestions.arr("actions")!![0].asJsonObject.deepCopy().apply { addProperty("sessionId", "ses_fork") }
        assertThrows(IllegalArgumentException::class.java) {
            Followups.suggestions(Json.obj("summary" to "Tests remain.", "actions" to listOf(action)).toString())
        }
        assertFalse(writer.linkSession(key, "d".repeat(64), action))
        assertFalse(writer.linkSession(key, digest, action.deepCopy().apply { addProperty("prompt", "Different prompt") }))
        assertTrue(writer.linkSession(key, digest, action))
        assertTrue(writer.linkSession(key, digest, action))
        assertEquals("ses_fork", FollowupCache(path).get(key, digest).arr("actions")!![0].asJsonObject.str("sessionId"))
        assertFalse(writer.linkSession(key, digest, action.deepCopy().apply { addProperty("sessionId", "ses_other") }))
        writer.dismiss(key, digest)
        assertFalse(writer.linkSession(key, digest, action))
        assertFalse(writer.claim(key, digest, "c".repeat(64)))
    }

    @Test fun `pathless folders preserve nested and empty directories and reject unsafe or partial trees`() {
        val root = temporary.newFolder().toPath()
        val store = AttachmentStore(root) { null }
        fun file(path: String, content: String = "test") = Json.obj("name" to path, "size" to content.toByteArray().size,
            "content" to Base64.getEncoder().encodeToString(content.toByteArray()))
        fun folder(files: List<com.google.gson.JsonObject>, directories: List<String> = listOf("empty", "nested")) =
            Json.obj("files" to files, "directory" to Json.obj("name" to "example", "directories" to directories))
        val attached = store.storeDirectory(folder(listOf(file("nested/test.txt"))))
        val target = java.nio.file.Path.of(attached.str("path")!!)
        assertEquals("directory", attached.str("type")); assertEquals("example", attached.str("relativePath"))
        assertEquals("test", Files.readString(target.resolve("nested/test.txt")))
        assertTrue(Files.isDirectory(target.resolve("empty")))
        val count = Files.list(root).use { it.count() }
        for (path in listOf("../escape", "/absolute", "a\\b", "a:b", "a/./b", "trailing. ")) {
            assertThrows(IllegalArgumentException::class.java) { store.storeDirectory(folder(listOf(file(path)))) }
        }
        assertThrows(IllegalArgumentException::class.java) { store.storeDirectory(folder(listOf(file("A"), file("a")))) }
        assertThrows(IllegalArgumentException::class.java) { store.storeDirectory(folder(listOf(file("bad").apply { addProperty("size", 99) }))) }
        assertThrows(IllegalArgumentException::class.java) { store.storeDirectory(folder(listOf(file("fractional").apply { addProperty("size", 4.5) }))) }
        assertThrows(IllegalArgumentException::class.java) { store.storeDirectory(folder(List(21) { file("file$it") })) }
        assertEquals(count, Files.list(root).use { it.count() })
        assertThrows(Exception::class.java) { store.storeDirectory(folder(listOf(file("collision"), file("collision/child")), emptyList())) }
        assertEquals(count, Files.list(root).use { it.count() })
    }

    @Test fun `startup diagnostics redact addon errors bound names and tolerate independent failures`() {
        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val diagnostics = StartupDiagnostics({ 2 }) { _, path, _, options ->
            calls.add(path); assertNotNull(options.deadlineNanos); assertEquals("/work", options.directory)
            OpenCodeResponse(Json.obj("data" to if (path == "/api/plugin") listOf(
                Json.obj("id" to "bad\n\"plugin", "state" to Json.obj("status" to "failed", "error" to "timeout secret-token")),
                Json.obj("id" to "ok", "state" to Json.obj("status" to "ready"))) else listOf(
                Json.obj("name" to "mcp", "status" to Json.obj("status" to "failed", "error" to "https://secret")))))
        }.read("/work") { false }
        assertEquals(setOf("/api/plugin", "/api/mcp"), calls)
        assertEquals(2, diagnostics.asJsonArray.size())
        assertEquals(true, diagnostics.asJsonArray[0].asJsonObject.bool("timedOut"))
        assertFalse(diagnostics.toString().contains("secret"))
        assertEquals(0, StartupDiagnostics({ 1 }) { _, _, _, _ -> error("V1 must not probe") }.read(null) { false }.asJsonArray.size())
    }

    @Test fun `trace opt-in preserves prompts and bounds ancestry without cross-request caching`() {
        var enabled = false; var reads = 0; var cycle = false
        val tracer = PromptTrace({ _, path, _, options ->
            reads++; assertEquals(64L * 1024, options.maxResponseBytes); assertNotNull(options.deadlineNanos)
            val id = path.substringAfterLast('/')
            OpenCodeResponse(if (id == "child") Json.obj("id" to id, "parentID" to "root") else
                Json.obj("id" to id).apply { if (cycle) addProperty("parentID", "child") })
        }, { enabled })
        val options = RequestOptions(directory = "/work")
        assertEquals("original\ntext", tracer.prompt("followup", "original\ntext", options, "child")); assertEquals(0, reads)
        enabled = true
        val traced = tracer.prompt("followup", "original\ntext", options, "child", "req", "helper")
        assertTrue(traced.endsWith("\noriginal\ntext"))
        val marker = Json.parse(traced.substringBefore('\n').removePrefix("VARRO_TRACE_V1 ")).asJsonObject
        assertEquals(Json.obj("purpose" to "followup", "sessionID" to "child", "rootSessionID" to "root", "requestID" to "req", "helperSessionID" to "helper"), marker)
        cycle = true
        assertFalse(tracer.prompt("followup", "", options, "child").contains("rootSessionID"))
        val before = reads
        val workspace = tracer.prompt("commit-message", "", options)
        assertFalse(workspace.contains("/work")); assertTrue(workspace.contains("workspaceID")); assertEquals(before, reads)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            tracer.prompt("followup", "", options.copy(isCancelled = { true }), "child")
        }
    }
}
