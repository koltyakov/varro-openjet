package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.server.OpenCodeRequestException
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.util.concurrent.CancellationException

class OneShotGenerationTest {
    private val specification = Json.parse("""{"paths":{"/api/experimental/generate":{"post":{}}}}""")
    private val model = Json.obj("providerID" to "test", "modelID" to "small", "variant" to "none")

    @Test fun `generation preserves model directory and cancellation without sessions`() {
        val calls = mutableListOf<String>()
        val cancelled = { false }
        val helper = OneShotGeneration { method, path, body, options ->
            calls.add("$method $path")
            assertSame(cancelled, options.isCancelled)
            if (method == "GET") {
                assertTrue(options.unscoped)
                assertEquals(4 * 1024 * 1024L, options.maxResponseBytes)
                OpenCodeResponse(specification)
            } else {
                assertEquals("/workspace", options.directory)
                assertFalse(options.unscoped)
                assertEquals(Json.parse("""{"prompt":"Write a message","model":{"providerID":"test","id":"small","variant":"none"}}"""), body)
                OpenCodeResponse(Json.obj("data" to Json.obj("text" to "fix: handle retries")))
            }
        }
        assertEquals("fix: handle retries", helper.generate(2, "Write a message", model, RequestOptions(directory = "/workspace", isCancelled = cancelled)))
        assertEquals(listOf("GET /openapi.json", "POST /api/experimental/generate"), calls)
    }

    @Test fun `unsupported versions and capabilities do not admit generation`() {
        val v1 = OneShotGeneration { _, _, _, _ -> error("V1 must not probe generation") }
        assertNull(v1.generate(1, "prompt", model, RequestOptions()))
        for (missing in listOf(false, true)) {
            var requests = 0
            val helper = OneShotGeneration { _, path, _, _ ->
                requests++
                assertEquals("/openapi.json", path)
                if (missing) throw OpenCodeRequestException("404 Not found")
                OpenCodeResponse(Json.obj("paths" to Json.obj()))
            }
            assertNull(helper.generate(2, "prompt", model, RequestOptions()))
            assertEquals(1, requests)
        }
    }

    @Test fun `only exact model admission failures allow fallback`() {
        for (message in listOf("400 Model unavailable: test/small", "401 Unauthorized", "503 Unavailable", "Request timed out", "404 Model not found", "400 Model unavailable: test/other")) {
            var requests = 0
            val helper = OneShotGeneration { method, _, _, _ ->
                requests++
                if (method == "GET") OpenCodeResponse(specification) else throw OpenCodeRequestException(message)
            }
            if (message == "400 Model unavailable: test/small") assertNull(helper.generate(2, "prompt", model, RequestOptions()))
            else assertEquals(message, assertThrows(OpenCodeRequestException::class.java) {
                helper.generate(2, "prompt", model, RequestOptions())
            }.message)
            assertEquals(2, requests)
        }
    }

    @Test fun `invalid output and cancellation never offer another generation`() {
        val helper = OneShotGeneration { method, _, _, _ ->
            OpenCodeResponse(if (method == "GET") specification else Json.obj("data" to Json.obj("text" to 42)))
        }
        assertThrows(IllegalStateException::class.java) { helper.generate(2, "prompt", model, RequestOptions()) }
        var cancelled = false
        var calls = 0
        val cancelling = OneShotGeneration { _, _, _, _ -> calls++; cancelled = true; OpenCodeResponse(specification) }
        assertThrows(CancellationException::class.java) {
            cancelling.generate(2, "prompt", model, RequestOptions(isCancelled = { cancelled }))
        }
        assertEquals(1, calls)
    }
}
