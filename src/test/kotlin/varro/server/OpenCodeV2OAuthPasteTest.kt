package varro.server

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

class OpenCodeV2OAuthPasteTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private inner class Fixture(val paste: Boolean? = true, val mode: String = "auto") {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        var code: String? = null
        var reject = false
        var onSubmit: (() -> Unit)? = null
        var onPoll: (() -> Unit)? = null
        val adapter = OpenCodeV2Adapter({ method, path, body, _ ->
            calls.add("$method $path")
            val route = path.substringBefore('?')
            OpenCodeResponse(when {
                route == "/api/provider/cloud" -> Json.obj("data" to Json.obj("integrationID" to "login"))
                route == "/api/integration/login" -> Json.obj("data" to Json.obj("methods" to listOf(Json.obj("id" to "oauth", "type" to "oauth"))))
                route == "/api/mcp" -> Json.obj("data" to listOf(Json.obj("name" to "docs", "integrationID" to "login", "status" to Json.obj("status" to "connected"))))
                route.endsWith("/connect/oauth") -> Json.obj("data" to Json.obj("attemptID" to "attempt", "mode" to mode, "url" to "https://example.com/auth").apply {
                    if (paste != null) addProperty("paste", paste)
                })
                route.endsWith("/complete") -> {
                    code = body.asObjectOrNull().str("code")
                    onSubmit?.invoke()
                    if (reject) error("400 invalid redirect URL")
                    Json.obj()
                }
                method == "GET" -> { onPoll?.invoke(); Json.obj("data" to Json.obj("status" to "complete")) }
                method == "DELETE" -> Json.obj()
                else -> error("Unexpected request $method $path")
            })
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        val options = RequestOptions(directory = "/repo")
        fun authorize(mcp: Boolean = false) = adapter.request("POST", if (mcp) "/mcp/docs/auth" else "/provider/cloud/oauth/authorize", Json.obj("method" to 0), options).data.asObjectOrNull()!!
        fun callback(body: JsonObject, options: RequestOptions = this.options) = adapter.request("POST", "/provider/cloud/oauth/callback", body, options)
        fun submit(url: String = "http://localhost/callback?code=a%2Fb&state=original") = callback(Json.obj("attemptID" to "attempt", "code" to url, "submitOnly" to true))
    }

    @Test fun `provider and MCP authorizations preserve only true native paste capability`() {
        for (paste in listOf(null, false, true)) for (mcp in listOf(false, true)) {
            val authorization = Fixture(paste).authorize(mcp)
            assertEquals(paste == true, authorization.has("paste"))
            if (mcp) assertEquals("attempt", authorization.str("oauthState"))
            else assertEquals("auto", authorization.str("method"))
        }
    }

    @Test fun `submit only preserves the redirect URL without polling or consuming the attempt`() {
        val fixture = Fixture()
        fixture.authorize()
        fixture.calls.clear()
        fixture.submit()
        assertEquals("http://localhost/callback?code=a%2Fb&state=original", fixture.code)
        assertEquals(listOf(
            "GET /api/provider/cloud?location%5Bdirectory%5D=%2Frepo",
            "POST /api/integration/login/connect/oauth/attempt/complete?location%5Bdirectory%5D=%2Frepo",
        ), fixture.calls.toList())
        fixture.callback(Json.obj("attemptID" to "attempt"))
        assertFalse(fixture.calls.any { it.startsWith("DELETE") })
        assertThrows(IllegalStateException::class.java) { fixture.submit() }
    }

    @Test fun `rejected or aborted paste leaves automatic completion alive for retry`() {
        for (aborted in listOf(false, true)) {
            val fixture = Fixture()
            fixture.authorize()
            fixture.calls.clear()
            if (aborted) fixture.onSubmit = { throw CancellationException("paste cancelled") } else fixture.reject = true
            assertThrows(Exception::class.java) { fixture.submit() }
            assertFalse(fixture.calls.any { it.startsWith("DELETE") || it.startsWith("GET") && it.contains("/connect/oauth/") })
            fixture.onSubmit = null; fixture.reject = false
            fixture.submit()
            fixture.callback(Json.obj("attemptID" to "attempt"))
        }
    }

    @Test fun `paste requires an auto capable attempt nonblank string and matching workspace identity`() {
        for ((paste, mode) in listOf(null to "auto", false to "auto", true to "code")) {
            val fixture = Fixture(paste, mode)
            fixture.authorize(); fixture.calls.clear()
            assertThrows(IllegalArgumentException::class.java) { fixture.submit() }
            assertFalse(fixture.calls.any { it.contains("/complete") || it.startsWith("DELETE") })
            fixture.callback(Json.obj("attemptID" to "attempt"))
        }
        val fixture = Fixture()
        fixture.authorize()
        for (url in listOf("", "  ")) assertThrows(IllegalArgumentException::class.java) { fixture.submit(url) }
        assertThrows(IllegalStateException::class.java) {
            fixture.callback(Json.obj("attemptID" to "attempt", "code" to "url", "submitOnly" to true), RequestOptions(directory = "/other"))
        }
        assertThrows(IllegalStateException::class.java) { fixture.callback(Json.obj("attemptID" to "foreign", "code" to "url", "submitOnly" to true)) }
        fixture.submit()
    }

    @Test fun `automatic callback retains polling ownership while paste is submitted concurrently`() {
        val fixture = Fixture()
        fixture.authorize()
        val polling = CountDownLatch(1)
        val completed = CountDownLatch(1)
        fixture.onPoll = { polling.countDown(); check(completed.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val automatic = executor.submit { fixture.callback(Json.obj("attemptID" to "attempt")) }
            assertTrue(polling.await(5, TimeUnit.SECONDS))
            fixture.submit()
            completed.countDown()
            automatic.get(5, TimeUnit.SECONDS)
            assertEquals(1, fixture.calls.count { it.startsWith("GET") && it.contains("/connect/oauth/attempt") })
            assertFalse(fixture.calls.any { it.startsWith("DELETE") })
        } finally { completed.countDown(); executor.shutdownNow() }
    }

    @Test fun `MCP redirect URL callback forwards unchanged and still acknowledges completion`() {
        val fixture = Fixture()
        fixture.authorize(true)
        fixture.adapter.request("POST", "/mcp/docs/auth/callback", Json.obj("attemptID" to "attempt", "code" to "http://localhost/callback?code=a%2Fb"), fixture.options)
        assertEquals("http://localhost/callback?code=a%2Fb", fixture.code)
        assertFalse(fixture.calls.any { it.startsWith("DELETE") })
    }
}
