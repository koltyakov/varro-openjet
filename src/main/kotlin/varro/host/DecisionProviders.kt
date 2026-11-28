package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.arr
import varro.protocol.obj
import varro.protocol.str
import varro.settings.VarroSettings

/**
 * Port of `src/extension/decision-providers.ts`: owns the TypeSafe credential and
 * the single decision reviewer selection. The key lives in the IDE password safe;
 * `TYPESAFE_API_KEY` in the IDE environment is the fallback.
 */
internal class DecisionProviders(
    private val settings: VarroSettings,
    private val secrets: SecretStore = PasswordSafeSecretStore,
    private val environment: () -> Map<String, String> = { com.intellij.util.EnvironmentUtil.getEnvironmentMap() },
    private val ui: Ui,
    private val onChanged: () -> Unit = {},
    private val createClient: (String) -> JevClient = { apiKey -> JevClient({ apiKey }) },
    private val native: NativeDecisions? = null,
) {
    interface SecretStore {
        fun get(): String?
        fun set(value: String?)
    }

    interface Ui {
        /** Prompts for an API key; null when cancelled. */
        fun promptApiKey(): String?
        fun showError(message: String)
        fun showInfo(message: String)
    }

    fun apiKey(): String? = storedKey() ?: environmentKey()

    fun hasApiKey(): Boolean = apiKey() != null

    fun directModel(): String? = JevDecisions.directModelId(settings.decisionModel())

    fun status(): JsonObject {
        val source = when {
            storedKey() != null -> "secret"
            environmentKey() != null -> "environment"
            else -> null
        }
        val selected = directModel()
        return Json.obj("jev" to Json.obj(
            "connected" to (source != null),
            "credentialSource" to source,
            "model" to (selected ?: JevDecisions.DEFAULT_MODEL),
            "autoApprove" to (selected != null),
        )).apply {
            native?.status()?.let { add("native", it.apply { addProperty("visible", settings.debugShowNativeDecisionModels) }) }
        }
    }

    fun handle(body: JsonElement?): JsonObject {
        val request = body.asObjectOrNull()
        when (request.str("action")) {
            "native" -> {
                val model = request.str("model") ?: throw IllegalArgumentException("Invalid decision model")
                require(model.length <= 500) { "Invalid decision model" }
                if (model.isNotEmpty()) {
                    val status = native?.status()
                    require(JevDecisions.directModelId(model) == null && status.bool("supported") == true && status.arr("models")?.any {
                        val entry = it.asObjectOrNull()
                        "${entry.str("providerID")}/${entry.str("modelID")}" == model
                    } == true) { "Select an available OpenCode decision model" }
                }
                if (model.isNotEmpty() || directModel() == null) select(model)
            }
            "connect" -> connect()
            "disconnect" -> {
                secrets.set(null)
                if (directModel() != null) select("")
            }
            "update" -> when (request.bool("autoApprove")) {
                true -> if (directModel() == null) select(JevDecisions.DIRECT_ROUTE)
                false -> if (directModel() != null) select("")
                null -> Unit
            }
            else -> throw IllegalArgumentException("Unsupported decision provider request")
        }
        return status()
    }

    private fun select(route: String) {
        if (settings.decisionModel() == route) return
        settings.selectDecisionModel(route)
        onChanged()
    }

    /** Prompts for a TypeSafe API key, verifies it with a minimal request, and stores it. */
    fun connect(): Boolean {
        val apiKey = ui.promptApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        try {
            createClient(apiKey).evaluate(
                directModel() ?: JevDecisions.DEFAULT_MODEL,
                Json.toElement("Connection check from Varro."),
                Json.obj("ok" to Json.obj("type" to "noul", "instructions" to "Is this a connection check?")),
                10_000,
            )
        } catch (failure: Exception) {
            ui.showError("Could not connect TypeSafe Jev: ${failure.message}")
            return false
        }
        secrets.set(apiKey)
        if (directModel() == null) {
            ui.showInfo("TypeSafe Jev connected. Turn on Auto-approve decisions for it in the Varro Models view.")
        }
        return true
    }

    private fun storedKey() = secrets.get()?.trim()?.takeIf { it.isNotEmpty() }

    private fun environmentKey() = environment()[JevDecisions.API_KEY_ENV]?.trim()?.takeIf { it.isNotEmpty() }

    private object PasswordSafeSecretStore : SecretStore {
        private val attributes = CredentialAttributes(generateServiceName("Varro", "typesafe.apiKey"))
        override fun get(): String? = PasswordSafe.instance.getPassword(attributes)
        override fun set(value: String?) = PasswordSafe.instance.setPassword(attributes, value)
    }
}
