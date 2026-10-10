package varro

import varro.host.ApiRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist is the host's authorization boundary, so it is tested directly
 * rather than only through the proxy.
 */
class ApiRoutesTest {

    @Test fun `background processes accept only scoped actions and safe output cursors`() {
        val list = "/session/ses_one/background-process"
        val process = "$list/shell_one"
        assertTrue(ApiRoutes.isAllowed("GET", list))
        assertTrue(ApiRoutes.isAllowed("GET", "$list?directory=%2Frepo"))
        for (method in listOf("PATCH", "DELETE")) assertTrue(ApiRoutes.isAllowed(method, "$process?directory=%2Frepo"))
        for (method in listOf("POST", "PUT", "GET")) assertFalse(ApiRoutes.isAllowed(method, process))
        assertFalse(ApiRoutes.isAllowed("DELETE", list))
        for (cursor in listOf("0", "65536", "9007199254740991")) {
            assertTrue(ApiRoutes.isAllowed("GET", "$process/output?cursor=$cursor&directory=%2Frepo"))
        }
        for (query in listOf("cursor=", "cursor=-1", "cursor=1.5", "cursor=1e3", "cursor=9007199254740992",
            "cursor=0&cursor=1", "directory=", "directory=/a&directory=/b", "limit=100000000")) {
            assertFalse(ApiRoutes.isAllowed("GET", "$process/output?$query"))
        }
        assertFalse(ApiRoutes.isAllowed("DELETE", "$process/output"))
        assertFalse(ApiRoutes.isAllowed("PATCH", "$list/a%2fb"))
        assertFalse(ApiRoutes.isAllowed("PATCH", "$process?service=true"))
    }

    @Test fun `message content reads only accept bounded thumbnail and directory queries`() {
        val path = "/session/ses_one/message/msg_one/part/call%3Afile%3A0"
        assertTrue(ApiRoutes.isAllowed("GET", path))
        assertTrue(ApiRoutes.isAllowed("GET", "$path?directory=%2Frepo&view=thumbnail"))
        for (query in listOf("view=original", "view=thumbnail&view=thumbnail", "directory=", "url=https://example.com")) {
            assertFalse(ApiRoutes.isAllowed("GET", "$path?$query"))
        }
        assertFalse(ApiRoutes.isAllowed("POST", path))
        assertFalse(ApiRoutes.isAllowed("DELETE", path))
        assertFalse(ApiRoutes.isAllowed("GET", "/session/ses_one/message/msg_one/part/a%2fb"))
    }

    @Test fun `provider disabling accepts only POST without query parameters`() {
        val path = ApiRoutes.Endpoints.OPENCODE_CONFIG_DISABLE_PROVIDER
        assertTrue(ApiRoutes.isAllowed("POST", path))
        assertFalse(ApiRoutes.isAllowed("GET", path))
        assertFalse(ApiRoutes.isAllowed("DELETE", path))
        assertFalse(ApiRoutes.isAllowed("POST", "$path?directory=/other"))
    }

