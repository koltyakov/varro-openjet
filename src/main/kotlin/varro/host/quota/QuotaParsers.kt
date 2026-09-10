package varro.host.quota

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

internal object QuotaParsers {
    fun codex(payload: JsonObject, now: Long): List<JsonObject> {
        val windows = linkedMapOf<String, JsonObject>()
        fun add(id: String, row: JsonObject?) {
            window(id, periodLabel(id), null, null, row.reset(now, "reset_at", "resetAt"), row.number("used_percent", "usedPercent"))
                ?.let { windows.putIfAbsent(id, it) }
        }
        fun rate(row: JsonObject?, spark: Boolean = false) {
            val limits = row.record("rate_limit", "rateLimit", "rate_limits", "rateLimits") ?: row
            val primary = limits.record("primary_window", "primaryWindow")
            val secondary = limits.record("secondary_window", "secondaryWindow")
            val weekly = secondary == null && ((!spark && payload.str("plan_type") == "free") ||
                (primary.number("limit_window_seconds", "limitWindowSeconds") ?: 0.0) >= 604800)
            val prefix = if (spark) "spark_" else ""
            add(prefix + if (weekly) "seven_day" else "five_hour", primary)
            add(prefix + "seven_day", secondary)
        }
        rate(payload)
        add("code_review", payload.obj("code_review_rate_limit").obj("primary_window"))
        fun visit(value: JsonElement, spark: Boolean, depth: Int) {
            if (depth > 5) return
            if (value.isJsonArray) { value.asJsonArray.forEach { visit(it, spark, depth + 1) }; return }
            val row = value.asObjectOrNull() ?: return
            val name = row.string("label", "name", "title", "model_id", "modelID", "id").orEmpty().lowercase()
            val isSpark = spark || name.contains("spark")
            if (isSpark) {
                rate(row, true)
                if (name.contains("week") || name.contains("seven_day")) add("spark_seven_day", row)
                else if (name.contains("hour") || name.contains("five") || name.contains("5")) add("spark_five_hour", row)
            }
            row.entrySet().forEach { (key, child) -> visit(child, isSpark || key.lowercase().contains("spark"), depth + 1) }
        }
        visit(payload, false, 0)
        val order = listOf("five_hour", "seven_day", "spark_five_hour", "spark_seven_day", "code_review")
        return windows.values.sortedBy { order.indexOf(it.str("id")) }
    }

    fun copilot(payload: JsonObject, now: Long): List<JsonObject> {
        val reset = payload.reset(now, "quota_reset_date_utc", "limited_user_reset_date")
        var snapshots = payload.obj("quota_snapshots")
        if (snapshots == null || snapshots.size() == 0) {
            snapshots = JsonObject()
            payload.obj("limited_user_quotas")?.entrySet()?.forEach { (id, _) ->
                val used = payload.obj("limited_user_quotas").number(id)
                val cap = payload.obj("monthly_quotas").number(id)
                if (used != null && cap != null && cap > 0) snapshots.add(id, Json.obj("remaining" to cap - used, "entitlement" to cap))
            }
        }
        return snapshots.entrySet().mapNotNull { (id, value) ->
            val row = value.asObjectOrNull()
            if (row.bool("unlimited") == true) return@mapNotNull null
            val cap = row.number("entitlement")
            val used = row.number("percent_remaining")?.let { 100 - it }
            if ((cap == null || cap <= 0) && used == null) return@mapNotNull null
            window(id, if (id == "premium_interactions") "Monthly Premium Requests" else "Monthly ${label(id)}",
                row.number("remaining"), cap, reset, used, if (id == "chat") "messages" else "requests")
        }.sortedBy { it.str("label") }
    }

    fun gemini(payload: JsonObject, now: Long): List<JsonObject> = payload.elements("buckets").mapNotNull { value ->
        val row = value.asObjectOrNull()
        val id = row.string("modelId", "modelID", "name") ?: return@mapNotNull null
        val remaining = row.number("remainingFraction", "remaining_fraction")?.coerceIn(0.0, 1.0) ?: return@mapNotNull null
        window(id, label(id), remaining * 100, 100.0, row.reset(now, "resetTime", "reset_at"), (1 - remaining) * 100)
    }

