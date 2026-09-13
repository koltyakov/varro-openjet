package varro.settings

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Explicit allowlist keeps newly added IDE settings local unless deliberately shared. */
internal object SharedCoreSettings {
    private val gson = Gson()
    private val names = setOf(
        "serverPort", "serverCommand", "serverAutoUpdate", "serverAutoStart",
        "chatDefaultPermissionMode", "chatAutoApproveModel", "commitMessageModel",
        "chatAutoRenameUntitledSessions", "chatEnableAskAgent", "chatAutoCompact", "chatAutoCompactionReservedTokens",
    )
    private val fields = VarroSettings::class.java.declaredFields.filter { it.name in names }.onEach { it.isAccessible = true }

    fun snapshot(settings: VarroSettings): JsonObject = JsonObject().apply {
        fields.forEach { add(it.name, gson.toJsonTree(it.get(settings))) }
    }

    fun apply(core: JsonObject, settings: VarroSettings) {
        // Validate all values before changing any in-memory settings.
        val changes = fields.filter { core.has(it.name) }.map { field ->
            val value = core.get(field.name)
            require(value.isJsonPrimitive) { "Invalid shared setting: ${field.name}" }
            val primitive = value.asJsonPrimitive
            require(when (field.type) {
                String::class.java -> primitive.isString
                Boolean::class.javaPrimitiveType -> primitive.isBoolean
                Int::class.javaPrimitiveType -> primitive.isNumber && runCatching { primitive.asBigDecimal.intValueExact() }.isSuccess
                else -> false
            }) { "Invalid shared setting: ${field.name}" }
            field to gson.fromJson(value, field.type)
        }
        changes.forEach { (field, value) -> if (field.get(settings) != value) field.set(settings, value) }
    }
}
