package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class OpenCodeV2ArchiveTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private fun spec(native: Boolean) = Json.obj("paths" to Json.obj("/api/session/{sessionID}" to Json.obj("patch" to Json.obj(
        "requestBody" to Json.obj("content" to Json.obj("application/json" to Json.obj("schema" to Json.obj(
            "properties" to if (native) Json.obj("archived" to Json.obj("type" to "boolean")) else Json.obj()))))))))

    @Test fun `archives use native state without migrating or overwriting legacy annotations`() {
        for (native in listOf(true, false)) {
            val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
            state.update("ses_fixture", Json.obj("metadata" to Json.obj("varro" to true), "time" to Json.obj("archived" to 10)))
            var archived = false
            val writes = mutableListOf<com.google.gson.JsonObject>()
            val adapter = OpenCodeV2Adapter({ method, path, body, _ -> OpenCodeResponse(when (path) {
                "/openapi.json" -> spec(native)
                "/api/session/ses_fixture" -> {
                    if (method == "PATCH") { writes.add(body!!.asJsonObject); archived = body.asJsonObject.bool("archived") == true }
                    Json.obj("data" to Json.obj("id" to "ses_fixture", "time" to Json.obj("created" to 1, "updated" to 2).apply {
                        if (archived) addProperty("archived", 500)
                    }))
                }
                else -> error("Unexpected $method $path")
            }) }, state)
            fun read() = adapter.request("GET", "/session/ses_fixture", null, RequestOptions()).data.asObjectOrNull()
            assertEquals(if (native) null else 10.0, read().obj("time").num("archived"))
            assertTrue(writes.isEmpty())
            val result = adapter.request("PATCH", "/session/ses_fixture", Json.obj("time" to Json.obj("archived" to 100, "updated" to 20)), RequestOptions()).data.asObjectOrNull()
            assertEquals(if (native) 500.0 else 100.0, result.obj("time").num("archived"))
            assertEquals(20.0, result.obj("time").num("updated"))
            if (native) assertEquals(Json.obj("archived" to true), writes.single()) else assertTrue(writes.isEmpty())
            adapter.request("PATCH", "/session/ses_fixture", Json.obj("time" to Json.obj("archived" to null)), RequestOptions())
            assertNull(read().obj("time").num("archived"))
            assertEquals(if (native) 10.0 else null, state.read("ses_fixture").obj("time").num("archived"))
            assertEquals(Json.obj("varro" to true), read().obj("metadata"))
        }
    }

    @Test fun `invalid archive values never mutate native or local state`() {
        val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
        val adapter = OpenCodeV2Adapter({ _, _, _, _ -> error("Must validate before discovery or mutation") }, state)
        for (value in listOf("123", true, Json.obj(), Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) {
                adapter.request("PATCH", "/session/ses_fixture", Json.obj("time" to Json.obj("archived" to value)), RequestOptions())
            }
        }
        assertEquals(Json.obj(), state.read("ses_fixture"))
    }

    @Test fun `ordinary reads avoid discovery and old servers without OpenAPI retain local archives`() {
        var probes = 0
        val adapter = OpenCodeV2Adapter({ method, path, _, _ ->
            assertEquals("GET", method)
            if (path == "/openapi.json") { probes++; error("404 Not Found") }
            OpenCodeResponse(Json.obj("data" to Json.obj("id" to "ses_fixture", "time" to Json.obj("created" to 1))))
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        adapter.request("GET", "/session/ses_fixture", null, RequestOptions())
        assertEquals(0, probes)
        adapter.request("PATCH", "/session/ses_fixture", Json.obj("time" to Json.obj("archived" to 100)), RequestOptions())
        assertEquals(100.0, adapter.request("GET", "/session/ses_fixture", null, RequestOptions()).data.asObjectOrNull().obj("time").num("archived"))
        assertEquals(1, probes)
    }

    @Test fun `failed discovery and native updates never commit local metadata or archives`() {
        for (failure in listOf("401 Unauthorized", "503 unavailable", "malformed", "native write failed")) {
            val state = OpenCodeV2SessionState(temporary.newFolder().toPath())
            val adapter = OpenCodeV2Adapter({ _, path, _, _ ->
                if (path == "/openapi.json") {
                    if (failure == "native write failed") OpenCodeResponse(spec(true))
                    else if (failure == "malformed") OpenCodeResponse(Json.obj("data" to Json.obj()))
                    else error(failure)
                } else error(failure)
            }, state)
            assertThrows(Exception::class.java) { adapter.request("PATCH", "/session/ses_fixture",
                Json.obj("metadata" to Json.obj("test" to true), "time" to Json.obj("archived" to 100)), RequestOptions()) }
            assertEquals(Json.obj(), state.read("ses_fixture"))
        }
    }

    @Test fun `archive discovery retries failures and resets on reconnect`() {
        var reads = 0
        var supported = false
        val adapter = OpenCodeV2Adapter({ _, path, _, _ -> OpenCodeResponse(when (path) {
            "/openapi.json" -> { reads++; if (reads == 1) error("503 unavailable"); spec(supported) }
            else -> Json.obj("data" to Json.obj("id" to "ses_fixture", "time" to Json.obj()))
        }) }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        val patch = Json.obj("time" to Json.obj("archived" to 100))
        assertThrows(IllegalStateException::class.java) { adapter.request("PATCH", "/session/ses_fixture", patch, RequestOptions()) }
        adapter.request("PATCH", "/session/ses_fixture", patch, RequestOptions())
        adapter.request("GET", "/session/ses_fixture", null, RequestOptions())
        assertEquals(2, reads)
        supported = true
        adapter.events(Json.obj("type" to "server.connected", "data" to Json.obj()))
        assertNull(adapter.request("GET", "/session/ses_fixture", null, RequestOptions()).data.asObjectOrNull().obj("time").num("archived"))
        assertEquals(3, reads)
    }

    @Test fun `archive events reach the shared event parser without transcript activity`() {
        for (type in listOf("session.archived", "session.unarchived")) {
            val event = OpenCodeV2Events.project(Json.obj("type" to type, "id" to "evt_archive", "durable" to Json.obj("seq" to 4),
                "location" to Json.obj("directory" to "/fixture"), "data" to Json.obj("sessionID" to "ses_fixture")), Json.obj()).single()
            val parsed = ServerEvents.parse(event)!!
            assertEquals(type, parsed.type)
            assertEquals("ses_fixture", parsed.properties.str("sessionID"))
            assertEquals("/fixture", parsed.workspaceDirectory)
            assertEquals(4, parsed.seq)
        }
    }

    @Test fun `include archived legacy lists do not become native archived only filters`() {
        val adapter = OpenCodeV2Adapter({ _, path, _, _ ->
            assertFalse(path.contains("archived"))
            OpenCodeResponse(Json.obj("data" to emptyList<Any>()))
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        adapter.request("GET", "/session?archived=true", null, RequestOptions())
        adapter.request("GET", "/experimental/session?archived=true", null, RequestOptions())
    }

    @Test fun `tool replay projects native fields and keeps older event fallbacks`() {
        for (type in listOf("called", "success", "failed")) for (native in listOf(true, false)) {
            val field = if (type == "called") "state" else "resultState"
            val data = Json.obj("sessionID" to "ses_fixture", "id" to "call", "name" to "shell",
                (if (native) "native" else field) to Json.obj("replay" to type))
            if (!native) data.add("native", com.google.gson.JsonNull.INSTANCE)
            val projected = OpenCodeV2Events.project(Json.obj("type" to "session.tool.$type", "data" to data), Json.obj()).single()
            assertEquals(Json.obj("replay" to type), projected.obj("properties").obj(if (type == "called") "provider" else "result"))
            assertEquals("bash", projected.obj("properties").str("name"))
        }
    }
}
