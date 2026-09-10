package varro.host.quota

import com.google.gson.JsonObject
import varro.protocol.*
import java.net.URI

internal class AntigravityQuota(private val http: QuotaHttp, private val environment: Map<String, String>) {
    fun poll(model: String?, now: Long): List<JsonObject> {
        val explicit = environment["ANTIGRAVITY_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = if (explicit != null) {
            requireLocal(explicit)
            listOf(explicit.trimEnd('/') to environment["ANTIGRAVITY_CSRF_TOKEN"].orEmpty())
        } else discover()
        if (candidates.isEmpty()) throw QuotaFailure("Antigravity language server was not found. Start Antigravity or set ANTIGRAVITY_BASE_URL and ANTIGRAVITY_CSRF_TOKEN.", 401)
        for ((base, csrf) in candidates) {
            val response = runCatching { http.send(QuotaRequest(
                "$base/exa.language_server_pb.LanguageServerService/GetUserStatus",
                mapOf("Connect-Protocol-Version" to "1", "X-Codeium-Csrf-Token" to csrf),
                Json.stringify(Json.obj("metadata" to Json.obj("ideName" to "antigravity", "extensionName" to "antigravity", "locale" to "en"))).toByteArray(),
            )) }.getOrNull() ?: continue
            if (response.code != 200) continue
            return parse(response.json(), model, now)
        }
        throw QuotaFailure("Antigravity's local quota endpoint could not be reached or rejected the local session")
    }

    private fun requireLocal(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host !in setOf("127.0.0.1", "[::1]", "::1") || uri.userInfo != null) {
            throw QuotaFailure("Antigravity quota URL must use a loopback IP address")
        }
    }

    private fun discover(): List<Pair<String, String>> = ProcessHandle.allProcesses().use { processes ->
        processes.map { process ->
            val info = process.info()
            val command = info.commandLine().orElse("")
            if (!command.contains("antigravity", ignoreCase = true) || !command.contains("language_server", ignoreCase = true)) return@map null
            fun argument(name: String) = Regex("(?:^|\\s)--$name(?:=|\\s+)([^\\s]+)").find(command)?.groupValues?.get(1)?.trim('"', '\'')
            val csrf = argument("csrf_token") ?: return@map null
            val port = argument("extension_server_port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@map null
            "http://127.0.0.1:$port" to csrf
        }.filter { it != null }.limit(8).toList().filterNotNull()
    }

    companion object {
        internal fun parse(payload: JsonObject, model: String?, now: Long): List<JsonObject> {
            val status = payload.obj("userStatus") ?: throw QuotaFailure("Antigravity language server is not authenticated", 401)
            fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
            return status.obj("cascadeModelConfigData").elements("clientModelConfigs").mapNotNull { value ->
                val row = value.asObjectOrNull()
                val id = row.obj("modelOrAlias").string("model") ?: return@mapNotNull null
                if (model != null && normalize(id) != normalize(model)) return@mapNotNull null
                val quota = row.obj("quotaInfo")
                val fraction = quota.number("remainingFraction")?.coerceIn(0.0, 1.0) ?: return@mapNotNull null
                window(id, row.string("label")?.replace(Regex("\\s*\\(thinking\\)\\s*$", RegexOption.IGNORE_CASE), "") ?: id,
                    fraction * 100, 100.0, quota.reset(now, "resetTime"), (1 - fraction) * 100, "credits")
            }
        }
    }
}
