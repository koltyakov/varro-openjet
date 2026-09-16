package varro.host

import com.google.gson.JsonObject
import com.intellij.util.io.HttpRequests
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.obj

/** Optional models.dev API rates, cached for one hour like upstream. */
internal class ModelPricingCatalog(
    private val load: () -> String = {
        HttpRequests.request("https://models.dev/api.json")
            .accept("application/json")
            .connectTimeout(8_000).readTimeout(8_000)
            .connect { it.readString() }
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var cached: Map<String, JsonObject>? = null
    private var expiresAt = 0L

    @Synchronized
    fun get(providerID: String, modelID: String): JsonObject? {
        if (cached == null || now() >= expiresAt) {
            cached = parse(load())
            expiresAt = now() + 60 * 60_000L
        }
        return cached?.get("${providerID.lowercase(java.util.Locale.ROOT)}/$modelID")?.deepCopy()
    }

    private fun parse(source: String): Map<String, JsonObject> {
        val catalog = Json.parseOrNull(source).asObjectOrNull()
            ?: throw IllegalStateException("Model pricing catalog is not an object")
        val prices = mutableMapOf<String, JsonObject>()
        for ((providerID, provider) in catalog.entrySet()) {
            val models = provider.asObjectOrNull().obj("models") ?: continue
            for ((modelID, model) in models.entrySet()) {
                val cost = model.asObjectOrNull().obj("cost") ?: continue
                val pricing = JsonObject()
                for (key in listOf("input", "output", "cache_read", "cache_write")) {
                    val value = cost.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?.asDouble ?: continue
                    if (value.isFinite() && value >= 0) pricing.addProperty(key, value)
                }
                if (pricing.entrySet().any { it.value.asDouble > 0 }) {
                    prices["${providerID.lowercase(java.util.Locale.ROOT)}/$modelID"] = pricing
                }
            }
        }
        return prices
    }
}
