package varro.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * JSON helpers for the webview protocol.
 *
 * Varro's transport is deliberately pass-through: the host forwards OpenCode
 * payloads it does not model and only inspects the handful of fields it routes
 * on. A mutable tree therefore fits better than generated data classes, and it
 * keeps this port's behaviour aligned with the TypeScript original, which
 * validates shallowly through `asRecord`/`isString` helpers.
 */
object Json {
    val gson: Gson = GsonBuilder()
        // `serializeNulls` is off on purpose: upstream distinguishes an absent
        // optional field from an explicit `null` in several payloads (notably
        // `session-plan-state` and `session-model/update`).
        .disableHtmlEscaping()
        .create()

    fun parse(text: String): JsonElement = JsonParser.parseString(text)

    fun parseOrNull(text: String?): JsonElement? =
        if (text.isNullOrEmpty()) null else runCatching { parse(text) }.getOrNull()

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject().apply { pairs.forEach { (key, value) -> add(key, toElement(value)) } }

    fun array(values: Iterable<Any?>): JsonArray =
        JsonArray().apply { values.forEach { add(toElement(it)) } }

    fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject().apply {
            value.forEach { (key, entry) -> add(key.toString(), toElement(entry)) }
        }
        is Iterable<*> -> array(value)
        is Array<*> -> array(value.asIterable())
        else -> gson.toJsonTree(value)
    }

    fun stringify(value: Any?): String = gson.toJson(toElement(value))

    /** Context snapshots need explicit nulls to pass validation and clear stale selections. */
    fun stringifyMessage(message: JsonElement): String = when (message.asObjectOrNull().str("type")) {
        "api/response", "provider-limit/updated", "context/update" -> message.toString()
        else -> stringify(message)
    }

    /**
     * Builds a protocol envelope. Every extension-to-webview message is
     * `{ type, payload? }`, and upstream's `parseExtensionMessage` rejects any
     * envelope whose `type` it does not know, so a malformed one is dropped
     * silently rather than surfacing as an error.
     */
    fun message(type: String, payload: Any? = Unit): JsonObject = JsonObject().apply {
        addProperty("type", type)
        if (payload != Unit) add("payload", toElement(payload))
    }
}

// --- Shallow accessors, mirroring `src/shared/type-utils.ts` -----------------

fun JsonElement?.asObjectOrNull(): JsonObject? =
    if (this != null && isJsonObject) asJsonObject else null

fun JsonElement?.asArrayOrNull(): JsonArray? =
    if (this != null && isJsonArray) asJsonArray else null

fun JsonObject?.obj(name: String): JsonObject? = this?.get(name).asObjectOrNull()

fun JsonObject?.arr(name: String): JsonArray? = this?.get(name).asArrayOrNull()

fun JsonObject?.str(name: String): String? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
}

/** Non-blank string, matching upstream's common `isString(x) && x.trim()` guard. */
fun JsonObject?.text(name: String): String? = str(name)?.takeIf { it.isNotBlank() }

fun JsonObject?.int(name: String): Int? = num(name)?.takeIf { it.isFinite() }?.toInt()

fun JsonObject?.long(name: String): Long? = num(name)?.takeIf { it.isFinite() }?.toLong()

fun JsonObject?.num(name: String): Double? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asDouble else null
}

fun JsonObject?.bool(name: String): Boolean? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else null
}

fun JsonObject?.hasNonNull(name: String): Boolean {
    val element = this?.get(name) ?: return false
    return !element.isJsonNull
}

fun JsonArray.strings(): List<String> =
    mapNotNull { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null }
