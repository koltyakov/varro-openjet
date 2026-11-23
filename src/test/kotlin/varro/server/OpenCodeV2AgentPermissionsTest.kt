package varro.server

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class OpenCodeV2AgentPermissionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val nativeRules = Json.array(listOf(Json.obj("action" to "edit", "resource" to "*", "effect" to "deny")))
    private fun read(config: JsonObject, resolvedPermissions: Boolean = true): JsonObject {
        val resolved = Json.obj("id" to "review", "mode" to "subagent", "request" to Json.obj())
        if (resolvedPermissions) resolved.add("permissions", nativeRules)
        val adapter = OpenCodeV2Adapter({ method, path, body, options ->
            assertEquals("GET", method); assertNull(body); assertEquals("/repo", options.directory)
            OpenCodeResponse(when (path) {
                "/api/agent?location%5Bdirectory%5D=%2Frepo" -> Json.obj("data" to listOf(resolved))
                "/api/config?location%5Bdirectory%5D=%2Frepo" -> Json.obj("data" to listOf(Json.obj("info" to Json.obj("agents" to Json.obj("review" to config)))))
                else -> error("Unexpected $method $path")
            })
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        return adapter.request("GET", "/agent", null, RequestOptions(directory = "/repo")).data!!.asJsonArray.single().asJsonObject
    }

    @Test fun `omitted and null configured permissions preserve resolved agent restrictions`() {
        for (config in listOf(Json.obj(), Json.obj("permissions" to null))) {
            assertEquals(OpenCodeV2Projection.legacyRules(nativeRules), read(config).get("permission"))
        }
    }

    @Test fun `explicit empty configured permissions override resolved restrictions`() {
        assertEquals(Json.array(emptyList<Any>()), read(Json.obj("permissions" to emptyList<Any>())).get("permission"))
    }

    @Test fun `explicit configured rules replace resolved rules while preserving the agent role`() {
        val configured = Json.array(listOf(Json.obj("action" to "shell", "resource" to "git *", "effect" to "ask")))
        val result = read(Json.obj("permissions" to configured, "description" to "Reviewer"))
        assertEquals(OpenCodeV2Projection.legacyRules(configured), result.get("permission"))
        assertEquals("subagent", result.str("mode"))
        assertEquals("Reviewer", result.str("description"))
    }

    @Test fun `unavailable native and configured permission data stays unknown instead of becoming an empty grant list`() {
        assertFalse(read(Json.obj(), resolvedPermissions = false).has("permission"))
    }
}
