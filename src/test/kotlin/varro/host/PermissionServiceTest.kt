package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.store.VarroStore

class PermissionServiceTest {
    private val body = Json.obj("sessionId" to "s", "permissionId" to "p")
    private fun pending() = Json.obj("id" to "p", "sessionID" to "s", "permission" to "bash", "always" to listOf("npm *"))
    private fun session(directory: String = "/workspace") = Json.obj("id" to "s", "directory" to directory)

    @Test fun `session approval saves rules exactly once and never replies or reloads config`() {
        val calls = mutableListOf<String>()
        val store = VarroStore()
        val existing = Json.array(listOf(Json.obj("permission" to "*", "pattern" to "*", "action" to "ask")))
        val service = PermissionService(store) { method, path, payload, directory ->
            assertEquals("/workspace", directory)
            calls.add("$method $path")
            when ("$method $path") {
                "GET /permission" -> Json.array(listOf(pending()))
                "GET /session/s" -> session().apply { add("permission", existing) }
                "PATCH /session/s" -> Json.obj("permission" to payload.asObjectOrNull().arr("permission"))
                else -> error("Unexpected request: $method $path")
            }
        }
        val rules = service.allow(body, false, "/workspace")
        assertEquals(listOf("GET /session/s", "GET /permission", "GET /session/s", "PATCH /session/s"), calls)
        assertEquals("ask", rules[0].asJsonObject.str("action"))
        assertEquals("npm *", rules[1].asJsonObject.str("pattern"))
        assertEquals(rules, store.permissionRules.arr("s"))
    }

    @Test fun `project approval merges an existing earlier permission group without touching session or runtime`() {
        val original = Json.obj("bash" to Json.obj("*" to "ask", "npm *" to "deny"), "edit" to "ask")
        val expected = Json.obj("bash" to Json.obj("*" to "ask", "npm *" to "allow"), "edit" to Json.obj("*" to "ask"))
        assertEquals(expected, projectApproval(original))
    }

    @Test fun `project approval preserves scalar defaults and unrelated rules`() {
        assertEquals(Json.obj("bash" to Json.obj("*" to "deny", "npm *" to "allow"), "edit" to Json.obj("*" to "ask")),
            projectApproval(Json.obj("bash" to "deny", "edit" to "ask")))
        val scalar = projectApproval(Json.toElement("ask"))
        val rules = PermissionService.fromConfig(scalar)
        assertEquals("*", rules[0].asJsonObject.str("permission"))
        assertEquals("ask", rules[0].asJsonObject.str("action"))
        assertEquals("allow", rules.last().asJsonObject.str("action"))
    }

    @Test fun `project approval preserves inherited scalar permissions when creating pattern overrides`() {
        assertEquals(Json.obj("bash" to Json.obj("*" to "ask", "npm *" to "allow")),
            projectApproval(Json.obj(), Json.obj("bash" to "ask")))
        assertEquals(Json.obj("*" to Json.obj("*" to "deny"), "bash" to Json.obj("npm *" to "allow")),
            projectApproval(Json.obj(), Json.toElement("deny")))
    }

    @Test fun `v2 pending wrappers and request id aliases are accepted`() {
        for (id in listOf("id", "permissionID", "requestID")) {
            val permission = pending().apply { remove("id"); addProperty(id, "p") }
            val service = PermissionService(VarroStore()) { method, path, _, _ ->
                when ("$method $path") {
                    "GET /permission" -> Json.array(listOf(Json.obj("info" to permission)))
                    "GET /session/s", "PATCH /session/s" -> session().apply { add("permission", JsonArray()) }
                    else -> error("Unexpected request")
                }
            }
            assertEquals("npm *", service.allow(body, false, null)[0].asJsonObject.str("pattern"))
        }
    }

    @Test fun `display patterns cannot substitute for missing standing approval scope`() {
        for (always in listOf(null, JsonArray(), Json.array(listOf(1, " ")))) {
            val permission = pending().apply {
                remove("always")
                always?.let { add("always", it) }
                add("patterns", Json.array(listOf("npm test")))
            }
            val service = PermissionService(VarroStore()) { method, path, _, _ ->
                assertEquals("GET", method)
                if (path == "/session/s") return@PermissionService session()
                assertEquals("/permission", path)
                Json.array(listOf(permission))
            }
            assertThrows(IllegalArgumentException::class.java) { service.allow(body, false, null) }
        }
    }

    @Test fun `failed project save is propagated without replying or changing session rules`() {
        val service = PermissionService(VarroStore(), { JsonArray() }, { _, _ -> error("disk full") }) { method, path, _, _ ->
            assertEquals("GET", method)
            when (path) {
                "/session/s" -> session()
                "/permission" -> Json.array(listOf(pending()))
                "/config" -> Json.obj()
                else -> error("Unexpected request: $path")
            }
        }
        val failure = assertThrows(IllegalStateException::class.java) { service.allow(body, true, null) }
        assertEquals("disk full", failure.message)
    }

    private fun projectApproval(local: JsonElement, effective: JsonElement = local): JsonElement {
        var saved: JsonArray? = null
        val store = VarroStore()
        val service = PermissionService(store, { PermissionService.fromConfig(local) }, { rules, directory ->
            assertEquals("/workspace", directory)
            saved = rules
        }) { method, path, _, directory ->
            assertEquals("/workspace", directory)
            // Standing approvals do not reply or reload the runtime.
            assertEquals("GET", method)
            when (path) {
                "/session/s" -> session()
                "/permission" -> Json.array(listOf(pending()))
                "/config" -> Json.obj("permission" to effective)
                else -> error("Unexpected request: $path")
            }
        }
        assertEquals(service.allow(body, true, "/workspace"), saved)
        assertEquals(0, store.permissionRules.size())
        return PermissionService.toConfig(saved!!)
    }

    @Test fun `scoped approvals use the owning directory for all reads and writes`() {
        for (project in listOf(false, true)) for (directory in listOf("/repo-b", "/repo/packages/other")) {
            var saved = false
            val service = PermissionService(VarroStore(), {
                assertEquals(directory, it)
                JsonArray()
            }, { _, target ->
                assertEquals(directory, target)
                saved = true
            }) { method, path, _, target ->
                assertEquals(directory, target)
                when ("$method $path") {
                    "GET /session/s" -> session(directory)
                    "GET /permission" -> Json.array(listOf(pending()))
                    "GET /config" -> Json.obj()
                    "PATCH /session/s" -> { saved = true; session(directory) }
                    else -> error("Unexpected request: $method $path")
                }
            }
            service.allow(body, project, directory)
            assertTrue(saved)
        }
    }

    @Test fun `scoped approvals reject mismatched session directories before reading permissions`() {
        for (project in listOf(false, true)) {
            val service = PermissionService(VarroStore()) { method, path, _, directory ->
                assertEquals("GET", method)
                assertEquals("/session/s", path)
                assertEquals("/repo-b", directory)
                session("/repo")
            }
            assertThrows(IllegalArgumentException::class.java) { service.allow(body, project, "/repo-b") }
        }
    }

    @Test fun `scoped approvals reject a pending permission owned by another session`() {
        for (project in listOf(false, true)) {
            val service = PermissionService(VarroStore()) { method, path, _, _ ->
                assertEquals("GET", method)
                when (path) {
                    "/session/s" -> session()
                    "/permission" -> Json.array(listOf(pending().apply { addProperty("sessionID", "other") }))
                    else -> error("Unexpected request: $path")
                }
            }
            assertThrows(IllegalStateException::class.java) { service.allow(body, project, "/workspace") }
        }
    }
}
