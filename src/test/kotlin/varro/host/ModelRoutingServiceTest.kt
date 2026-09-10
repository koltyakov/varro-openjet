package varro.host

import com.google.gson.JsonObject
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.obj
import varro.protocol.str
import varro.settings.VarroSettings

class ModelRoutingServiceTest {
    @Test
    fun `every menu target returns its assigned chip and can be unset`() {
        for ((target, field) in listOf(
            "small_model" to "smallModel",
            "commit_message" to "commitMessageModel",
            "auto_approve" to "autoApproveModel",
            "agent" to "agentModels",
        )) {
            val fixture = Fixture()
            val result = fixture.service().update(request(target))
            val route = if (target == "agent") result.obj(field)?.obj("explore") else result.obj(field)
            assertEquals("openai", route.str("providerID"))
            assertEquals("gpt-6/astra", route.str("modelID"))
            assertEquals(result, fixture.service().read()) // A second project reads the same settings.
            assertEquals(1, fixture.changes)

            val cleared = fixture.service().update(request(target, unset = true))
            if (target == "agent") assertEquals(0, cleared.obj(field)?.size())
            else assertTrue(cleared.get(field).isJsonNull)
            assertEquals(cleared, fixture.service().read())
            assertEquals(2, fixture.changes)
        }
    }

    @Test
    fun `OpenCode patches contain config fields and preserve unrelated agent settings`() {
        val fixture = Fixture()
        fixture.config = Json.parse("""{"agent":{"explore":{"temperature":0.2,"prompt":"Keep me"},"general":{"model":"other/model"}},"small_model":"other/small"}""").asJsonObject
        fixture.service().update(request("agent"))
        assertEquals(Json.parse("""{"agent":{"explore":{"model":"openai/gpt-6/astra"}}}"""), fixture.patches.single())
        assertEquals("Keep me", fixture.config.obj("agent")?.obj("explore").str("prompt"))
        assertEquals("other/model", fixture.config.obj("agent")?.obj("general").str("model"))
        assertEquals("other/small", fixture.config.str("small_model"))
        fixture.service().update(request("small_model"))
        assertEquals(Json.obj("small_model" to "openai/gpt-6/astra"), fixture.patches.last())
    }

    @Test
    fun `IDE model roles persist through settings serialization without patching OpenCode`() {
        val fixture = Fixture()
        fixture.service().update(request("commit_message"))
        fixture.service().update(request("auto_approve"))
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(fixture.settings.state), VarroSettings::class.java)
        assertEquals("openai/gpt-6/astra", restored.commitMessageModel)
        assertEquals("openai/gpt-6/astra", restored.chatAutoApproveModel)
        assertTrue(fixture.patches.isEmpty())
    }

    @Test
    fun `invalid menu requests do not write or notify`() {
        val fixture = Fixture()
        for (body in listOf(JsonObject(), request("unknown"), request("agent").apply { remove("agentName") },
            request("small_model").apply { remove("modelID") })) {
            assertThrows(IllegalArgumentException::class.java) { fixture.service().update(body) }
        }
        assertTrue(fixture.patches.isEmpty())
        assertEquals(0, fixture.changes)
    }

    @Test
    fun `save failures propagate instead of returning unchanged routing as success`() {
        var notified = false
        val service = ModelRoutingService(VarroSettings(), { JsonObject() }, { error("Write failed") }, { notified = true })
        val failure = assertThrows(IllegalStateException::class.java) { service.update(request("small_model")) }
        assertEquals("Write failed", failure.message)
        assertFalse(notified)
    }

    private fun request(target: String, unset: Boolean = false) = Json.obj(
        "target" to target, "providerID" to "openai", "modelID" to "gpt-6/astra",
        "agentName" to "explore", "unset" to unset,
    )

    private class Fixture {
        val settings = VarroSettings()
        var config = JsonObject()
        val patches = mutableListOf<JsonObject>()
        var changes = 0

        fun service() = ModelRoutingService(settings, { config.deepCopy() }, { patch ->
            patches.add(patch.deepCopy())
            merge(config, patch)
            config.deepCopy()
        }, { changes++ })

        private fun merge(target: JsonObject, patch: JsonObject) {
            patch.entrySet().forEach { (key, value) ->
                val existing = target.get(key).asObjectOrNull()
                if (existing != null && value.isJsonObject) merge(existing, value.asJsonObject)
                else target.add(key, value.deepCopy())
            }
        }
    }
}
