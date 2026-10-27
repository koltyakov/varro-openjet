package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions

/** Explicit server-owned decisions. Never reads credentials or creates local helper sessions. */
class NativeDecisions(
    private val apiVersion: () -> Int,
    private val configuredModel: () -> String,
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
) {
    val model: JsonObject? get() {
        if (apiVersion() != 2) return null
        val route = configuredModel().trim()
        if (!route.contains('/') || route.substringBefore('/').isEmpty() || route.substringAfter('/').isEmpty() || route.any(Char::isWhitespace)) return null
        return Json.obj("providerID" to route.substringBefore('/'), "modelID" to route.substringAfter('/'))
    }

    private fun options(): RequestOptions {
        val deadline = System.nanoTime() + 5_000_000_000L
        return RequestOptions(unscoped = true, timeoutMs = 5000, isCancelled = { System.nanoTime() >= deadline })
    }

    private fun supported(options: RequestOptions): Boolean {
        val spec = request("GET", "/openapi.json", null, options.copy(maxResponseBytes = 4 * 1024 * 1024)).data
        options.checkCancelled()
        return spec.asObjectOrNull().obj("paths").obj(DECISION_PATH).obj("post") != null
    }

    fun status(): JsonObject {
        val selected = model
        fun unsupported() = Json.obj("supported" to false, "model" to selected, "models" to JsonArray())
        if (apiVersion() != 2) return unsupported()
        return try {
            val options = options()
            if (!supported(options)) return unsupported()
            val response = request("GET", "/api/model?types=decision", null, options.copy(maxResponseBytes = 1024 * 1024)).data
            options.checkCancelled()
            val catalog = response.asObjectOrNull().arr("data") ?: error("Invalid decision model catalog")
            val models = catalog.mapNotNull { value ->
                val item = value.asObjectOrNull()
                if (item.str("type") != "decision" || item.bool("enabled") == false ||
                    item.str("providerID") == null || item.str("id") == null || item.str("name") == null) null
                else Json.obj("providerID" to item.str("providerID"), "modelID" to item.str("id"), "name" to item.str("name"))
            }
            Json.obj("supported" to true, "model" to selected, "models" to models)
        } catch (failure: Exception) {
            unsupported().apply { addProperty("error", failure.message ?: "Native decisions unavailable") }
        }
    }

    fun judgePermission(model: JsonObject, permission: JsonObject, prior: JsonArray): JsonObject {
        check(apiVersion() == 2) { "Native decisions require OpenCode V2" }
        val options = options()
        check(supported(options)) { "Native decision generation is unavailable" }
        val questions = JevDecisions.QUESTIONS.entrySet().map { (name, value) ->
            val question = value.asJsonObject
            Json.obj("name" to name, "instructions" to question.get("instructions"),
                "type" to if (question.str("type") == "choice") "choice" else "predicate").apply {
                question.obj("criteria")?.let { criteria -> add("choices", Json.array(criteria.entrySet().map {
                    Json.obj("value" to it.key, "description" to it.value)
                })) }
            }
        }
        val response = request("POST", DECISION_PATH, Json.obj(
            "model" to Json.obj("providerID" to model.str("providerID"), "id" to model.str("modelID")),
            "input" to listOf(Json.obj("type" to "text", "text" to Json.stringify(Json.obj("permission" to permission, "priorUserDecisions" to prior)))),
            "questions" to questions,
        ), options.copy(maxResponseBytes = 64 * 1024)).data
        options.checkCancelled()
        return JevDecisions.gate(parseAnswers(response), prior)
    }

    companion object {
        private const val DECISION_PATH = "/api/experimental/generate/decisions"

        internal fun parseAnswers(response: JsonElement?): JsonObject {
            fun invalid(): Nothing = error("Invalid native permission decisions")
            fun probability(value: JsonObject?, key: String): Double = value.num(key)?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: invalid()
            val answers = response.asObjectOrNull().obj("data").arr("answers") ?: invalid()
            if (answers.size() != 3) invalid()
            val indexed = answers.associate { it.asObjectOrNull().str("name") to it.asObjectOrNull() }
            if (indexed.size != 3) invalid()
            val choice = indexed["decision"] ?: invalid()
            val destructive = indexed["destructive"] ?: invalid()
            val manipulation = indexed["manipulation"] ?: invalid()
            val allowed = setOf("allow", "ask", "reject")
            if (choice.str("type") != "choice" || choice.str("choice") !in allowed ||
                destructive.str("type") != "predicate" || manipulation.str("type") != "predicate") invalid()
            val values = choice.arr("probabilities") ?: invalid()
            val probabilities = JsonObject()
            if (values.size() != 3) invalid()
            values.forEach { value ->
                val entry = value.asObjectOrNull()
                val name = entry.str("value") ?: invalid()
                if (name !in allowed || probabilities.has(name)) invalid()
                probabilities.addProperty(name, probability(entry, "probability"))
            }
            return Json.obj(
                "decision" to Json.obj("type" to "choice", "choice" to choice.str("choice"),
                    "confidence" to probability(choice, "confidence"), "probabilities" to probabilities),
                "destructive" to Json.obj("type" to "noul", "noul" to probability(destructive, "probability")),
                "manipulation" to Json.obj("type" to "noul", "noul" to probability(manipulation, "probability")),
            )
        }
    }
}
