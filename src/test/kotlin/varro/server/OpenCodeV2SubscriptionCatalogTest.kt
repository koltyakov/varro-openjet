package varro.server

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class OpenCodeV2SubscriptionCatalogTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        var providers = listOf(Json.obj("id" to "openai", "settings" to Json.obj("baseURL" to "https://chatgpt.com/backend-api/codex")))
        var models = emptyList<JsonObject>()
        var config = Json.obj()
        val adapter = OpenCodeV2Adapter({ method, path, body, options ->
            assertEquals("GET", method); assertNull(body); assertTrue(options.unscoped)
            OpenCodeResponse(when (path) {
                "/api/provider" -> Json.obj("data" to providers)
                "/api/model" -> Json.obj("data" to models)
                "/api/integration" -> Json.obj("data" to emptyList<Any>())
                "/api/config" -> Json.obj("data" to listOf(Json.obj("info" to config)))
                else -> error("Unexpected request $path")
            })
        }, OpenCodeV2SessionState(temporary.newFolder().toPath()))

        fun catalog(route: String) = adapter.request("GET", route, null, RequestOptions()).data.asObjectOrNull()!!
        fun entries(catalog: JsonObject) = (catalog.arr("all") ?: catalog.arr("providers"))!!.map { it.asJsonObject }
    }

    @Test fun `native and legacy catalogs omit subscription Flex and Cyber before selecting defaults without mutating input`() {
        val blocked = setOf("flex", "renamed-tier", "gpt-6-cyber", "gpt-5.6-cyber", "gpt-6-cyber-fast",
            "security-alias", "gpt-5.5-cyber-preview")
        val definitions = listOf(
            Json.obj("id" to "flex", "settings" to Json.obj("serviceTier" to "flex")),
            Json.obj("id" to "renamed-tier", "body" to Json.obj("service_tier" to "flex")),
            Json.obj("id" to "gpt-6-cyber", "modelID" to "gpt-6-cyber"),
            Json.obj("id" to "gpt-5.6-cyber", "modelID" to "gpt-5.6-cyber"),
            Json.obj("id" to "gpt-6-cyber-fast", "modelID" to "gpt-6-cyber", "settings" to Json.obj("serviceTier" to "priority")),
            Json.obj("id" to "security-alias", "modelID" to "gpt-6-cyber"),
            Json.obj("id" to "gpt-5.5-cyber-preview", "modelID" to "gpt-5.5-cyber-preview"),
            Json.obj("id" to "standard"),
            Json.obj("id" to "fast", "settings" to Json.obj("serviceTier" to "priority")),
            Json.obj("id" to "flexible-name", "name" to "Flex", "settings" to Json.obj("serviceTier" to "priority")),
            Json.obj("id" to "cyber-name", "name" to "Cyber", "modelID" to "gpt-6.1-sol"),
            Json.obj("id" to "gpt-6-cyber-alias", "modelID" to "gpt-6.1-sol"),
            Json.obj("id" to "api-override", "settings" to Json.obj("serviceTier" to "flex", "baseURL" to "https://api.openai.com/v1")),
            Json.obj("id" to "body-override", "settings" to Json.obj("serviceTier" to "flex"), "body" to Json.obj("service_tier" to "default")),
        )
        for (version in listOf(null, "2.0.5", "2.2.0", "2.3.0")) {
            val f = Fixture()
            f.providers = listOf(
                Json.obj("id" to "openai", "settings" to Json.obj("baseURL" to "https://chatgpt.com/backend-api/codex")),
                Json.obj("id" to "subscription-alias", "settings" to Json.obj("baseURL" to "https://chatgpt.com/backend-api/codex/")),
                Json.obj("id" to "api", "settings" to Json.obj("baseURL" to "https://api.openai.com/v1")),
            )
            f.models = f.providers.flatMap { provider -> definitions.map { definition ->
                Json.obj("modelID" to "gpt-6.1-sol", "providerID" to provider.str("id"), "enabled" to true).apply {
                    definition.entrySet().forEach { add(it.key, it.value.deepCopy()) }
                }
            } }
            f.config = Json.obj("providers" to Json.obj("openai" to Json.obj("models" to Json.obj(
                "configured-flex" to Json.obj("settings" to Json.obj("serviceTier" to "flex")),
                "configured-cyber" to Json.obj("modelID" to "gpt-6-cyber"),
            ))))
            f.adapter.setServerVersion(version)
            val originalModels = Json.array(f.models).deepCopy()
            val originalProviders = Json.array(f.providers).deepCopy()
            val originalConfig = f.config.deepCopy()
            for (route in listOf("/provider", "/config/providers")) {
                val catalog = f.catalog(route)
                f.entries(catalog).forEach { provider ->
                    val id = provider.str("id")!!
                    val expected = definitions.map { it.str("id")!! }.filter {
                        id == "api" || it !in blocked && !(id == "openai" && it == "api-override")
                    }
                    assertEquals("$version $route $id", expected, provider.obj("models")!!.keySet().toList())
                }
                assertEquals(Json.obj("openai" to "standard", "subscription-alias" to "standard", "api" to "flex"), catalog.obj("default"))
            }
            assertEquals(originalModels, Json.array(f.models))
            assertEquals(originalProviders, Json.array(f.providers))
            assertEquals(originalConfig, f.config)
        }
    }

    @Test fun `endpoint changes restore filtered models and empty catalogs have no unsupported default`() {
        val f = Fixture()
        f.adapter.setServerVersion("2.3.0")
        f.models = listOf(
            Json.obj("id" to "flex", "providerID" to "openai", "settings" to Json.obj("serviceTier" to "flex")),
            Json.obj("id" to "gpt-6-cyber", "providerID" to "openai"),
        )
        for (endpoint in listOf("https://chatgpt.com/backend-api/codex", "https://api.openai.com/v1", "https://chat.openai.com/backend-api/codex/")) {
            f.providers.single().add("settings", Json.obj("baseURL" to endpoint))
            for (route in listOf("/provider", "/config/providers")) {
                val catalog = f.catalog(route)
                val filtered = !endpoint.contains("api.openai.com")
                assertEquals(if (filtered) emptySet<String>() else setOf("flex", "gpt-6-cyber"),
                    f.entries(catalog).single().obj("models")!!.keySet())
                assertEquals(if (filtered) "" else "flex", catalog.obj("default").str("openai"))
            }
        }
    }

    @Test fun `subscription endpoint matching checks exact hosts and path boundaries`() {
        val endpoints = mapOf(
            "https://chatgpt.com/backend-api/codex" to true,
            "https://CHATGPT.COM/backend-api/codex/?fixture=1" to true,
            "https://chat.openai.com/backend-api/codex/responses" to true,
            "https://api.openai.com/v1" to false,
            "https://chatgpt.com.example.invalid/backend-api/codex" to false,
            "https://example.invalid/backend-api/codex" to false,
            "https://chatgpt.com/backend-api/codex-other" to false,
            "https://chatgpt.com/backend-api/codex%2Fresponses" to false,
            "https://chatgpt.com/other/backend-api/codex" to false,
            "https://chatgpt.com" to false,
            "/backend-api/codex" to false,
            "not a URL" to false,
            "" to false,
        )
        endpoints.forEach { (endpoint, blocked) ->
            val f = Fixture()
            f.providers.single().add("settings", Json.obj("baseURL" to endpoint))
            f.models = listOf(Json.obj("id" to "flex", "providerID" to "openai", "settings" to Json.obj("serviceTier" to "flex")))
            assertEquals(endpoint, !blocked, f.entries(f.catalog("/config/providers")).single().obj("models")!!.has("flex"))
        }
    }

    @Test fun `model body provider body and settings use nullish override precedence`() {
        val f = Fixture()
        f.providers.single().apply {
            obj("settings")!!.addProperty("serviceTier", "flex")
            add("body", Json.obj("service_tier" to "default"))
        }
        f.models = listOf(
            Json.obj("id" to "provider-body-wins", "providerID" to "openai", "settings" to Json.obj("serviceTier" to "flex")),
            Json.obj("id" to "model-body-wins", "providerID" to "openai", "body" to Json.obj("service_tier" to "flex")),
            Json.obj("id" to "null-body-falls-back", "providerID" to "openai", "body" to Json.obj("service_tier" to null)),
            Json.obj("id" to "false-body-is-explicit", "providerID" to "openai", "body" to Json.obj("service_tier" to false)),
        )
        assertEquals(setOf("provider-body-wins", "null-body-falls-back", "false-body-is-explicit"),
            f.entries(f.catalog("/provider")).single().obj("models")!!.keySet())
        f.providers.single().remove("body")
        assertEquals(setOf("false-body-is-explicit"), f.entries(f.catalog("/provider")).single().obj("models")!!.keySet())
        f.providers.single().obj("settings")!!.remove("serviceTier")
        f.models = listOf(Json.obj("id" to "legacy-options", "providerID" to "openai"))
        f.config = Json.obj("providers" to Json.obj("openai" to Json.obj("models" to Json.obj(
            "legacy-options" to Json.obj("options" to Json.obj("serviceTier" to "flex")),
        ))))
        assertTrue(f.entries(f.catalog("/provider")).single().obj("models")!!.isEmpty)
    }

    @Test fun `only the OpenAI subscription hook redirects API-origin model overrides`() {
        for (providerID in listOf("openai", "subscription-alias")) {
            val f = Fixture()
            f.providers.single().addProperty("id", providerID)
            f.models = listOf(
                Json.obj("id" to "default-api-port", "providerID" to providerID, "settings" to Json.obj("serviceTier" to "flex", "baseURL" to "https://api.openai.com:443/v1")),
                Json.obj("id" to "different-port", "providerID" to providerID, "settings" to Json.obj("serviceTier" to "flex", "baseURL" to "https://api.openai.com:444/v1")),
                Json.obj("id" to "other-api", "providerID" to providerID, "settings" to Json.obj("serviceTier" to "flex", "baseURL" to "https://example.invalid/v1")),
                Json.obj("id" to "invalid-model-url", "providerID" to providerID, "settings" to Json.obj("serviceTier" to "flex", "baseURL" to false)),
            )
            val models = f.entries(f.catalog("/provider")).single().obj("models")!!
            assertEquals(providerID != "openai", models.has("default-api-port"))
            assertTrue(models.has("different-port")); assertTrue(models.has("other-api")); assertTrue(models.has("invalid-model-url"))
        }
    }
}
