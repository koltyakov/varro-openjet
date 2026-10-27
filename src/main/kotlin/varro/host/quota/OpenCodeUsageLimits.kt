package varro.host.quota

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Validates server-owned Console/Go limits without accessing this host's credentials. */
internal object OpenCodeUsageLimits {
    fun project(value: JsonElement?, providerId: String, modelId: String?, checkedAt: Long): JsonObject {
        fun invalid(message: String): Nothing = error(message)
        fun number(record: JsonObject?, key: String, message: String, nonnegative: Boolean = false): Double =
            record.num(key)?.takeIf { it.isFinite() && (!nonnegative || it >= 0) } ?: invalid(message)
        val info = value.asObjectOrNull() ?: invalid("Invalid OpenCode usage limits response")
        val workspace = info.obj("workspace")
        if (workspace.str("id") == null || info.arr("budgets") == null) invalid("Invalid OpenCode usage limits response")
        val windows = mutableListOf<JsonObject>()
        val go = info.obj("go")
        if (providerId == "opencode-go" && info.has("go") && go == null) invalid("Invalid OpenCode Go allowance")
        if (go != null && providerId == "opencode-go") {
            for ((key, id, label) in listOf(Triple("rolling", "five_hour", "5 hour"), Triple("weekly", "weekly", "Weekly"), Triple("monthly", "monthly", "Monthly"))) {
                val meter = go.obj(key)
                val percent = number(meter, "percent", "Invalid OpenCode Go allowance", true)
                if (percent > 100) invalid("Invalid OpenCode Go allowance")
                val reset = number(meter, "resetsAt", "Invalid OpenCode Go allowance")
                val limited = meter.bool("limited") ?: invalid("Invalid OpenCode Go allowance")
                windows.add(Json.obj("id" to id, "label" to label, "unit" to "unknown", "limit" to 100,
                    "remaining" to if (limited) 0.0 else 100 - percent, "percent" to if (limited) 100.0 else percent, "resetAt" to reset))
            }
        }
        for (value in info.arr("budgets")!!) {
            val budget = value.asObjectOrNull()
            val message = "Invalid OpenCode Console budget"
            val id = budget.str("id") ?: invalid(message)
            val limit = number(budget, "limit", message, true)
            val total = number(budget, "spent", message, true)
            val reset = number(budget, "resetsAt", message)
            val models = budget.arr("models") ?: invalid(message)
            val coverage = budget.str("coverage")
            val per = budget.str("per")
            if (coverage !in setOf("all", "models") || per !in setOf("model", "set")) invalid(message)
            val model = models.mapNotNull { it.asObjectOrNull() }.firstOrNull { it.str("providerID") == providerId && it.str("modelID") == modelId }
            if (coverage == "all" && providerId == "opencode-go") continue
            if ((coverage != "all" || per == "model") && model == null) continue
            val modelSpent = if (model?.has("spent") == true) number(model, "spent", "Invalid OpenCode model budget spend", true) else 0.0
            val spent = if (per == "model") modelSpent else total
            windows.add(Json.obj("id" to "budget:$id", "label" to if (budget.bool("shared") == true) "Shared monthly budget" else "Monthly budget",
                "unit" to "usd", "remaining" to maxOf(0.0, limit - spent), "limit" to limit,
                "percent" to if (limit > 0) minOf(100.0, spent / limit * 100) else 100.0, "resetAt" to reset))
        }
        return Json.obj("providerID" to providerId, "modelID" to modelId, "checkedAt" to checkedAt, "source" to "opencode",
            "status" to if (windows.isEmpty()) "unsupported" else "available",
            "note" to if (windows.isEmpty()) "The connected Console workspace has no applicable allowance or budget."
                else workspace.str("name")?.let { "OpenCode Console workspace: $it" } ?: "OpenCode Console workspace limits").apply {
            if (windows.isNotEmpty()) add("windows", Json.array(windows))
            if (go != null && providerId == "opencode-go" && windows.isNotEmpty()) addProperty("planName", "Go")
        }
    }
}
