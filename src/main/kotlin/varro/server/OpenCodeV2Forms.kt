package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Preserve native field identity. Unknown fields stay visible but cannot be answered. */
internal object OpenCodeV2Forms {
    private val base = setOf("key", "type", "title", "description", "required", "hidden", "when")
    private val properties = mapOf(
        "string" to base + setOf("format", "minLength", "maxLength", "pattern", "placeholder", "default", "options", "custom"),
        "number" to base + setOf("minimum", "maximum", "default"),
        "integer" to base + setOf("minimum", "maximum", "default"),
        "boolean" to base + "default",
        "multiselect" to base + setOf("options", "minItems", "maxItems", "custom", "default"),
        "external" to setOf("key", "type", "title", "description", "url"),
    )

    fun parse(value: JsonObject): JsonObject {
        require(value.str("id") != null && value.str("sessionID") != null && value.str("title") != null &&
            value.arr("fields")?.isEmpty == false) { "Invalid OpenCode native form" }
        val keys = mutableSetOf<String>()
        return Json.obj("id" to value.get("id"), "sessionID" to value.get("sessionID"), "title" to value.get("title"),
            "fields" to value.arr("fields")!!.mapIndexed { index, entry ->
                val field = entry.asObjectOrNull() ?: Json.obj()
                val key = field.str("key") ?: "unsupported-$index"
                if (keys.add(key) && supported(field)) field.deepCopy() else Json.obj(
                    "type" to "unsupported", "key" to key, "nativeType" to (field.str("nativeType") ?: field.str("type") ?: "unknown"),
                    "raw" to (field.obj("raw") ?: field),
                ).apply {
                    for (name in listOf("title", "description")) field.str(name)?.let { addProperty(name, it) }
                }
            }).apply { value.obj("metadata")?.let { add("metadata", it.deepCopy()) } }
    }

    fun answer(input: JsonObject, form: JsonObject): JsonObject {
        require(input.str("kind") == "native-form" && input.str("sessionID") == form.str("sessionID") &&
            input.keySet().all { it in setOf("kind", "sessionID", "answer") } &&
            input.obj("answer")?.entrySet()?.all { validValue(it.value) } == true) {
            "Native forms require keyed, typed answers. Reload the Varro view."
        }
        require(parse(form).arr("fields")!!.none { it.asObjectOrNull().str("type") == "unsupported" }) {
            "This native form contains unsupported fields. Answer in another OpenCode client or skip it."
        }
        // Validation of active fields and constraints belongs to the native reply endpoint.
        return input.obj("answer")!!.deepCopy()
    }

    private fun string(value: JsonElement?) = value?.isJsonPrimitive == true && value.asJsonPrimitive.isString
    private fun number(value: JsonElement?) = value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber && value.asDouble.isFinite()
    private fun boolean(value: JsonElement?) = value?.isJsonPrimitive == true && value.asJsonPrimitive.isBoolean
    private fun strings(value: JsonElement?) = value?.isJsonArray == true && value.asJsonArray.all(::string)
    private fun validValue(value: JsonElement) = string(value) || number(value) || boolean(value) || strings(value)
    private fun supported(field: JsonObject): Boolean {
        if (field.str("key") == null) return false
        val type = field.str("type") ?: return false
        val allowed = properties[type] ?: return false
        if (field.keySet().any { it !in allowed }) return false
        for (key in listOf("title", "description")) if (field.has(key) && !string(field.get(key))) return false
        if (type == "external") return string(field.get("url"))
        for (key in listOf("required", "hidden", "custom")) if (field.has(key) && !boolean(field.get(key))) return false
        if (field.has("when") && field.arr("when")?.all { value ->
            val condition = value.asObjectOrNull()
            condition.str("key") != null && condition.str("op") in setOf("eq", "neq") &&
                (string(condition?.get("value")) || number(condition?.get("value")) || boolean(condition?.get("value")))
        } != true) return false
        if (field.has("options") && field.arr("options")?.all { value ->
            val option = value.asObjectOrNull()
            option.str("value") != null && option.str("label") != null && (option?.has("description") != true || string(option.get("description")))
        } != true) return false
        for (key in listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems"))
            if (field.has(key) && !number(field.get(key))) return false
        for (key in listOf("placeholder", "pattern")) if (field.has(key) && !string(field.get(key))) return false
        if (field.has("format") && field.str("format") !in setOf("email", "uri", "date", "date-time")) return false
        if (type == "multiselect" && field.arr("options") == null) return false
        if (!field.has("default")) return true
        return when (type) {
            "string" -> string(field.get("default"))
            "number", "integer" -> number(field.get("default"))
            "boolean" -> boolean(field.get("default"))
            "multiselect" -> strings(field.get("default"))
            else -> false
        }
    }
}
