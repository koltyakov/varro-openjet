package varro.host.quota

import com.google.gson.JsonObject
import varro.protocol.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded decoding of the two read-only Grok billing gRPC-web responses. */
internal class GrokQuotaRpc(private val http: QuotaHttp, private val token: String) {
    private data class Field(val id: Int, val number: Double? = null, val bytes: ByteArray? = null, val metric: Boolean = false)

    fun credits(now: Long): JsonObject? {
        val payloads = request("GetGrokCreditsConfig")
        val percents = mutableListOf<Triple<Int, Int, Double>>()
        val resets = mutableListOf<Pair<String, Long>>()
        fun scan(bytes: ByteArray, path: List<Int>) {
            if (path.size > 8) return
            val fields = runCatching { fields(bytes) }.getOrNull() ?: return
            fields.forEach { field ->
                val next = path + field.id
                field.number?.let { number ->
                    if (field.metric && number in 0.0..100.0) percents.add(Triple(if (field.id == 1) 0 else 1, next.size, number))
                    if (number in 1_700_000_000.0..2_100_000_000.0 && number * 1000 > now) resets.add(next.joinToString(".") to (number * 1000).toLong())
                }
                field.bytes?.let { scan(it, next) }
            }
        }
        payloads.forEach { scan(it, emptyList()) }
        val reset = resets.firstOrNull { it.first == "1.5.1" }?.second ?: resets.minOfOrNull { it.second } ?: return null
        val percent = percents.sortedWith(compareBy({ it.first }, { it.second })).firstOrNull()?.third ?: 0.0
        return window("credits", if (reset - now <= 8 * 86_400_000L) "Weekly Credits" else "Credits", null, null, reset, percent, "credits")
    }

    fun resets(now: Long): JsonObject? {
        val credits = request("GetRemainingResets").flatMap { payload -> fields(payload).mapNotNull { entry ->
            if (entry.id != 1 || entry.bytes == null) return@mapNotNull null
            val token = fields(entry.bytes)
            if (token.none { it.id == 1 && it.bytes?.isNotEmpty() == true }) return@mapNotNull null
            val timestamp = token.firstOrNull { it.id == 3 }?.bytes?.let(::fields) ?: return@mapNotNull null
            val seconds = timestamp.firstOrNull { it.id == 1 }?.number ?: return@mapNotNull null
            val nanos = timestamp.firstOrNull { it.id == 2 }?.number ?: 0.0
            if (nanos !in 0.0..<1_000_000_000.0) return@mapNotNull null
            val expires = (seconds * 1000 + nanos / 1_000_000).toLong()
            if (expires <= now) return@mapNotNull null
            Json.obj("title" to "Weekly quota reset", "expiresAt" to expires)
        } }.sortedBy { it.get("expiresAt").asLong }
        return if (credits.isEmpty()) null else Json.obj("availableCount" to credits.size, "credits" to Json.array(credits))
    }

    private fun request(method: String): List<ByteArray> {
        val response = http.send(QuotaRequest("https://grok.com/grok_api_v2.GrokBuildBilling/$method", mapOf(
            "Authorization" to "Bearer $token", "Origin" to "https://grok.com", "Referer" to "https://grok.com/?_s=usage",
            "x-grpc-web" to "1", "x-user-agent" to "connect-es/2.1.1",
        ), ByteArray(5), "application/grpc-web+proto"))
        if (response.code !in 200..299) throw QuotaFailure("SuperGrok quota RPC returned HTTP ${response.code}", response.code)
        return frames(response.bytes, requireTrailers = method == "GetRemainingResets")
    }

    companion object {
        internal fun frames(bytes: ByteArray, requireTrailers: Boolean = false): List<ByteArray> {
            val buffer = ByteBuffer.wrap(bytes)
            val payloads = mutableListOf<ByteArray>()
            var successfulTrailer = false
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 5) throw QuotaFailure("Truncated Grok quota frame")
                val flags = buffer.get().toInt() and 255
                val length = buffer.int
                if (length < 0 || length > buffer.remaining()) throw QuotaFailure("Invalid Grok quota frame length")
                val payload = ByteArray(length).also(buffer::get)
                if (flags == 128) {
                    val status = Regex("(?:^|\\r?\\n)grpc-status:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(payload.toString(Charsets.UTF_8))?.groupValues?.get(1)?.toIntOrNull()
                    if (status != null && status != 0) throw QuotaFailure("SuperGrok quota RPC returned gRPC $status", if (status in setOf(7, 16)) 401 else null)
                    successfulTrailer = status == 0
                } else if (flags == 0) payloads.add(payload)
                else throw QuotaFailure("Unsupported Grok quota frame encoding")
            }
            if (requireTrailers && !successfulTrailer) throw QuotaFailure("Grok quota response has no successful status trailer")
            return payloads
        }

        private fun fields(bytes: ByteArray): List<Field> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun varint(): Long {
                var value = 0L
                for (shift in 0..56 step 7) {
                    if (!buffer.hasRemaining()) throw QuotaFailure("Truncated Grok quota value")
                    val byte = buffer.get().toInt() and 255
                    value = value or ((byte and 127).toLong() shl shift)
                    if (byte and 128 == 0) return value
                }
                throw QuotaFailure("Invalid Grok quota value")
            }
            val result = mutableListOf<Field>()
            while (buffer.hasRemaining()) {
                val tag = varint()
                val id = (tag ushr 3).toInt()
                if (id <= 0) throw QuotaFailure("Invalid Grok quota field")
                result.add(when ((tag and 7).toInt()) {
                    0 -> Field(id, number = varint().toDouble())
                    1 -> Field(id, number = buffer.double.takeIf { it.isFinite() }, metric = true)
                    5 -> Field(id, number = buffer.float.toDouble().takeIf { it.isFinite() }, metric = true)
                    2 -> {
                        val size = varint()
                        if (size > buffer.remaining() || size < 0) throw QuotaFailure("Invalid Grok quota field length")
                        Field(id, bytes = ByteArray(size.toInt()).also(buffer::get))
                    }
                    else -> throw QuotaFailure("Unsupported Grok quota field")
                })
            }
            return result
        }
    }
}
