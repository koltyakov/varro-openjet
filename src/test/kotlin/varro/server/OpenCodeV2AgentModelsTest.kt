package varro.server

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class OpenCodeV2AgentModelsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val resolvedModel = Json.obj("providerID" to "native", "id" to "reviewer", "variant" to "high")

    private fun read(config: JsonObject, nativeModel: JsonObject? = resolvedModel): JsonObject {
        val resolved = Json.obj("id" to "review", "mode" to "subagent", "permissions" to emptyList<Any>())
        nativeModel?.let { resolved.add("model", it) }
        val adapter = OpenCodeV2Adapter({ method, path, body, options ->
            assertEquals("GET", method); assertNull(body); assertEquals("/repo", options.directory)
            OpenCodeResponse(when (path) {
                "/api/agent?location%5Bdirectory%5D=%2Frepo" -> Json.obj("data" to listOf(resolved))
                "/api/config?location%5Bdirectory%5D=%2Frepo" ->
                    Json.obj("data" to listOf(Json.obj("info" to Json.obj("agents" to Json.obj("review" to config)))))
                else -> error("Unexpected $method $path")
            })
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))
        return adapter.request("GET", "/agent", null, RequestOptions(directory = "/repo")).data!!.asJsonArray.single().asJsonObject
    }

    @Test fun `omitted and null configured models preserve the resolved agent model and variant`() {
        for (config in listOf(Json.obj(), Json.obj("model" to null))) {
            val model = read(config).obj("model")
            assertEquals("native", model.str("providerID"))
            assertEquals("reviewer", model.str("modelID"))
            assertEquals("high", model.str("variant"))
        }
    }

    @Test fun `configured models override native defaults with the composer model shape`() {
        for (model in listOf(Json.toElement("configured/builder#low"),
            Json.obj("providerID" to "configured", "id" to "builder", "variant" to "low"))) {
            val selected = read(Json.obj("model" to model)).obj("model")
            assertEquals("configured", selected.str("providerID"))
            assertEquals("builder", selected.str("modelID"))
            assertEquals("low", selected.str("variant"))
        }
    }

    @Test fun `configured models work without a resolved model and absent models stay absent`() {
        assertEquals("builder", read(Json.obj("model" to "configured/builder"), nativeModel = null).obj("model").str("modelID"))
        assertFalse(read(Json.obj(), nativeModel = null).hasNonNull("model"))
    }
}
