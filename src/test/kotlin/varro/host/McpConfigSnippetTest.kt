package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class McpConfigSnippetTest {
    private val remote = Json.obj("type" to "remote", "url" to "https://example.com/mcp")

    @Test fun `accepts named maps and supported config wrappers without importing unrelated settings`() {
        val servers = Json.obj("docs" to remote)
        for (config in listOf(servers, Json.obj("mcp" to servers), Json.obj("servers" to servers),
            Json.obj("mcpServers" to servers), Json.obj("mcp" to Json.obj("timeout" to 1000, "servers" to servers), "model" to "not/imported"))) {
            val snippet = McpConfigSnippet.parse(config.toString())
            assertEquals(servers, snippet.servers); assertFalse(snippet.requiresName)
        }
    }

    @Test fun `JSONC bare servers need names and normalize command args env without resolving secrets`() {
        val bare = "{ // copied snippet\n \"url\": \"https://example.com/mcp\", }"
        assertTrue(McpConfigSnippet.parse(bare).requiresName)
        assertEquals(Json.obj(), McpConfigSnippet.parse(bare).servers)
        assertEquals(Json.obj("docs" to remote), McpConfigSnippet.parse(bare, " docs ").servers)
        val local = McpConfigSnippet.parse("""{"mcpServers":{"browser":{"command":"npx","args":["-y","browser-mcp"],"env":{"TOKEN":"{env:MCP_TOKEN}"}}}}""")
        assertEquals(Json.obj("browser" to Json.obj("type" to "local", "command" to listOf("npx", "-y", "browser-mcp"),
            "environment" to Json.obj("TOKEN" to "{env:MCP_TOKEN}"))), local.servers)
    }

    @Test fun `malformed JSON structures reserved names unsafe URLs and wrong field types reject the entire snippet`() {
        val invalid = listOf("", "[]", "null", "{}", "{broken}", "{\"mcp\":null}", "{\"mcp\":{\"servers\":[]}}",
            """{"docs":true}""", """{"docs":{"url":"file:///secret"}}""", """{"docs":{"url":"/relative"}}""",
            """{"docs":{"type":"local","command":[]}}""", """{"docs":{"command":["node",5]}}""",
            """{"docs":{"command":"node","args":"bad"}}""", """{"docs":{"command":["node"],"args":[]}}""",
            """{"docs":{"url":"https://example.com","enabled":"yes"}}""",
            """{"docs":{"url":"https://example.com","enabled":true,"disabled":false}}""",
            """{"docs":{"url":"https://example.com","headers":{"Key":42}}}""",
            """{"docs":{"command":"node","env":{},"environment":{}}}""",
            """{"__proto__":{"url":"https://example.com"}}""", """{"constructor":{"url":"https://example.com"}}""",
            """{"prototype":{"url":"https://example.com"}}""", """{" docs ":{"url":"https://example.com"}}""",
            Json.obj("x".repeat(513) to remote).toString(), " ".repeat(100_001))
        for (text in invalid) {
            try { McpConfigSnippet.parse(text); fail("Expected invalid snippet: ${text.take(100)}") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }
}
