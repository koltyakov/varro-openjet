package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Read-only retained-history accounting, matching Varro's REST fallback. */
class UsageReport(private val request: (String, RequestOptions) -> OpenCodeResponse) {
    fun build(includeAllTime: Boolean, now: Long = System.currentTimeMillis(), checkCancelled: () -> Unit = {}): String {
        val windows = listOf("Last 24 hours" to now - DAY, "Last 7 days" to now - 7 * DAY, "Last 30 days" to now - 30 * DAY) +
            if (includeAllTime) listOf("All time" to 0L) else emptyList()
        val groups = windows.map { sortedMapOf<String, Total>() }
        val sessions = linkedMapOf<String, JsonObject>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            checkCancelled()
            val path = "/experimental/session?archived=true&limit=1000" +
                (if (includeAllTime) "" else "&start=${now - 30 * DAY}") +
                (cursor?.let { "&cursor=${encode(it)}" } ?: "")
            val response = request(path, RequestOptions(unscoped = true, captureNextCursor = true))
            val page = response.data.asArrayOrNull() ?: response.data.asObjectOrNull().arr("data")
                ?: error("OpenCode returned a malformed session list")
            page.forEach { value -> value.asObjectOrNull()?.let { session ->
                session.str("id")?.let { sessions[it] = session }
            } }
            check(sessions.size <= 250) { "Usage history exceeds 250 sessions. Use OpenCode's local `opencode stats` command for this report." }
            cursor = response.nextCursor
            check(cursor == null || cursors.add(cursor)) { "OpenCode repeated a session pagination cursor" }
        } while (cursor != null)

        val warnings = mutableListOf<String>()
        for ((id, session) in sessions) {
            checkCancelled()
            val messages = try {
                val data = request("/session/${encode(id)}/message", RequestOptions(directory = session.str("directory"))).data
                data.asArrayOrNull() ?: data.asObjectOrNull().arr("data") ?: error("Malformed message history")
            } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) {
                throw failure
            } catch (failure: Exception) {
                warnings.add("Could not read session $id: ${failure.message}")
                continue
            }
            val seen = mutableSetOf<String>()
            messages.forEach { value ->
                val info = value.asObjectOrNull().obj("info") ?: value.asObjectOrNull() ?: return@forEach
                if (info.str("role") != "assistant") return@forEach
                val messageId = info.str("id") ?: return@forEach
                if (!seen.add(messageId)) return@forEach
                val created = info.obj("time").long("created") ?: return@forEach
                if (created > now) return@forEach
                val model = "${info.str("providerID") ?: "unknown"}/${info.str("modelID") ?: "unknown"}"
                windows.forEachIndexed { index, (_, start) ->
                    if (created >= start) groups[index].getOrPut(model) { Total() }.add(id, info)
                }
            }
        }
        return buildString {
            appendLine("# OpenCode usage report")
            appendLine()
            appendLine("Generated ${Instant.ofEpochMilli(now)}. Retained history across all projects, ${sessions.size} sessions scanned.")
            appendLine("Costs are those recorded by OpenCode, not a provider invoice. Deleted history is unavailable.")
            windows.forEachIndexed { index, (title, _) ->
                appendLine("\n## $title\n")
                appendLine("| Provider/model | Prompts | Input | Output | Reasoning | Cache read | Cache write | Cost USD |")
                appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
                if (groups[index].isEmpty()) appendLine("| No usage | 0 | 0 | 0 | 0 | 0 | 0 | 0 |")
                val total = Total()
                groups[index].forEach { (model, usage) ->
                    appendLine(usage.row(model.replace("|", "\\|").replace("\n", " ")))
                    total.merge(usage)
                }
                appendLine(total.row("Total"))
            }
            if (warnings.isNotEmpty()) {
                appendLine("\n## Incomplete history\n")
                warnings.forEach { appendLine("- $it") }
            }
        }
    }

    internal class Total {
        val prompts = mutableSetOf<String>()
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var cost = 0.0
        fun add(sessionId: String, info: JsonObject) {
            info.str("parentID")?.let { prompts.add("$sessionId/$it") }
            val tokens = info.obj("tokens")
            input += tokens.long("input")?.coerceAtLeast(0) ?: 0
            output += tokens.long("output")?.coerceAtLeast(0) ?: 0
            reasoning += tokens.long("reasoning")?.coerceAtLeast(0) ?: 0
            cacheRead += tokens.obj("cache").long("read")?.coerceAtLeast(0) ?: 0
            cacheWrite += tokens.obj("cache").long("write")?.coerceAtLeast(0) ?: 0
            cost += info.num("cost")?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        }
        fun merge(other: Total) {
            prompts.addAll(other.prompts)
            input += other.input
            output += other.output
            reasoning += other.reasoning
            cacheRead += other.cacheRead
            cacheWrite += other.cacheWrite
            cost += other.cost
        }
        fun row(label: String) = "| $label | ${prompts.size} | $input | $output | $reasoning | $cacheRead | $cacheWrite | ${String.format(Locale.ROOT, "%.4f", cost)} |"
    }

    companion object {
        private const val DAY = 86_400_000L
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
