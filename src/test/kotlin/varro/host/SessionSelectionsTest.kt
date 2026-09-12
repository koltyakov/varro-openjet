package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.store.VarroStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionSelectionsTest {
    private class Fixture {
        val store = VarroStore()
        var session = Json.obj("id" to "session", "time" to Json.obj("updated" to 2),
            "metadata" to Json.obj("custom" to "preserved", "varro" to Json.obj(
                "workspaceScope" to "folder", "extensionData" to Json.obj("keep" to true))))
        val patches = mutableListOf<JsonObject>()
        val agents = mutableListOf<String>()
        var failPatch = false
        val selections: SessionSelections = SessionSelections(store, publishAgent = { _, agent -> agents.add(agent) }) { method, path, body, directory ->
            assertEquals("/session/session", path)
            assertEquals("/repo", directory)
            if (method == "PATCH") {
                if (failPatch) error("metadata unavailable")
                patches.add(body!!.asJsonObject.deepCopy())
                body.asJsonObject.entrySet().forEach { (key, value) -> session.add(key, value.deepCopy()) }
            }
            // Transport callbacks must not restore the old GET or the unconfirmed PATCH.
            observe(session)
            session.deepCopy()
        }
        private fun observe(value: JsonObject): Unit = selections.observe(value)
    }

    @Test fun `concurrent writes preserve metadata and another instance restores all selections read only`() {
        val fixture = Fixture()
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val model = model("high")
        try {
            val tasks = listOf<() -> Unit>(
                { fixture.selections.updateModel("session", model, "/repo") },
                { fixture.selections.updateAgent("session", "plan", "/repo") },
                { fixture.selections.updateMode("session", Json.obj("mode" to "auto"), "/repo") },
            ).map { task -> executor.submit { start.await(); task() } }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(Json.obj("custom" to "preserved", "varro" to Json.obj("workspaceScope" to "folder",
            "extensionData" to Json.obj("keep" to true),
            "schemaVersion" to 1, "model" to storedModel("high"), "agent" to "plan", "permissionMode" to "auto")),
            fixture.session.obj("metadata"))
        val second = VarroStore()
        second.updateSessionModel("session", model("low"))
        second.updateSessionAgent("session", "build")
        second.updateSessionPermissionMode("session", Json.toElement("full"))
        val restoredAgents = mutableListOf<String>()
        val reader = SessionSelections(second, publishAgent = { _, agent -> restoredAgents.add(agent) }) { _, _, _, _ ->
            error("Restoration must not write to OpenCode")
        }
        reader.observe(fixture.session)
        assertEquals(model, second.sessionSelectedModels.get("session"))
        assertEquals("plan", second.sessionPlanAgents.str("session"))
        assertEquals("auto", second.sessionPermissionModes.str("session"))
        assertEquals(listOf("plan"), restoredAgents)
        reader.observe(fixture.session)
        assertEquals(listOf("plan"), restoredAgents)
        assertEquals(2, fixture.session.obj("time").int("updated"))
    }

    @Test fun `clearing reasoning replaces the model and identical selections do not patch`() {
        val fixture = Fixture()
        fixture.selections.updateModel("session", model("high"), "/repo")
        fixture.selections.updateModel("session", model(null), "/repo")
        fixture.selections.updateAgent("session", "plan", "/repo")
        fixture.patches.clear()
        fixture.selections.updateModel("session", model(null), "/repo")
        fixture.selections.updateAgent("session", "plan", "/repo")
        assertTrue(fixture.patches.isEmpty())
        assertEquals(storedModel(null), fixture.session.obj("metadata").obj("varro").obj("model"))
        assertEquals(model(null), fixture.store.sessionSelectedModels.get("session"))
    }

    @Test fun `failed writes do not publish confirmed model agent or mode`() {
        val fixture = Fixture()
        fixture.session.add("metadata", Json.obj("varro" to Json.obj("model" to storedModel("low"),
            "agent" to "build", "permissionMode" to "default")))
        fixture.failPatch = true
        assertThrows(IllegalStateException::class.java) { fixture.selections.updateModel("session", model("high"), "/repo") }
        assertThrows(IllegalStateException::class.java) { fixture.selections.updateAgent("session", "plan", "/repo") }
        assertThrows(IllegalStateException::class.java) { fixture.selections.updateMode("session", Json.obj("mode" to "full"), "/repo") }
        assertEquals(JsonObject(), fixture.store.sessionSelectedModels)
        assertEquals(JsonObject(), fixture.store.sessionPlanAgents)
        assertEquals(JsonObject(), fixture.store.sessionPermissionModes)
        assertTrue(fixture.agents.isEmpty())
    }

    @Test fun `mode writes save rules and metadata together while preconfigured modes only save metadata`() {
        val fixture = Fixture()
        fixture.selections.updateMode("session", Json.obj("mode" to "full"), "/repo")
        val full = fixture.patches.last()
        assertEquals("full", full.obj("metadata").obj("varro").str("permissionMode"))
        assertEquals(Json.obj("permission" to "*", "pattern" to "*", "action" to "allow"), full.arr("permission")!!.last())
        fixture.selections.updateMode("session", Json.obj("mode" to "auto"), "/repo")
        val auto = fixture.patches.last().arr("permission")!!
        assertEquals("ask", auto.first().asJsonObject.str("action"))
        assertEquals("allow", auto.first { it.asJsonObject.str("permission") == "read" }.asJsonObject.str("action"))
        assertEquals("ask", auto.first { it.asJsonObject.str("permission") == "bash" }.asJsonObject.str("action"))
        val agentRules = Json.array(listOf(Json.obj("permission" to "*", "pattern" to "*", "action" to "deny")))
        fixture.selections.updateMode("session", Json.obj("mode" to "default", "defaultPermission" to agentRules), "/repo")
        assertEquals(agentRules, fixture.patches.last().arr("permission"))
        fixture.selections.updateMode("session", Json.obj("mode" to "auto", "preconfigured" to true), "/repo")
        assertFalse(fixture.patches.last().has("permission"))
        fixture.patches.clear()
        fixture.selections.updateMode("session", Json.obj("mode" to "auto", "preconfigured" to true), "/repo")
        assertTrue(fixture.patches.isEmpty())
    }

    @Test fun `missing and invalid metadata preserve legacy choices without inferring access from rules`() {
        val fixture = Fixture()
        fixture.store.updateSessionModel("session", model("high"))
        fixture.store.updateSessionAgent("session", "plan")
        fixture.store.updateSessionPermissionMode("session", Json.toElement("auto"))
        fixture.selections.observe(Json.obj("id" to "session"))
        fixture.selections.observe(Json.obj("id" to "session", "metadata" to Json.obj("varro" to Json.obj(
            "model" to Json.obj("provider" to "openai", "model" to "test", "variant" to 1),
            "agent" to " ", "permissionMode" to "invalid")),
            "permission" to Json.array(listOf(Json.obj("permission" to "*", "pattern" to "*", "action" to "allow")))))
        assertEquals(model("high"), fixture.store.sessionSelectedModels.get("session"))
        assertEquals("plan", fixture.store.sessionPlanAgents.str("session"))
        assertEquals("auto", fixture.store.sessionPermissionModes.str("session"))
        assertTrue(fixture.patches.isEmpty())
    }

    @Test fun `old top level fields are ignored and preserved when nested selections are written`() {
        val fixture = Fixture()
        val metadata = fixture.session.obj("metadata")!!
        metadata.add("varroModel", model("low"))
        metadata.addProperty("varroAgent", "build")
        metadata.addProperty("varroPermissionMode", "full")
        fixture.selections.observe(fixture.session)
        assertEquals(JsonObject(), fixture.store.sessionSelectedModels)
        assertEquals(JsonObject(), fixture.store.sessionPlanAgents)
        assertEquals(JsonObject(), fixture.store.sessionPermissionModes)
        assertTrue(fixture.patches.isEmpty())

        fixture.selections.updateModel("session", model("high"), "/repo")
        fixture.selections.updateAgent("session", "plan", "/repo")
        fixture.selections.updateMode("session", Json.obj("mode" to "auto", "preconfigured" to true), "/repo")
        val saved = fixture.session.obj("metadata")!!
        assertEquals(model("low"), saved.obj("varroModel"))
        assertEquals("build", saved.str("varroAgent"))
        assertEquals("full", saved.str("varroPermissionMode"))
        assertEquals(model("high"), fixture.store.sessionSelectedModels.get("session"))
        assertEquals("plan", fixture.store.sessionPlanAgents.str("session"))
        assertEquals("auto", fixture.store.sessionPermissionModes.str("session"))
        assertEquals(1, saved.obj("varro").int("schemaVersion"))
    }

    @Test fun `reading and reselecting nested metadata without a version does not rewrite it`() {
        val fixture = Fixture()
        val metadata = Json.obj("varro" to Json.obj("model" to storedModel("high"),
            "agent" to "plan", "permissionMode" to "auto"))
        fixture.session.add("metadata", metadata.deepCopy())
        fixture.selections.observe(fixture.session)
        assertEquals(model("high"), fixture.store.sessionSelectedModels.get("session"))
        assertEquals("plan", fixture.store.sessionPlanAgents.str("session"))
        assertEquals("auto", fixture.store.sessionPermissionModes.str("session"))
        fixture.selections.updateModel("session", model("high"), "/repo")
        fixture.selections.updateAgent("session", "plan", "/repo")
        fixture.selections.updateMode("session", Json.obj("mode" to "auto", "preconfigured" to true), "/repo")
        assertTrue(fixture.patches.isEmpty())
        assertEquals(metadata, fixture.session.obj("metadata"))
    }

    private fun model(variant: String?) = Json.obj("providerID" to "openai", "modelID" to "test").apply {
        variant?.let { addProperty("variant", it) }
    }

    private fun storedModel(variant: String?) = Json.obj("provider" to "openai", "model" to "test").apply {
        variant?.let { addProperty("variant", it) }
    }
}
