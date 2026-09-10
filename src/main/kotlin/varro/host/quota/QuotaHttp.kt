package varro.host.quota

import com.google.gson.JsonObject
import com.intellij.util.io.HttpRequests
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import java.net.HttpURLConnection
import java.net.URI

data class QuotaRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String = "application/json",
)

data class QuotaResponse(val code: Int, val bytes: ByteArray, val headers: Map<String, String> = emptyMap()) {
    fun json(): JsonObject = Json.parseOrNull(bytes.toString(Charsets.UTF_8)).asObjectOrNull()
        ?: throw QuotaFailure("Provider returned an invalid JSON response")
}

class QuotaFailure(message: String, val code: Int? = null) : RuntimeException(message)

fun interface QuotaHttp {
    fun send(request: QuotaRequest): QuotaResponse
}

/** Uses the IDE's proxy and certificate configuration, not a child process's networking. */
class IdeQuotaHttp : QuotaHttp {
    override fun send(request: QuotaRequest): QuotaResponse {
        val uri = URI(request.url)
        require(uri.scheme == "https" || isLoopbackUrl(request.url)) { "Invalid provider quota URL" }
        require(uri.userInfo == null) { "Provider quota URLs cannot contain credentials" }
        require(request.headers.values.none { value -> value.any { it == '\r' || it == '\n' || it == '\u0000' } }) { "Invalid provider quota header" }
        val builder = if (request.body == null) HttpRequests.request(request.url)
            else HttpRequests.post(request.url, request.contentType)
        return builder.connectTimeout(10_000).readTimeout(15_000).redirectLimit(1)
            .followRedirects(false).throwStatusCodeException(false).gzip(false)
            .tuner { connection ->
                (connection as HttpURLConnection).instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Varro-OpenJet/0.1.0")
                request.headers.forEach(connection::setRequestProperty)
            }.connect { connection ->
                request.body?.let { connection.write(it) }
                val http = connection.connection as HttpURLConnection
                val code = http.responseCode
                val stream = if (code >= 400) http.errorStream else http.inputStream
                val bytes = stream?.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) } ?: byteArrayOf()
                if (bytes.size > MAX_RESPONSE_BYTES) throw QuotaFailure("Provider quota response exceeded 1 MiB")
                QuotaResponse(code, bytes, http.headerFields.entries.filter { it.key != null }
                    .associate { it.key.lowercase() to it.value.joinToString(",") })
            }
    }

    companion object { private const val MAX_RESPONSE_BYTES = 1024 * 1024 }
}

internal fun isLoopbackUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]", "::1") &&
        uri.userInfo == null && uri.fragment == null && uri.rawAuthority.matches(Regex("(?:127\\.0\\.0\\.1|\\[::1])(?::[0-9]+)?"))
}.getOrDefault(false)
