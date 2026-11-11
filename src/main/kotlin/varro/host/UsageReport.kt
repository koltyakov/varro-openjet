package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import varro.server.OpenCodeV2SessionState
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Read local usage first, starting the server only for the REST fallback. */
class UsageReport(
    private val databasePath: Path = LocalUsageDatabase.defaultPath(),
    private val ensureServerStarted: () -> Unit = {},
    private val attachOnly: Boolean = false,
    private val readAnnotations: (String) -> JsonObject = OpenCodeV2SessionState()::read,
    private val apiVersion: () -> Int = { 1 },
    private val request: (String, RequestOptions) -> OpenCodeResponse,
) {
    fun build(includeAllTime: Boolean, now: Long = System.currentTimeMillis(), checkCancelled: () -> Unit = {}): String {
        val midnight = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val windows = listOf("Today" to midnight, "Last 7 rolling days" to now - 7 * DAY, "Last 30 rolling days" to now - 30 * DAY) +
            (if (includeAllTime) listOf("All time" to 0L) else emptyList()) + listOf("Undated session usage" to Long.MIN_VALUE)
        val groups = windows.map { sortedMapOf<String, Total>() }
        var aggregated = 0
        fun addUsage(id: String, info: JsonObject) {
            val undated = info.str("source") == "unrecorded"
            val created = info.obj("time").long("completed") ?: info.obj("time").long("created")
            if (!undated && (created == null || created > now || created < windows.filter { it.second != Long.MIN_VALUE }.minOf { it.second })) return
            check(++aggregated <= 250_000) { "Usage report exceeds the 250,000-message local aggregation limit" }
            val model = "${info.str("providerID") ?: info.obj("model").str("providerID") ?: "unknown"}\u0000${info.str("modelID") ?: info.obj("model").str("id") ?: info.obj("model").str("modelID") ?: "unknown"}" + (info.str("source")?.let { "\u0000$it" } ?: "")
            val usage = Total().apply { add(id, info) }
            if (usage.total <= 0) return
            windows.forEachIndexed { index, (_, start) ->
                if (if (start == Long.MIN_VALUE) undated else if (undated) start == 0L else created!! >= start) {
                    check(model in groups[index] || groups[index].size < 4_096) { "Usage report exceeds the 4,096-route aggregation limit" }
                    groups[index].getOrPut(model) { Total() }.merge(usage)
                }
            }
        }
        val localCount = if (attachOnly) null else LocalUsageDatabase(databasePath, readAnnotations).read(
            if (includeAllTime) null else now - 30 * DAY, checkCancelled, ::addUsage,
        )
        val sessions = linkedMapOf<String, JsonObject>()
        if (localCount == null) {
            readRemote(includeAllTime, now, checkCancelled, sessions, ::addUsage)
        }
        return render(now, windows, groups, localCount ?: sessions.size.toLong())
    }

    private fun readRemote(
        includeAllTime: Boolean,
        now: Long,
        checkCancelled: () -> Unit,
        sessions: MutableMap<String, JsonObject>,
        addUsage: (String, JsonObject) -> Unit,
    ) {
        ensureServerStarted()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            checkCancelled()
            val path = "/experimental/session?archived=true&limit=1000" +
                (if (includeAllTime || apiVersion() == 2) "" else "&start=${now - 30 * DAY}") +
                (cursor?.let { "&cursor=${encode(it)}" } ?: "")
            val response = request(path, RequestOptions(unscoped = true, captureNextCursor = true))
            val page = response.data.asArrayOrNull() ?: response.data.asObjectOrNull().arr("data")
                ?: error("OpenCode returned a malformed session list")
            page.forEach { value -> value.asObjectOrNull()?.let { session ->
                session.str("id")?.let { sessions[it] = session }
            } }
            check(sessions.size <= 250) {
                if (attachOnly) "Usage reports in attach-only mode support up to 250 sessions through the API. Run usage reporting on the server host for larger histories."
                else "The local OpenCode usage database is unavailable. Refusing to fetch full history for more than 250 sessions."
            }
            cursor = response.nextCursor
            check(cursor == null || cursors.add(cursor)) { "OpenCode repeated a session pagination cursor" }
        } while (cursor != null)

        for ((id, session) in sessions) {
            checkCancelled()
            val messages = try {
                val data = request("/session/${encode(id)}/message", RequestOptions(directory = session.str("directory"))).data
                data.asArrayOrNull() ?: data.asObjectOrNull().arr("data") ?: error("Malformed message history")
            } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) {
                throw failure
            } catch (failure: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(UsageReport::class.java)
                    .warn("Could not read usage for session $id", failure)
                continue
            }
            val seen = mutableSetOf<String>()
            val remainder = if (apiVersion() == 2) session.obj("tokens")?.let { tokens ->
                runCatching { AuxiliaryUsage.validate(Json.obj("source" to "unrecorded", "tokens" to tokens)); AuxiliaryUsage.Remainder(tokens) }.getOrNull()
            } else null
            val pauses = SessionPauses.read(session.obj("metadata"))
            messages.forEach { value ->
                val info = value.asObjectOrNull().obj("info") ?: value.asObjectOrNull() ?: return@forEach
                if (info.str("role") != "assistant") return@forEach
                val messageId = info.str("id") ?: return@forEach
                if (!seen.add(messageId)) return@forEach
                remainder?.subtract(info)
                addUsage(id, SessionPauses.capUsage(info, pauses))
            }
            if (apiVersion() == 2) {
                val auxiliary = try {
                    AuxiliaryUsage.log(request("/api/experimental/session/${encode(id)}/log?follow=false&after=0",
                        RequestOptions(directory = session.str("directory"), maxResponseBytes = 32L * 1024 * 1024)).data, id)
                } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) { throw failure }
                catch (failure: Exception) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(UsageReport::class.java).warn("Could not read auxiliary usage for session $id", failure)
                    emptyList()
                }
                auxiliary.forEach { checkCancelled(); remainder?.subtract(it); addUsage(id, it) }
                remainder?.info()?.let { addUsage(id, it) }
            }
        }
    }

    private fun render(
        now: Long,
        windows: List<Pair<String, Long>>,
        groups: List<Map<String, Total>>,
        sessionCount: Long,
    ): String = buildString {
        appendLine("# OpenCode Usage Report")
        appendLine()
        appendLine("Generated ${Instant.ofEpochMilli(now)}. Retained history across all projects, $sessionCount sessions scanned.")
        appendLine()
        appendLine("Token counts use compact notation. Cache rate is cache read / (input + cache read + cache write), calculated from summed token counts. A dash in Cache rate means no recorded input tokens.")
        appendLine("Unattributed rows include session generation, titles and compaction. They add tokens, not prompts or assistant duration. Undated usage is the remainder of native totals after message and retained-event usage. Its date, model and request count are unknown. It is excluded from dated windows and included in All time.")
        windows.forEachIndexed { index, (title, _) ->
            if (title == "Undated session usage" && groups[index].isEmpty()) return@forEachIndexed
            appendLine("\n## $title\n")
            if (groups[index].isEmpty()) {
                appendLine("_No token usage._")
                return@forEachIndexed
            }
            appendLine("| Provider | Model | Prompts | Total | Duration | Input | Output | Reasoning | Cache read | Cache write | Cache rate |")
            appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            val total = Total()
            groups[index].entries.sortedWith(compareByDescending<Map.Entry<String, Total>> { it.value.prompts.size }
                .thenByDescending { it.value.total }.thenBy { it.key }).forEach { (route, usage) ->
                val fields = route.split('\u0000')
                val source = fields.getOrNull(2)
                val label = when (source) { "generate" -> "Session generation"; "title" -> "Session titles"; "compaction" -> "Compaction"; "unrecorded" -> "Other session usage"; else -> fields[1] }
                appendLine(usage.row(if (source == null) fields[0] else "Unattributed", label))
                total.merge(usage)
            }
            appendLine(total.row("**Total**", ""))
        }
    }

    internal class Total {
        val prompts = mutableSetOf<String>()
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var total = 0L
        var durationMs = 0L
        var durationCount = 0
        fun add(sessionId: String, info: JsonObject) {
            info.str("parentID")?.let { prompts.add("$sessionId\u0000$it") }
            val tokens = info.obj("tokens")
            input += tokens.long("input")?.coerceAtLeast(0) ?: 0
            output += tokens.long("output")?.coerceAtLeast(0) ?: 0
            reasoning += tokens.long("reasoning")?.coerceAtLeast(0) ?: 0
            cacheRead += tokens.obj("cache").long("read")?.coerceAtLeast(0) ?: 0
            cacheWrite += tokens.obj("cache").long("write")?.coerceAtLeast(0) ?: 0
            total += tokens.long("total")?.takeIf { it >= 0 } ?: listOf(
                tokens.long("input"), tokens.long("output"), tokens.long("reasoning"),
                tokens.obj("cache").long("read"), tokens.obj("cache").long("write"),
            ).sumOf { it?.coerceAtLeast(0) ?: 0 }
            val created = info.obj("time").long("created")
            val completed = info.obj("time").long("completed")
            if (created != null && completed != null && completed >= created) {
                durationMs += completed - created
                durationCount++
            }
        }
        fun merge(other: Total) {
            prompts.addAll(other.prompts)
            input += other.input
            output += other.output
            reasoning += other.reasoning
            cacheRead += other.cacheRead
            cacheWrite += other.cacheWrite
            total += other.total
            durationMs += other.durationMs
            durationCount += other.durationCount
        }
        fun row(provider: String, model: String) =
            "| ${escape(provider)} | ${escape(model)} | ${integer(prompts.size.toLong())} | ${tokens(total)} | ${duration()} | ${tokens(input)} | ${tokens(output)} | ${tokens(reasoning)} | ${tokens(cacheRead)} | ${tokens(cacheWrite)} | ${cacheRate()} |"

        private fun tokens(value: Long) = tokenNumberFormat.get().format(value)

        private fun cacheRate(): String {
            val prompt = input.toDouble() + cacheRead + cacheWrite
            return if (prompt > 0) String.format(Locale.US, "%.1f%%", cacheRead / prompt * 100) else "-"
        }

        private fun duration(): String {
            if (durationCount == 0) return "-"
            if (durationMs < 1_000) return "<1s"
            val seconds = (durationMs + 500) / 1_000
            if (seconds < 60) return "${seconds}s"
            val minutes = seconds / 60
            if (minutes < 60) return "${minutes}m" + if (seconds % 60 > 0) " ${seconds % 60}s" else ""
            return "${minutes / 60}h" + if (minutes % 60 > 0) " ${minutes % 60}m" else ""
        }

        private fun integer(value: Long) = String.format(Locale.US, "%,d", value)
        private fun escape(value: String) = value.replace("|", "\\|").replace(Regex("[\r\n]+"), " ")
    }

    companion object {
        private val tokenNumberFormat = ThreadLocal.withInitial {
            java.text.NumberFormat.getCompactNumberInstance(Locale.US, java.text.NumberFormat.Style.SHORT).apply {
                maximumFractionDigits = 1
                roundingMode = java.math.RoundingMode.HALF_UP
            }
        }
        private const val DAY = 86_400_000L
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
