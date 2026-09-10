package varro.host.quota

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZoneId

internal fun JsonObject?.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    val value = this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return@firstNotNullOfOrNull null
    val normalized = if (Regex("[+-]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?").matches(value)) value.replace(",", "") else value
    normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun JsonObject?.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { text(it) }
internal fun JsonObject?.record(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { obj(it) }
internal fun JsonObject?.elements(vararg keys: String): List<JsonElement> = keys.firstNotNullOfOrNull { arr(it) }?.toList().orEmpty()

internal fun resetAt(value: JsonElement?, now: Long): Long? {
    if (value == null || !value.isJsonPrimitive) return null
    val text = value.asString.trim()
    text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let {
        return when {
            it > 1_000_000_000_000 -> it.toLong()
            it > 1_000_000_000 -> (it * 1000).toLong()
            else -> now + (it * 1000).toLong()
        }
    }
    val normalized = text.lowercase().replace(Regex("\\s+"), "")
    val pieces = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)").findAll(normalized).toList()
    if (pieces.isNotEmpty() && pieces.joinToString("") { it.value } == normalized) {
        return now + pieces.sumOf { match -> match.groupValues[1].toDouble() * when (match.groupValues[2]) {
            "ms" -> 1; "s" -> 1000; "m" -> 60_000; "h" -> 3_600_000; else -> 86_400_000
        } }.toLong()
    }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}

internal fun JsonObject?.reset(now: Long, vararg names: String): Long? =
    names.firstNotNullOfOrNull { resetAt(this?.get(it), now) }

internal fun label(value: String): String = value.replace(Regex("[_-]+"), " ")
    .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

internal fun window(
    id: String, label: String, remaining: Double?, limit: Double?, reset: Long?,
    percent: Double? = null, unit: String = "unknown",
): JsonObject? {
    val used = percent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    val left = remaining?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: used?.let { 100 - it } ?: return null
    val cap = limit?.takeIf { it.isFinite() && it > 0 } ?: if (remaining == null && used != null) 100.0 else null
    return Json.obj("id" to id, "label" to label, "unit" to unit,
        "remaining" to left, "limit" to cap, "resetAt" to reset).apply {
        (used ?: cap?.let { ((1 - left / it) * 100).coerceIn(0.0, 100.0) })?.let { addProperty("percent", it) }
    }
}

internal fun directWindows(record: JsonObject?, now: Long): List<JsonObject> {
    if (record == null) return emptyList()
    val result = linkedMapOf<String, JsonObject>()
    fun add(id: String, row: JsonObject?) {
        if (row == null) return
        val limit = row.number("limit", "total", "max", "capacity")
        val remaining = row.number("remaining", "remainingCount", "available", "left")
            ?: limit?.let { total -> row.number("used", "usage", "consumed")?.let { total - it } }
        val unit = row.string("unit")?.takeIf { it in setOf("requests", "tokens", "messages", "credits", "usd", "unknown") }
            ?: id.takeIf { it in setOf("requests", "tokens", "messages", "credits", "usd") } ?: "unknown"
        window(id, row.string("label", "name") ?: label(id), remaining, limit,
            row.reset(now, "resetAt", "reset_at", "resetsAt", "reset", "resetTime"), row.number("percent", "percentage"), unit)
            ?.let { result.putIfAbsent(id, it) }
    }
    fun container(value: JsonObject) {
        add(value.string("id") ?: "limit", value)
        listOf("requests", "tokens", "messages", "credits", "usd").forEach { add(it, value.obj(it)) }
    }
    container(record)
    listOf("quota", "usage", "rateLimit", "rateLimits", "limits", "billing").forEach { key ->
        record.obj(key)?.let(::container)
        record.arr(key)?.forEach { item -> item.asObjectOrNull()?.let { add(it.string("id", "name", "type") ?: key, it) } }
    }
    return result.values.toList()
}