    fun zai(payload: JsonObject, now: Long): List<JsonObject> = payload.obj("data").elements("limits").mapNotNull { value ->
        val row = value.asObjectOrNull()
        val type = row.string("type")?.uppercase() ?: return@mapNotNull null
        val unit = row.number("unit")?.toInt()
        val count = row.number("number")?.toInt()
        val id = when {
            type in setOf("TOKENS_LIMIT", "CREDIT_LIMIT") && unit == 3 && count == 5 -> "five_hour"
            type in setOf("TOKENS_LIMIT", "CREDIT_LIMIT") && unit == 6 -> "weekly"
            type == "TIME_LIMIT" && row.elements("usageDetails").any {
                it.asObjectOrNull().string("modelCode", "model") in setOf("search-prime", "web-reader", "zread")
            } -> "mcp"
            else -> "${type.lowercase()}-${unit ?: 0}-${count ?: 0}"
        }
        val remaining = row.number("remaining")
        val limit = row.number("usage") ?: remaining?.let { left -> row.number("currentValue")?.plus(left) }
        window(id, if (id == "mcp") "MCP Quota" else periodLabel(id), remaining, limit,
            row.reset(now, "nextResetTime"), row.number("percentage"))
    }

    fun minimax(payload: JsonObject, now: Long): List<JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        payload.elements("model_remains", "modelRemains").forEach { value ->
            val row = value.asObjectOrNull()
            for ((prefix, camel, id) in listOf(Triple("interval", "Interval", "requests"), Triple("weekly", "Weekly", "requests-weekly"))) {
                val ms = if (prefix == "interval") row.number("remains_time", "remainsTime") else row.number("weekly_remains_time", "weeklyRemainsTime")
                val reset = if (ms != null && ms > 0) now + ms.toLong() else if (prefix == "interval") row.reset(now, "end_time", "endTime") else row.reset(now, "weekly_end_time", "weeklyEndTime")
                // MiniMax names the remaining count usage_count. It must not be subtracted twice.
                window(id, if (prefix == "interval") "Requests" else "Weekly requests",
                    row.number("current_${prefix}_usage_count", "current${camel}UsageCount"),
                    row.number("current_${prefix}_total_count", "current${camel}TotalCount"), reset, unit = "requests")
                    ?.let { result.putIfAbsent(id, it) }
            }
        }
        return result.values.toList()
    }

    fun kimi(payload: JsonObject, now: Long): List<JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        fun add(id: String, row: JsonObject?) {
            val cap = row.number("limit")?.takeIf { it > 0 } ?: return
            val used = row.number("used")
            val remaining = row.number("remaining") ?: used?.let { cap - it } ?: return
            window(id, periodLabel(id), remaining, cap, row.reset(now, "resetTime", "resetAt", "reset_time", "reset_at"),
                used?.let { it / cap * 100 }, "requests")?.let { result[id] = it }
        }
        add("seven_day", payload.obj("usage"))
        payload.elements("limits").forEach { value ->
            val row = value.asObjectOrNull()
            val period = row.obj("window")
            val seconds = period.number("duration")?.times(when (period.str("timeUnit")) {
                "TIME_UNIT_SECOND" -> 1; "TIME_UNIT_MINUTE" -> 60; "TIME_UNIT_HOUR" -> 3600; "TIME_UNIT_DAY" -> 86400; else -> 0
            })
            if (seconds == 18000.0) add("five_hour", row.obj("detail"))
            if (seconds == 604800.0) add("seven_day", row.obj("detail"))
        }
        return result.values.sortedBy { if (it.str("id") == "five_hour") 0 else 1 }
    }

    fun xai(payload: JsonObject, now: Long): List<JsonObject> {
        val config = payload.obj("config") ?: payload
        val result = mutableListOf<JsonObject>()
        val period = config.obj("currentPeriod")
        val type = period.string("type").orEmpty().uppercase()
        window("credits", when { type.contains("WEEK") -> "Weekly Credits"; type.contains("MONTH") -> "Monthly Credits"; type.contains("DAY") -> "Daily Credits"; else -> "Credits" },
            null, null, period.reset(now, "end") ?: config.reset(now, "billingPeriodEnd"), config.number("creditUsagePercent"), "credits")?.let(result::add)
        fun amount(row: JsonObject?, key: String) = row.obj(key).number("val") ?: row.number(key)
        val cap = amount(config, "onDemandCap")
        val used = amount(config, "onDemandUsed")
        if (cap != null && cap > 0 && used != null) window("on_demand", "On-demand Credits", cap - used, cap,
            config.reset(now, "billingPeriodEnd"), unit = "credits")?.let(result::add)
        if (result.isEmpty()) {
            val monthly = amount(payload, "monthlyLimit") ?: amount(config, "monthlyLimit")
            val monthlyUsed = amount(payload.obj("usage") ?: config.obj("usage"), "totalUsed") ?: amount(config, "used")
            if (monthly != null && monthly > 0 && monthlyUsed != null) window("monthly_credits", "Monthly Credits", monthly - monthlyUsed,
                monthly, payload.obj("billingCycle").reset(now, "billingPeriodEnd") ?: config.reset(now, "billingPeriodEnd"), unit = "credits")?.let(result::add)
        }
        return result
    }
}
