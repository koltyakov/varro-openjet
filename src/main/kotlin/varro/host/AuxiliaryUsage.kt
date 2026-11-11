package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Native usage without assistant messages has no trustworthy model, prompt count or duration. */
internal object AuxiliaryUsage {
    private val sources = setOf("generate", "title", "compaction", "unrecorded")
    fun validate(info: JsonObject): JsonObject {
        val source = info.str("source")
        require(source in sources) { "Unsupported auxiliary usage source" }
        require(source == "unrecorded" || info.obj("time").num("created")?.let { it.isFinite() && it >= 0 && it == it.toLong().toDouble() } == true) { "Malformed auxiliary usage timestamp" }
        val tokens = info.obj("tokens")
        require(listOf(tokens.num("input"), tokens.num("output"), tokens.num("reasoning"), tokens.obj("cache").num("read"), tokens.obj("cache").num("write")).all {
            it != null && it.isFinite() && it >= 0
        }) { "Malformed auxiliary token usage" }
        return info.deepCopy().apply {
            remove("parentID"); remove("model"); remove("variant")
            addProperty("providerID", "unknown"); addProperty("modelID", "unknown")
            obj("time")?.remove("completed")
        }
    }

    class Remainder(tokens: JsonObject, private val nativeCost: Double? = null) {
        private val remaining = UsageReport.Total().apply { add("", Json.obj("tokens" to tokens)) }
        private var cost = nativeCost ?: 0.0
        private var records = 0
        fun subtract(info: JsonObject) {
            val used = UsageReport.Total().apply { add("", info) }
            remaining.input -= used.input; remaining.output -= used.output; remaining.reasoning -= used.reasoning
            remaining.cacheRead -= used.cacheRead; remaining.cacheWrite -= used.cacheWrite
            cost -= info.num("cost")?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            records++
        }
        fun info(): JsonObject? {
            val tokens = Json.obj("input" to remaining.input.coerceAtLeast(0), "output" to remaining.output.coerceAtLeast(0),
                "reasoning" to remaining.reasoning.coerceAtLeast(0), "cache" to Json.obj("read" to remaining.cacheRead.coerceAtLeast(0), "write" to remaining.cacheWrite.coerceAtLeast(0)))
            val total = UsageReport.Total().apply { add("", Json.obj("tokens" to tokens)) }.total
            val residualCost = cost.takeIf { it > Math.ulp(1.0) * (nativeCost ?: 0.0) * maxOf(1, records) } ?: 0.0
            if (total <= 0 && residualCost <= 0) return null
            return validate(Json.obj("id" to "native-total-residual", "source" to "unrecorded", "tokens" to tokens, "time" to Json.obj())).apply {
                if (nativeCost != null) addProperty("cost", residualCost)
            }
        }
    }

    fun log(value: JsonElement?, sessionID: String): List<JsonObject> {
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Invalid auxiliary usage log" }
        val seen = mutableSetOf<String>()
        var synced = false
        val usage = mutableListOf<JsonObject>()
        for (frame in value.asString.replace("\r\n", "\n").split("\n\n")) {
            val text = frame.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (text.isEmpty()) continue
            val event = Json.parse(text).asObjectOrNull() ?: error("Invalid auxiliary log frame")
            require(!synced) { "Unexpected frame after auxiliary log sync" }
            if (event.str("type") == "log.synced") {
                require(event.str("aggregateID") == sessionID) { "Auxiliary usage log session mismatch" }
                synced = true
            } else if (event.str("type") == "session.usage.recorded") {
                val data = event.obj("data") ?: error("Invalid auxiliary usage record")
                val id = event.text("id") ?: error("Missing auxiliary usage ID")
                require(data.str("sessionID") == sessionID && event.obj("durable").str("aggregateID") == sessionID && data.str("source") != "unrecorded") { "Auxiliary usage session mismatch" }
                val info = validate(data.deepCopy().apply { addProperty("id", id); add("time", Json.obj("created" to event.get("created"))) })
                if (seen.add(id)) usage.add(info)
                require(seen.size <= 100_000) { "Auxiliary usage exceeds record limit" }
            }
        }
        require(synced) { "Incomplete auxiliary usage log" }
        return usage
    }
}
