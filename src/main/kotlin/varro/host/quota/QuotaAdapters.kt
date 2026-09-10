package varro.host.quota

import com.google.gson.JsonObject
import varro.host.VarroBuild
import varro.protocol.*
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files

/** Provider endpoint contracts ported from Varro's provider-limits adapters. */
internal class QuotaAdapters(
    private val http: QuotaHttp,
    private val credentials: QuotaCredentials,
    private val setAuth: (String, JsonObject) -> Unit,
) {
    fun poll(provider: JsonObject, auth: JsonObject, model: String?, now: Long): JsonObject? {
        val id = provider.str("id") ?: return null
        fun available(windows: List<JsonObject>, plan: String? = null): JsonObject = Json.obj(
            "providerID" to id, "modelID" to model, "checkedAt" to now, "source" to "provider",
            "status" to if (windows.isEmpty()) "unsupported" else "available",
            "note" to if (windows.isEmpty()) "Provider returned no supported quota windows" else "Polled provider quota endpoint",
        ).apply {
            if (windows.isNotEmpty()) add("windows", Json.array(windows.distinctBy { it.str("id") }))
            plan?.let { addProperty("planName", it) }
        }
        fun token(): String = credentials.token(provider, auth) ?: throw QuotaFailure("No $id credentials available. Connect the provider in OpenCode.", 401)
        return when (id) {
            "antigravity" -> available(AntigravityQuota(http, credentials.environment).poll(model, now))
            "claude-code" -> {
                val descriptor = provider.obj("options").obj("claude-code").obj("providerLimits")
                val url = descriptor.string("url") ?: return null
                if (descriptor.int("schemaVersion") != 1 || descriptor.str("transport") != "http" || !isLoopbackUrl(url)) {
                    throw QuotaFailure("Claude Code provider-limit endpoint is not a valid loopback descriptor")
                }
                val value = json(url, descriptor.string("token") ?: throw QuotaFailure("Claude Code quota descriptor has no token"))
                val status = value.obj("providerLimit")
                if (value.int("schemaVersion") != 1 || status.str("providerID") != id ||
                    status.str("status") !in setOf("available", "unsupported", "error") || status.long("checkedAt") == null
                ) throw QuotaFailure("Claude Code quota endpoint returned an invalid response")
                status!!.deepCopy().apply { addProperty("modelID", model) }
            }
            "anthropic" -> {
                val windows = mutableListOf<JsonObject>()
                val statusFile = credentials.home.resolve(".onwatch/data/anthropic-statusline.json")
                if (Files.isRegularFile(statusFile) && now - Files.getLastModifiedTime(statusFile).toMillis() in 0..300_000) {
                    val snapshot = credentials.read(statusFile).record("rate_limits", "rateLimits")
                    listOf("five_hour" to "fiveHour", "seven_day" to "sevenDay").forEach { (key, camel) ->
                        val row = snapshot.record(key, camel)
                        window(key, periodLabel(key), null, null, row.reset(now, "resets_at", "resetsAt"),
                            row.number("used_percentage", "usedPercentage"))?.let(windows::add)
                    }
                }
                val bases = listOfNotNull(provider.obj("options").string("baseURL", "baseUrl")) +
                    provider.obj("models")?.entrySet().orEmpty().mapNotNull { it.value.asObjectOrNull().obj("api").string("url") }
                bases.firstOrNull(::isLoopbackUrl)?.let { base ->
                    val uri = URI(base)
                    runCatching { json("${uri.scheme}://${uri.rawAuthority}/v1/usage/quota") }.getOrNull()?.let { payload ->
                        payload.elements("buckets").forEach { bucket ->
                            val row = bucket.asObjectOrNull()
                            val key = row.string("type") ?: return@forEach
                            if (key != "seven_day_omelette") window(key, periodLabel(key), null, null,
                                row.reset(now, "resetsAt"), row.number("utilization")?.times(100))?.let(windows::add)
                        }
                    }
                }
                val openCode = auth.obj(id)?.takeIf { it.str("type") == "oauth" }
                val local = if (openCode == null) credentials.read(credentials.claudePath).obj("claudeAiOauth") else null
                var access = openCode.string("access") ?: local.string("accessToken")
                if (access == null && windows.isNotEmpty()) return available(windows)
                if (access == null) throw QuotaFailure("No Anthropic OAuth credentials available. Connect Anthropic in OpenCode or Claude Code.", 401)
                val headers = mapOf("anthropic-beta" to "oauth-2025-04-20", "User-Agent" to "claude-code/2.1.69")
                try {
                    var response = send("https://api.anthropic.com/api/oauth/usage", access, headers)
                    val refresh = local.string("refreshToken")
                    if (response.code == 401 && refresh != null) {
                        val refreshed = json("https://console.anthropic.com/v1/oauth/token", body = Json.obj(
                            "grant_type" to "refresh_token", "refresh_token" to refresh,
                            "client_id" to "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
                        ))
                        credentials.updateClaude(refresh, refreshed, now)
                        access = refreshed.string("access_token") ?: throw QuotaFailure("Claude token refresh returned no access token")
                        response = send("https://api.anthropic.com/api/oauth/usage", access, headers)
                    }
                    val payload = checked(response).json()
                    for (key in listOf("five_hour", "seven_day", "seven_day_sonnet", "monthly_limit", "extra_usage")) {
                        val row = payload.obj(key) ?: continue
                        if (row.bool("is_enabled") == false || row.bool("isEnabled") == false) continue
                        val limit = row.number("monthly_limit", "monthlyLimit")
                        val used = row.number("used_credits", "usedCredits")
                        window(key, periodLabel(key), if (limit != null && used != null) limit - used else null,
                            limit, row.reset(now, "resets_at", "resetsAt"), row.number("utilization"),
                            if (limit != null && used != null) "credits" else "unknown")?.let(windows::add)
                    }
                } catch (failure: Exception) { if (windows.isEmpty()) throw failure }
                available(windows)
            }
            "openai" -> {
                if (auth.obj(id).str("type") != "oauth" && provider.obj("options").string("apiKey") != QuotaCredentials.OAUTH_DUMMY) {
                    return available(headerWindows("https://api.openai.com/v1/models", token(), now))
                }
                val oauth = credentials.codex(auth) ?: throw QuotaFailure("No Codex OAuth credentials available", 401)
                val headers = mutableMapOf("User-Agent" to "codex-cli/1.0.0")
                oauth.string("accountId", "account_id")?.let { headers["ChatGPT-Account-Id"] = it; headers["X-Account-Id"] = it }
                var base = "https://chatgpt.com/backend-api/wham"
                var response = send("$base/usage", oauth.string("access"), headers)
                if (response.code == 404) { base = "https://chatgpt.com/api/codex"; response = send("$base/usage", oauth.string("access"), headers) }
                val payload = checked(response).json()
                val plan = payload.string("plan_type")
                val result = available(QuotaParsers.codex(payload, now), when (plan) { "pro" -> "Pro 20x"; "prolite" -> "Pro 5x"; else -> plan?.let(::label) })
                payload.record("rate_limit_reset_credits", "rateLimitResetCredits").number("available_count", "availableCount")?.takeIf { it > 0 }?.let { count ->
                    val summary = Json.obj("availableCount" to count.toInt(), "credits" to null)
                    runCatching { json("$base/rate-limit-reset-credits", oauth.string("access"), headers) }.getOrNull()?.let { detail ->
                        summary.add("credits", Json.array(detail.elements("credits").mapNotNull { value ->
                            val credit = value.asObjectOrNull()
                            if (credit.str("status") != "available") return@mapNotNull null
                            val expiration = credit?.get("expires_at") ?: credit?.get("expiresAt") ?: return@mapNotNull null
                            val expiresAt = if (expiration.isJsonNull) null else resetAt(expiration, now) ?: return@mapNotNull null
                            Json.obj("title" to (credit.string("title") ?: "Full reset"), "expiresAt" to expiresAt)
                        }.take(count.toInt())))
                    }
                    result.add("usageLimitResets", summary)
                }
                result
            }
            "github-copilot" -> {
                val key = credentials.token(provider, auth) ?: credentials.copilotFallback()
                    ?: throw QuotaFailure("No GitHub Copilot credentials available", 401)
                val payload = json("https://api.github.com/copilot_internal/user", key, mapOf(
                    "Editor-Version" to "JetBrains/2026.2", "Editor-Plugin-Version" to "varro/${VarroBuild.version}"))
                available(QuotaParsers.copilot(payload, now),
                    if (payload.str("access_type_sku") == "free_limited_copilot") "Free" else payload.string("copilot_plan")?.let(::label))
            }
            "openrouter" -> {
                val payload = json("https://openrouter.ai/api/v1/auth/key", token()).obj("data")
                val limit = payload.number("limit")
                val used = payload.number("usage")
                val remaining = if (limit != null && used != null) limit - used else payload.number("limit_remaining", "limitRemaining")
                available(listOfNotNull(window("spend", "Spend", remaining, limit, null, unit = "usd")),
                    if (payload.bool("is_free_tier") == true) "Free" else null)
            }
            "opencode-go" -> {
                val usage = json("https://opencode.ai/zen/go/v1/usage", token()).obj("usage")
                available(listOf("rolling" to "five_hour", "weekly" to "weekly", "monthly" to "monthly").mapNotNull { (key, name) ->
                    val row = usage.obj(key)
                    window(name, periodLabel(name), null, null, row.reset(now, "resetsAt"), row.number("percent"))
                }, "Go")
            }
            "ollama-cloud" -> {
                val limits = json("https://ollama.com/api/usage", token()).obj("limits")
                available(listOf("session" to "five_hour", "weekly" to "weekly").mapNotNull { (key, name) ->
                    val fraction = limits.obj(key).number("usage")?.takeIf { it in 0.0..1.0 }
                    window(name, periodLabel(name), null, null, null, fraction?.times(100))
                })
            }
            "google", "gemini" -> {
                val access = credentials.gemini(id, auth) ?: throw QuotaFailure("No Gemini OAuth credentials available", 401)
                available(QuotaParsers.gemini(json("https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota", access, body = JsonObject()), now))
            }
            "zai", "zai-coding-plan" -> {
                val payload = json("https://api.z.ai/api/monitor/usage/quota/limit", headers = mapOf("Authorization" to token()))
                if (payload.bool("success") == false || payload.int("code") in setOf(401, 403)) {
                    throw QuotaFailure("Z.ai quota endpoint returned ${payload.int("code") ?: "an error"}", payload.int("code"))
                }
                val result = available(QuotaParsers.zai(payload, now))
                runCatching { json("https://api.z.ai/api/biz/customer-package-reset/list?targetType=PERSONAL", headers = mapOf("Authorization" to token())) }
                    .getOrNull()?.takeIf { it.int("code") == 200 && it.bool("success") != false }?.obj("data")?.let { data ->
                        val available = listOf("weekResets" to "Weekly quota reset", "fiveHourResets" to "5-hour quota reset").flatMap { (key, title) ->
                            data.elements(key).mapNotNull { entry -> entry.asObjectOrNull()?.takeIf { it.bool("available") == true }?.let { it to title } }
                        }
                        if (available.isNotEmpty()) result.add("usageLimitResets", Json.obj("availableCount" to available.size,
                            "credits" to Json.array(available.mapNotNull { (credit, title) ->
                                credit.reset(now, "expireTime")?.let { Json.obj("title" to title, "expiresAt" to it) }
                            })))
                    }
                result
            }
            "minimax" -> {
                val payload = json("https://api.minimax.io/v1/api/openplatform/coding_plan/remains", token())
                val code = payload.record("base_resp", "baseResp").number("status_code", "statusCode")?.toInt()
                if (code != null && code != 0) throw QuotaFailure("MiniMax quota endpoint returned $code", if (code == 1004) 401 else code)
                available(QuotaParsers.minimax(payload, now))
            }
            "kimi-for-coding" -> available(QuotaParsers.kimi(json("https://api.kimi.com/coding/v1/usages", token()), now))
            "xai" -> {
                val oauth = auth.obj(id)
                if (oauth.str("type") != "oauth") return available(headerWindows("https://api.x.ai/v1/models", token(), now))
                var access = oauth.string("access") ?: throw QuotaFailure("No SuperGrok OAuth token available", 401)
                fun refresh(): Boolean {
                    val refresh = oauth.string("refresh") ?: return false
                    val form = "grant_type=refresh_token&client_id=b1a00492-073a-47ea-816f-4c329264a828&refresh_token=${URLEncoder.encode(refresh, Charsets.UTF_8)}"
                    val next = checked(http.send(QuotaRequest("https://auth.x.ai/oauth2/token", body = form.toByteArray(), contentType = "application/x-www-form-urlencoded"))).json()
                    access = next.string("access_token") ?: throw QuotaFailure("SuperGrok refresh returned no access token")
                    setAuth(id, Json.obj("type" to "oauth", "access" to access, "refresh" to (next.string("refresh_token") ?: refresh),
                        "expires" to now + ((next.number("expires_in") ?: 3600.0) * 1000).toLong()))
                    return true
                }
                var refreshed = false
                if ((oauth.long("expires") ?: Long.MAX_VALUE) <= now + 300_000) refreshed = refresh()
                val headers = mapOf("x-xai-token-auth" to "xai-grok-cli")
                var response = send("https://cli-chat-proxy.grok.com/v1/billing?format=credits", access, headers)
                if (response.code in setOf(401, 403) && !refreshed && refresh()) response = send("https://cli-chat-proxy.grok.com/v1/billing?format=credits", access, headers)
                val windows = QuotaParsers.xai(checked(response).json(), now).toMutableList()
                if (windows.none { it.str("id") == "credits" }) windows.addAll(QuotaParsers.xai(json("https://cli-chat-proxy.grok.com/v1/billing", access, headers), now))
                val rpc = GrokQuotaRpc(http, access)
                if (windows.isEmpty()) rpc.credits(now)?.let(windows::add)
                available(windows, "SuperGrok").apply {
                    runCatching { rpc.resets(now) }.getOrNull()?.let { add("usageLimitResets", it) }
                }
            }
            else -> null
        }
    }

    private fun send(url: String, token: String? = null, headers: Map<String, String> = emptyMap(), body: JsonObject? = null): QuotaResponse =
        http.send(QuotaRequest(url, (token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()) + headers,
            body?.let { Json.stringify(it).toByteArray() }))

    private fun json(url: String, token: String? = null, headers: Map<String, String> = emptyMap(), body: JsonObject? = null): JsonObject =
        checked(send(url, token, headers, body)).json()

    private fun checked(response: QuotaResponse): QuotaResponse {
        if (response.code !in 200..299) throw QuotaFailure(
            if (response.code in setOf(401, 403)) "Provider rejected quota credentials (${response.code}). Reconnect the provider."
            else "Provider quota endpoint returned HTTP ${response.code}", response.code)
        return response
    }

    private fun headerWindows(url: String, token: String, now: Long): List<JsonObject> {
        val response = checked(send(url, token))
        val headers = Json.obj(*response.headers.map { it.key to it.value }.toTypedArray())
        return listOf("requests", "tokens", "limit").mapNotNull { id ->
            val prefix = if (id == "limit") "x-ratelimit" else "x-ratelimit"
            val suffix = if (id == "limit") "" else "-$id"
            window(id, label(id), headers.number("$prefix-remaining$suffix", "ratelimit-remaining"),
                headers.number("$prefix-limit$suffix", "ratelimit-limit"), headers.reset(now, "$prefix-reset$suffix", "ratelimit-reset"),
                unit = if (id == "limit") "unknown" else id)
        }
    }
}

internal fun periodLabel(id: String): String = when (id) {
    "five_hour" -> "5-Hour Limit"
    "seven_day", "weekly" -> "Weekly All-Model"
    "seven_day_sonnet" -> "Weekly Sonnet"
    "spark_five_hour" -> "5-Hour Limit (Spark)"
    "spark_seven_day" -> "Weekly Limit (Spark)"
    "code_review" -> "Review Requests"
    else -> label(id)
}
