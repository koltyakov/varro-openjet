package varro.server

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class OpenCodeSavedPermissionsTest {
    private fun spec() = Json.obj("paths" to Json.obj("/api/permission/saved/{id}" to Json.obj("patch" to Json.obj(
        "requestBody" to Json.obj("content" to Json.obj("application/json" to Json.obj("schema" to Json.obj(
            "properties" to Json.obj("action" to Json.obj(), "resource" to Json.obj())))))))))
    private fun observed() = Json.obj("id" to "legacy:request:0", "projectID" to "project", "permission" to "bash", "pattern" to "git *", "retractable" to false)

    @Test fun `only observed project allowances can be edited and native IDs follow external edits`() {
        var saved = Json.obj("id" to "saved", "projectID" to "project", "action" to "shell", "resource" to "git *")
        val mutations = mutableListOf<JsonObject>()
        val service = OpenCodeSavedPermissions({ 2 }) { method, path, body, options ->
            assertEquals("/fixture", options.directory)
            OpenCodeResponse(when {
                path == "/project/current" -> Json.obj("id" to "project")
                path == "/openapi.json" -> spec()
                path.startsWith("/api/permission/saved?") -> Json.obj("data" to listOf(saved,
                    Json.obj("id" to "foreign", "projectID" to "other", "action" to "shell", "resource" to "git *"),
                    Json.obj("id" to "unobserved", "projectID" to "project", "action" to "shell", "resource" to "rm *")))
                path == "/api/permission/saved/saved" && method == "PATCH" -> {
                    mutations.add(body!!.asJsonObject)
                    saved = saved.deepCopy().apply { add("action", body.asJsonObject.get("action")); add("resource", body.asJsonObject.get("resource")) }
                    Json.obj("data" to saved)
                }
                else -> error("Unexpected $method $path")
            })
        }
        val options = RequestOptions(directory = "/fixture")
        assertEquals(0, service.handle("GET", null, null, options).arr("rules")!!.size())
        service.remember(listOf(observed()))
        val rule = service.handle("GET", null, null, options).arr("rules")!!.single().asJsonObject
        assertEquals("saved", rule.str("id")); assertEquals("bash", rule.str("permission")); assertEquals(true, rule.bool("editable"))
        saved.addProperty("resource", "git status")
        assertEquals("git status", service.handle("GET", null, null, options).arr("rules")!!.single().asJsonObject.str("pattern"))
        val result = service.handle("PATCH", null, Json.obj("id" to "saved", "permission" to "task", "pattern" to "audit *"), options)
        assertEquals(Json.obj("action" to "subagent", "resource" to "audit *"), mutations.single())
        assertEquals("task", result.arr("rules")!!.single().asJsonObject.str("permission"))
        for (id in listOf("foreign", "unobserved")) assertThrows(IllegalStateException::class.java) {
            service.handle("DELETE", null, Json.obj("id" to id), options)
        }
        assertEquals(1, mutations.size)
    }

    @Test fun `optional discovery failure preserves list remove and restart only fallback`() {
        var available = true
        var deleted = false
        val service = OpenCodeSavedPermissions({ 2 }) { method, path, _, _ -> OpenCodeResponse(when {
            path == "/project/current" -> Json.obj("id" to "project")
            path == "/openapi.json" -> error("503 discovery unavailable")
            path.startsWith("/api/permission/saved?") -> {
                if (!available) error("404 unavailable")
                Json.obj("data" to if (deleted) emptyList<Any>() else listOf(Json.obj("id" to "saved", "projectID" to "project", "action" to "shell", "resource" to "git *")))
            }
            method == "DELETE" && path == "/api/permission/saved/saved" -> { deleted = true; null }
            else -> error("Unexpected $method $path")
        }) }
        service.remember(listOf(observed()))
        val options = RequestOptions()
        assertFalse(service.handle("GET", null, null, options).arr("rules")!!.single().asJsonObject.has("editable"))
        assertThrows(IllegalStateException::class.java) {
            service.handle("PATCH", null, Json.obj("id" to "saved", "permission" to "bash", "pattern" to "git status"), options)
        }
        assertEquals(0, service.handle("DELETE", null, Json.obj("id" to "saved"), options).arr("rules")!!.size())
        available = false
        service.remember(listOf(observed()))
        assertEquals(false, service.handle("GET", null, null, options).arr("rules")!!.single().asJsonObject.bool("retractable"))
        assertThrows(IllegalArgumentException::class.java) { service.handle("DELETE", null, Json.obj("id" to "legacy:request:0"), options) }
    }

    @Test fun `conflicts unverified acknowledgements and invalid drafts do not change mirrored scope`() {
        var conflict = true
        var patches = 0
        val service = OpenCodeSavedPermissions({ 2 }) { method, path, _, _ -> OpenCodeResponse(when {
            path == "/project/current" -> Json.obj("id" to "project")
            path == "/openapi.json" -> spec()
            path.startsWith("/api/permission/saved?") -> Json.obj("data" to listOf(Json.obj("id" to "saved", "projectID" to "project", "action" to "shell", "resource" to "git *")))
            method == "PATCH" -> { patches++; if (conflict) error("409 Conflict"); Json.obj("data" to Json.obj("id" to "saved", "projectID" to "other")) }
            else -> error("Unexpected $method $path")
        }) }
        service.remember(listOf(observed()))
        for (failure in listOf(true, false)) {
            conflict = failure
            assertThrows(IllegalStateException::class.java) {
                service.handle("PATCH", null, Json.obj("id" to "saved", "permission" to "bash", "pattern" to "changed"), RequestOptions())
            }
            assertEquals("git *", service.handle("GET", null, null, RequestOptions()).arr("rules")!!.single().asJsonObject.str("pattern"))
        }
        for ((name, pattern) in listOf("" to "x", "x" to "", "x".repeat(201) to "x", "x" to "x".repeat(2001))) {
            assertThrows(IllegalArgumentException::class.java) { service.handle("PATCH", null, Json.obj("id" to "saved", "permission" to name, "pattern" to pattern), RequestOptions()) }
        }
        assertEquals(2, patches)
    }

    @Test fun `always allowances are remembered only after acknowledgement and reset with the connection`() {
        val service = OpenCodeSavedPermissions({ 1 }) { _, path, _, _ -> OpenCodeResponse(when (path) {
            "/permission" -> Json.array(listOf(Json.obj("info" to Json.obj("requestID" to "request", "sessionID" to "session", "type" to "bash", "always" to listOf("git *", "git *", " ")))))
            "/session/session" -> Json.obj("projectID" to "project")
            "/project/current" -> Json.obj("id" to "project")
            else -> error("404 unavailable")
        }) }
        val rules = service.prepare("POST", "/permission/request/reply", Json.obj("reply" to "always"), RequestOptions())
        assertEquals(1, rules.size)
        assertEquals(false, service.handle("GET", null, null, RequestOptions()).bool("supported"))
        service.remember(rules)
        assertEquals(rules.single(), service.handle("GET", null, null, RequestOptions()).arr("rules")!!.single())
        service.reset()
        assertEquals(false, service.handle("GET", null, null, RequestOptions()).bool("supported"))
    }

    @Test fun `approval captures native IDs before settings opens and reload retains edits and removal`() {
        var stored = Json.array(emptyList<Any>())
        var saved = Json.obj("id" to "saved", "projectID" to "project", "action" to "shell", "resource" to "git *")
        var removed = false
        var server = "http://localhost:1234"
        var version = 2
        val mutations = mutableListOf<String>()
        fun service() = OpenCodeSavedPermissions({ version }, stored, { stored = it }, { server }) { method, path, body, _ ->
            OpenCodeResponse(when {
                path == "/project/current" -> Json.obj("id" to "project")
                path == "/session/session" -> Json.obj("projectID" to "project")
                path == "/openapi.json" -> spec()
                path.startsWith("/api/permission/saved?") -> Json.obj("data" to listOfNotNull(if (removed) null else saved,
                    Json.obj("id" to "internal", "projectID" to "project", "action" to "shell", "resource" to "internal *")))
                method == "PATCH" -> {
                    mutations += path
                    saved = saved.deepCopy().apply { add("action", body!!.asJsonObject.get("action")); add("resource", body.asJsonObject.get("resource")) }
                    Json.obj("data" to saved)
                }
                method == "DELETE" -> { mutations += path; removed = true; null }
                else -> error("Unexpected $method $path")
            })
        }
        val original = service()
        val options = RequestOptions()
        // No management link exists before the successful approval is recorded.
        assertEquals(0, stored.size())
        original.approved(listOf(observed()), options)
        assertEquals("saved", stored.single().asJsonObject.obj("rule").str("id"))
        assertFalse(stored.single().asJsonObject.obj("rule")!!.has("editable"))
        val restored = service()
        server = "http://localhost:4321"
        assertEquals(0, restored.handle("GET", null, null, options).arr("rules")!!.size())
        server = "http://localhost:1234"
        version = 1
        assertEquals(0, restored.handle("GET", null, null, options).arr("rules")!!.size())
        version = 2
        restored.reset()
        saved.addProperty("resource", "external edit")
        assertEquals("external edit", restored.handle("GET", "session", null, options).arr("rules")!!.single().asJsonObject.str("pattern"))
        restored.handle("PATCH", null, Json.obj("id" to "saved", "permission" to "read", "pattern" to "README.md"), options)
        assertEquals("read", service().handle("GET", null, null, options).arr("rules")!!.single().asJsonObject.str("permission"))
        service().handle("DELETE", null, Json.obj("id" to "saved"), options)
        assertEquals(0, stored.size())
        assertEquals(0, service().handle("GET", null, null, options).arr("rules")!!.size())
        assertEquals(listOf("/api/permission/saved/saved", "/api/permission/saved/saved"), mutations)
    }

    @Test fun `removed native ID cannot adopt an unobserved replacement with the same scope`() {
        var stored = Json.array(emptyList<Any>())
        var id = "saved"
        val service = OpenCodeSavedPermissions({ 2 }, persist = { stored = it }) { _, path, _, _ -> OpenCodeResponse(when {
            path == "/project/current" -> Json.obj("id" to "project")
            path == "/openapi.json" -> spec()
            path.startsWith("/api/permission/saved?") -> Json.obj("data" to listOf(Json.obj("id" to id,
                "projectID" to "project", "action" to "shell", "resource" to "git *")))
            else -> error("Unexpected $path")
        }) }
        service.approved(listOf(observed()), RequestOptions())
        assertEquals(1, stored.size())
        id = "internal-replacement"
        assertEquals(0, service.handle("GET", null, null, RequestOptions()).arr("rules")!!.size())
        assertEquals(0, stored.size())
        assertThrows(IllegalStateException::class.java) {
            service.handle("DELETE", null, Json.obj("id" to id), RequestOptions())
        }
    }

    @Test fun `unavailable native list keeps approval successful without persisting legacy allowances`() {
        var writes = 0
        val service = OpenCodeSavedPermissions({ 2 }, persist = { writes++ }) { _, path, _, _ ->
            if (path == "/project/current") OpenCodeResponse(Json.obj("id" to "project")) else error("503 unavailable")
        }
        service.approved(listOf(observed()), RequestOptions())
        assertEquals(0, writes)
        assertEquals(false, service.handle("GET", null, null, RequestOptions()).arr("rules")!!.single().asJsonObject.bool("retractable"))
        service.reset()
        assertEquals(false, service.handle("GET", null, null, RequestOptions()).bool("supported"))
    }

    @Test fun `capabilities resolve escaped refs compositions and recursive schemas safely`() {
        val value = spec()
        val schema = value.obj("paths").obj("/api/permission/saved/{id}").obj("patch").obj("requestBody").obj("content").obj("application/json")!!
        schema.add("schema", Json.obj("\$ref" to "#/components/schemas/edit~1saved~0"))
        value.add("components", Json.obj("schemas" to Json.obj("edit/saved~" to Json.obj("allOf" to listOf(
            Json.obj("properties" to Json.obj("action" to Json.obj())), Json.obj("properties" to Json.obj("resource" to Json.obj())))))))
        assertTrue(OpenCodeApiCapabilities.read(value).savedPermissionEditing)
        value.obj("components").obj("schemas")!!.add("edit/saved~", Json.obj("\$ref" to "#/components/schemas/edit~1saved~0"))
        assertFalse(OpenCodeApiCapabilities.read(value).savedPermissionEditing)
    }
}
