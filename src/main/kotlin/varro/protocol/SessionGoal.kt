package varro.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Optional native goal-plugin state, matching shared/session-goal.ts. */
internal object SessionGoal {
    fun parse(value: JsonElement?): JsonObject? {
        val goal = value.asObjectOrNull() ?: return null
        if (goal.str("id") == null || goal.str("objective") == null ||
            goal.str("status") !in setOf("active", "paused", "complete") ||
            goal.has("reason") && goal.str("reason") == null ||
            listOf("rounds", "maxRounds", "updatedAt").any { goal.num(it)?.isFinite() != true }) return null
        return Json.obj("id" to goal.get("id"), "objective" to goal.get("objective"), "status" to goal.get("status"),
            "rounds" to goal.get("rounds"), "maxRounds" to goal.get("maxRounds"), "updatedAt" to goal.get("updatedAt")).apply {
            goal.str("reason")?.let { addProperty("reason", it) }
        }
    }
}
