package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.ApiRoutes
import varro.protocol.*
import java.util.concurrent.CancellationException

class OpenCodeV2TodosTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `native todo restoration reads scoped newest history without returning a partial streaming tool`() {
        val expected = Json.array(listOf(Json.obj("id" to "saved", "content" to "Saved task", "status" to "pending", "priority" to "high")))
        fun tool(status: String, todos: Any) = Json.obj("type" to "tool", "name" to "todowrite",
            "state" to Json.obj("status" to status, "input" to Json.obj("todos" to todos)))
        var calls = 0
        val adapter = OpenCodeV2Adapter({ method, path, body, options ->
            calls++
            assertEquals("GET", method)
            assertEquals("/api/session/child/message?limit=100&order=desc", path)
            assertEquals("/repo", options.directory)
            assertNull(body)
            OpenCodeResponse(Json.obj("data" to listOf(
                Json.obj("type" to "user", "content" to listOf(tool("completed", emptyList<Any>()))),
                Json.obj("type" to "assistant", "content" to listOf(
                    tool("completed", expected),
                    tool("completed", "malformed"),
                    tool("completed", emptyList<Any>()).apply { addProperty("type", "text") },
                    tool("streaming", emptyList<Any>()),
                )),
            )))
        }, OpenCodeV2SessionState(temporary.root.toPath()))
        assertEquals(expected, adapter.request("GET", "/session/child/todo", null, RequestOptions(directory = "/repo")).data)
        assertEquals(1, calls)
        assertTrue(ApiRoutes.isAllowed("GET", "/session/child/todo?directory=%2Frepo"))
        assertThrows(CancellationException::class.java) {
            adapter.request("GET", "/session/child/todo", null, RequestOptions(directory = "/repo", isCancelled = { true }))
        }
        assertEquals(1, calls)
    }

    @Test fun `a session with no native todos returns an empty array without lifecycle or generation requests`() {
        val adapter = OpenCodeV2Adapter({ method, path, _, _ ->
            assertEquals("GET", method)
            assertEquals("/api/session/empty/message?limit=100&order=desc", path)
            OpenCodeResponse(Json.obj("data" to emptyList<Any>()))
        }, OpenCodeV2SessionState(temporary.root.toPath()))
        assertEquals(Json.array(emptyList<Any>()), adapter.request("GET", "/session/empty/todo", null, RequestOptions()).data)
    }
}