    @Test
    fun `allows the core read endpoints`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/global/health"))
        assertTrue(ApiRoutes.isAllowed("GET", "/session"))
        assertTrue(ApiRoutes.isAllowed("GET", "/session/status"))
        assertTrue(ApiRoutes.isAllowed("GET", "/provider"))
        assertTrue(ApiRoutes.isAllowed("GET", "/agent"))
    }

    @Test
    fun `allows session writes but only the named actions`() {
        assertTrue(ApiRoutes.isAllowed("POST", "/session/ses_123/prompt_async"))
        assertTrue(ApiRoutes.isAllowed("POST", "/session/ses_123/abort"))
        assertTrue(ApiRoutes.isAllowed("POST", "/session/ses_123/summarize"))
        // `delete` is not a POST action; deletion goes through the host namespace.
        assertFalse(ApiRoutes.isAllowed("POST", "/session/ses_123/delete"))
        assertFalse(ApiRoutes.isAllowed("POST", "/session/ses_123/rm-rf"))
    }

    @Test
    fun `steering resume allows only POST with an optional directory`() {
        val path = "/session/ses_123/resume-steering"
        assertTrue(ApiRoutes.isAllowed("POST", path))
        assertTrue(ApiRoutes.isAllowed("POST", "$path?directory=%2Frepo"))
        assertFalse(ApiRoutes.isAllowed("GET", path))
        assertFalse(ApiRoutes.isAllowed("DELETE", path))
        assertFalse(ApiRoutes.isAllowed("POST", "$path?directory="))
        assertFalse(ApiRoutes.isAllowed("POST", "$path?unexpected=true"))
        assertFalse(ApiRoutes.isAllowed("POST", "/session/a%2fb/resume-steering"))
    }

    @Test
    fun `allows decision provider status and updates without a query`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/varro/decision-providers"))
        assertTrue(ApiRoutes.isAllowed("POST", "/varro/decision-providers"))
        assertFalse(ApiRoutes.isAllowed("DELETE", "/varro/decision-providers"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/decision-providers?x=1"))
    }

    @Test
    fun `rejects unknown routes and methods`() {
        assertFalse(ApiRoutes.isAllowed("GET", "/admin"))
        assertFalse(ApiRoutes.isAllowed("DELETE", "/global/health"))
        assertFalse(ApiRoutes.isAllowed("POST", "/provider"))
    }

    @Test
    fun `rejects paths that are not host-relative`() {
        assertFalse(ApiRoutes.isAllowed("GET", "//evil.example.com/session"))
        assertFalse(ApiRoutes.isAllowed("GET", "http://evil.example.com/session"))
        assertFalse(ApiRoutes.isAllowed("GET", "session"))
    }

    @Test
    fun `rejects traversal and encoded separators`() {
        assertFalse(ApiRoutes.isAllowed("GET", "/session/../admin"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session/%2e%2e/admin"))
        // An encoded slash would let a crafted id span two route segments.
        assertFalse(ApiRoutes.isAllowed("GET", "/session/a%2fb"))
    }

    @Test
    fun `constrains query parameters per route`() {
        // `/session` accepts a positive limit, with or without a directory.
        assertTrue(ApiRoutes.isAllowed("GET", "/session?limit=50"))
        assertTrue(ApiRoutes.isAllowed("GET", "/session?limit=50&directory=/tmp/project"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session?limit=0"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session?limit=abc"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session?unexpected=1"))
        // An empty directory is meaningless and would silently widen scope.
        assertFalse(ApiRoutes.isAllowed("GET", "/session?directory="))
        // `/session/status` takes no query at all.
        assertFalse(ApiRoutes.isAllowed("GET", "/session/status?directory=/tmp"))
    }

    @Test
    fun `search requires its full parameter set`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/session?limit=20&search=fix&roots=true"))
        // `roots` must be exactly "true"; anything else changes what is returned.
        assertFalse(ApiRoutes.isAllowed("GET", "/session?limit=20&search=fix&roots=false"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session?search=fix&roots=true"))
    }

    @Test
    fun `message paging requires a limit alongside a cursor`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/session/ses_1/message"))
        assertTrue(ApiRoutes.isAllowed("GET", "/session/ses_1/message?limit=200"))
        assertTrue(ApiRoutes.isAllowed("GET", "/session/ses_1/message?limit=200&before=msg_9"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session/ses_1/message?before=msg_9"))
    }

    @Test
    fun `allows the host namespace with its own constraints`() {
        assertTrue(ApiRoutes.isAllowed("GET", "/varro/provider-limit?providerID=anthropic"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/provider-limit"))
        assertTrue(ApiRoutes.isAllowed("GET", "/varro/session-trash"))
        assertTrue(ApiRoutes.isAllowed("DELETE", "/varro/session-trash"))
        assertTrue(ApiRoutes.isAllowed("POST", "/varro/session-trash/ses_1/restore"))
        assertTrue(ApiRoutes.isAllowed("DELETE", "/varro/session-trash/ses_1/delete"))
        // The action and method have to agree.
        assertFalse(ApiRoutes.isAllowed("POST", "/varro/session-trash/ses_1/delete"))
        assertTrue(ApiRoutes.isAllowed("POST", "/varro/session/ses_1/pin"))
        assertTrue(ApiRoutes.isAllowed("DELETE", "/varro/session/ses_1/delete"))
    }

    @Test
    fun `model pricing requires both identifiers and only allows GET`() {
        val path = "/varro/model-pricing?providerID=openai&modelID=gpt-5"
        assertTrue(ApiRoutes.isAllowed("GET", path))
        assertFalse(ApiRoutes.isAllowed("POST", path))
        assertFalse(ApiRoutes.isAllowed("GET", "$path&url=https://example.com"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/model-pricing?providerID=openai"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/model-pricing?modelID=gpt-5"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/model-pricing?providerID=openai&modelID="))
    }

    @Test
    fun `extracts the session id from host namespace paths`() {
        assertEquals("ses_123", ApiRoutes.varroSessionId("/varro/session/ses_123/pin"))
        assertEquals("ses_123", ApiRoutes.varroSessionId("/varro/session/ses_123/diff-summary?revision=x"))
        assertEquals(null, ApiRoutes.varroSessionId("/varro/session-trash"))
        assertEquals(null, ApiRoutes.varroSessionId("/session/ses_123"))
    }

    @Test
    fun `mcp and oauth callbacks are reachable for the connect flows`() {
        assertTrue(ApiRoutes.isAllowed("POST", "/mcp/github/auth/authenticate"))
        assertTrue(ApiRoutes.isAllowed("POST", "/mcp/github/connect"))
        assertTrue(ApiRoutes.isAllowed("POST", "/mcp/github/disconnect"))
        assertFalse(ApiRoutes.isAllowed("POST", "/mcp/github/anything-else"))
        assertTrue(ApiRoutes.isAllowed("POST", "/provider/anthropic/oauth/authorize"))
        assertTrue(ApiRoutes.isAllowed("POST", "/provider/anthropic/oauth/callback"))
        assertFalse(ApiRoutes.isAllowed("POST", "/provider/anthropic/oauth/steal"))
    }
}
