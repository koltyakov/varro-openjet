package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.util.Base64

class OpenCodeV2SyncTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private fun adapter(wire: (String, String, JsonElement?) -> JsonElement?) = OpenCodeV2Adapter(
        { method, path, body, _ -> OpenCodeResponse(wire(method, path, body)) }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
    private fun fails(text: String, action: () -> Unit) {
        try { action(); fail("Expected $text") } catch (expected: IllegalArgumentException) { assertTrue(expected.message, expected.message!!.contains(text)) }
    }
    private fun form() = Json.obj("id" to "frm", "sessionID" to "child", "title" to "Connection", "metadata" to Json.obj("tool" to Json.obj("id" to "call", "messageID" to "msg")),
        "fields" to listOf(Json.obj("key" to "enabled", "type" to "boolean", "default" to false, "hidden" to true),
            Json.obj("key" to "choice", "type" to "string", "when" to listOf(Json.obj("key" to "enabled", "op" to "eq", "value" to true)),
                "options" to listOf(Json.obj("label" to "Same", "value" to "a"), Json.obj("label" to "Same", "value" to "b")))))

    @Test fun `native parent identity overrides stale compatibility annotations`() {
        val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
        state.update("child", Json.obj("parentID" to "stale-parent"))
        val native = OpenCodeV2Adapter({ _, _, _, _ -> OpenCodeResponse(Json.obj("data" to Json.obj("id" to "child", "parentID" to "native-parent"))) }, state)
        assertEquals("native-parent", native.request("GET", "/session/child", null, RequestOptions()).data.asObjectOrNull().str("parentID"))
    }

    @Test fun `native forms retain keyed fields conditions defaults and tool attribution`() {
        val native = form(); val projected = OpenCodeV2Projection.form(native)
        assertEquals(native, projected.obj("form")); assertEquals("native-form", projected.str("kind"))
        assertEquals("call", projected.obj("tool").str("callID")); assertEquals(false, projected.arr("questions")!!.single().asJsonObject.bool("custom"))
        val event = OpenCodeV2Events.project(Json.obj("type" to "form.created", "data" to Json.obj("form" to native)), Json.obj()).single()
        assertEquals(projected, event.obj("properties"))
        val settled = OpenCodeV2Events.project(Json.obj("type" to "form.replied", "data" to Json.obj("id" to "frm", "sessionID" to "child", "answer" to Json.obj("secret" to "excluded"))), Json.obj()).single()
        assertEquals(Json.obj("sessionID" to "child", "requestID" to "frm"), settled.obj("properties"))
    }

    @Test fun `unsupported malformed and duplicate native fields remain visible and non-answerable`() {
        for (field in listOf(Json.obj("key" to "secret", "type" to "password"), Json.obj("key" to "n", "type" to "number", "default" to "NaN"),
            Json.obj("key" to "secret", "type" to "string", "format" to "password"), Json.obj("key" to "choices", "type" to "multiselect", "options" to "broken"))) {
            val form = form().apply { add("fields", Json.array(listOf(field))) }
            assertEquals("unsupported", OpenCodeV2Forms.parse(form).arr("fields")!!.single().asJsonObject.str("type"))
            fails("unsupported fields") { OpenCodeV2Forms.answer(Json.obj("kind" to "native-form", "sessionID" to "child", "answer" to Json.obj()), form) }
        }
        val duplicate = form().apply { add("fields", Json.array(listOf(Json.obj("key" to "x", "type" to "boolean"), Json.obj("key" to "x", "type" to "boolean")))) }
        assertEquals("unsupported", OpenCodeV2Forms.parse(duplicate).arr("fields")!![1].asJsonObject.str("type"))
    }

    @Test fun `only typed replies acknowledge forms and failures retain pending identity`() {
        var reject = true; var posts = 0
        val native = adapter { method, path, body -> when {
            path.startsWith("/api/form") -> Json.obj("data" to listOf(form()))
            path.endsWith("/reply") -> {
                posts++; assertEquals(Json.obj("enabled" to false, "choice" to "b", "n" to 0, "empty" to emptyList<String>()), body.asObjectOrNull().obj("answer"))
                if (reject) error("422 rejected")
                Json.obj()
            }
            method == "DELETE" -> Json.obj()
            else -> error(path)
        } }
        native.request("GET", "/question", null, RequestOptions())
        val reply = Json.obj("kind" to "native-form", "sessionID" to "child", "answer" to Json.obj("enabled" to false, "choice" to "b", "n" to 0, "empty" to emptyList<String>()))
        for (bad in listOf(Json.obj("answers" to listOf(listOf("Same"))), reply.deepCopy().apply { addProperty("sessionID", "parent") }, reply.deepCopy().apply { addProperty("extra", true) }))
            fails("keyed, typed") { native.request("POST", "/question/frm/reply", bad, RequestOptions()) }
        assertEquals(0, posts)
        try { native.request("POST", "/question/frm/reply", reply, RequestOptions()); fail() } catch (_: IllegalStateException) { }
        reject = false
        assertEquals(true, native.request("POST", "/question/frm/reply", reply, RequestOptions()).data!!.asBoolean)
        assertEquals(2, posts)
    }

    @Test fun `lost shell notices appear from inbox with stable identity and no prompt admission`() {
        val payload = Json.obj("text" to "Background command cancelled", "metadata" to Json.obj("source" to "shell", "state" to "cancelled"))
        var delivered = false
        val native = adapter { method, path, _ ->
            assertEquals("GET", method)
            if (path.endsWith("/inbox")) Json.obj("data" to if (delivered) emptyList<Any>() else listOf(Json.obj("id" to "notice", "type" to "synthetic", "payload" to payload, "time" to Json.obj("created" to 10))))
            else Json.obj("data" to if (delivered) listOf(payload.deepCopy().apply { addProperty("id", "notice"); addProperty("type", "synthetic"); add("time", Json.obj("created" to 10)) }) else emptyList<Any>())
        }
        val pending = native.request("GET", "/session/child/message", null, RequestOptions()).data
        delivered = true
        assertEquals(pending, native.request("GET", "/session/child/message", null, RequestOptions()).data)
    }

    @Test fun `concurrent native form replies never race a reply against rejection`() {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val native = adapter { _, path, _ ->
            if (path == "/api/form") Json.obj("data" to listOf(form())) else {
                assertTrue(path.endsWith("/reply")); entered.countDown()
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); Json.obj()
            }
        }
        native.request("GET", "/question", null, RequestOptions())
        val first = java.util.concurrent.CompletableFuture.supplyAsync {
            native.request("POST", "/question/frm/reply", Json.obj("kind" to "native-form", "sessionID" to "child", "answer" to Json.obj()), RequestOptions())
        }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            try { native.request("POST", "/question/frm/reject", null, RequestOptions()); fail() }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("already in progress")) }
        } finally { release.countDown(); first.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun pdf() = Json.obj("type" to "file", "mime" to "application/pdf", "filename" to "test.pdf",
        "url" to "data:application/pdf;base64,${Base64.getEncoder().encodeToString("%PDF-fixture".toByteArray())}")

    @Test fun `PDF admission checks authoritative capabilities before any mutation`() {
        for (capability in listOf(listOf("text"), null, listOf("text", "pdf"))) {
            val methods = mutableListOf<String>()
            val native = adapter { method, path, _ ->
                methods += method
                when {
                    path.startsWith("/api/model?") -> Json.obj("data" to listOf(Json.obj("providerID" to "p", "id" to "m", "capabilities" to Json.obj("input" to capability))))
                    path == "/api/session/child" -> Json.obj("data" to Json.obj())
                    else -> Json.obj()
                }
            }
            val input = Json.obj("model" to Json.obj("providerID" to "p", "modelID" to "m"), "parts" to listOf(pdf()))
            if (capability == listOf("text", "pdf")) {
                native.request("POST", "/session/child/prompt_async", input, RequestOptions(directory = "/repo"))
                assertTrue(methods.contains("POST"))
            } else {
                fails(if (capability == null) "unknown" else "does not support") { native.request("POST", "/session/child/prompt_async", input, RequestOptions(directory = "/repo")) }
                assertEquals(listOf("GET"), methods)
            }
        }
        fails("configured commands") { NativePdfInput.validate(listOf(pdf()), "command", Json.obj()) }
        fails("one-shot") { NativePdfInput.validate(listOf(pdf()), "message", Json.obj("system" to "test")) }
        for (url in listOf("data:application/pdf;base64,JVBERi0", "data:application/pdf;base64,bm90IGEgcGRm", "https://example.com/test.pdf")) assertNull(NativePdfInput.size(url))
        assertNotNull(NativePdfInput.size(pdf().str("url")))
    }
}
