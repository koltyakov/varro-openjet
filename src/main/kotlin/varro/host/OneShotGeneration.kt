package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.OpenCodeRequestException
import varro.server.OpenCodeResponse
import varro.server.RequestOptions

/** Null allows a helper-session fallback only when no generation was admitted. */
internal class OneShotGeneration(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
) {
    fun generate(apiVersion: Int, prompt: String, model: JsonObject?, options: RequestOptions): String? {
        if (apiVersion != 2) return null
        options.checkCancelled()
        val specification = try {
            request("GET", "/openapi.json", null, options.copy(unscoped = true, maxResponseBytes = 4 * 1024 * 1024)).data
        } catch (failure: OpenCodeRequestException) {
            if (failure.message?.startsWith("404 ") == true) return null
            throw failure
        }
        val paths = specification.asObjectOrNull().obj("paths")
        val generatePath = GENERATE_PATHS.firstOrNull { paths.obj(it).obj("post") != null } ?: return null
        options.checkCancelled()
        val response = try {
            request("POST", generatePath, Json.obj("prompt" to prompt, "model" to model?.let {
                Json.obj("providerID" to it.get("providerID"), "id" to it.get("modelID")).apply {
                    if (it.has("variant")) add("variant", it.get("variant"))
                }
            }), options).data
        } catch (failure: OpenCodeRequestException) {
            options.checkCancelled()
            val route = "${model.str("providerID")}/${model.str("modelID")}"
            if (model != null && failure.message in listOf("400 Model unavailable: $route", "404 Model not found: $route")) return null
            throw failure
        }
        options.checkCancelled()
        return response.asObjectOrNull().obj("data").str("text")
            ?: error("OpenCode returned an invalid one-shot generation response")
    }

    companion object {
        private val GENERATE_PATHS = listOf("/api/experimental/generate/text", "/api/experimental/generate")
    }
}
