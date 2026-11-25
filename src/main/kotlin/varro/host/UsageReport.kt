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
    /** Mirrors `varro.usageReports.showCacheWrite`. */
    private val showCacheWrite: Boolean = false,
    private val request: (String, RequestOptions) -> OpenCodeResponse,
) {
    private data class Window(val title: String, val start: Long, val end: Long? = null)

    fun build(includeAllTime: Boolean, now: Long = System.currentTimeMillis(), checkCancelled: () -> Unit = {}): String {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val windows = listOf(Window("Today", midnight), Window("Yesterday", yesterday, midnight),
            Window("Last 7 rolling days", now - 7 * DAY), Window("Last 30 rolling days", now - 30 * DAY)) +
            (if (includeAllTime) listOf(Window("All time", 0L)) else emptyList()) + listOf(Window("Undated session usage", Long.MIN_VALUE))
        val groups = windows.map { sortedMapOf<String, Total>() }
        var aggregated = 0
        fun addUsage(id: String, info: JsonObject) {
            val undated = info.str("source") == "unrecorded"
            val created = info.obj("time").long("completed") ?: info.obj("time").long("created")
            if (!undated && (created == null || created > now || created < windows.filter { it.start != Long.MIN_VALUE }.minOf { it.start })) return
            check(++aggregated <= 250_000) { "Usage report exceeds the 250,000-message local aggregation limit" }
            val model = route(info)
            val usage = Total().apply { add(id, info) }
            if (usage.total <= 0) return
            windows.forEachIndexed { index, window ->
                if (if (window.start == Long.MIN_VALUE) undated else if (undated) window.start == 0L
                    else created!! >= window.start && (window.end == null || created < window.end)) {
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

        val log = com.intellij.openapi.diagnostic.Logger.getInstance(UsageReport::class.java)
        for ((id, session) in sessions) {
            readSessionUsage(id, session, checkCancelled, emptyMap(), { message, failure -> log.warn(message, failure) }, addUsage)
        }
    }

    private fun readSessionUsage(
        id: String,
        session: JsonObject,
        checkCancelled: () -> Unit,
        timings: Map<Pair<String, String>, JsonObject>,
        warn: (String, Exception) -> Unit,
        addUsage: (String, JsonObject) -> Unit,
    ) {
        checkCancelled()
        val messages = try {
            val data = request("/session/${encode(id)}/message", RequestOptions(directory = session.str("directory"))).data
            data.asArrayOrNull() ?: data.asObjectOrNull().arr("data") ?: error("Malformed message history")
        } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) {
            throw failure
        } catch (failure: Exception) {
            warn("Could not read messages for session $id", failure)
            return
        }
        val seen = mutableSetOf<String>()
        val remainder = if (apiVersion() == 2) session.obj("tokens")?.let { tokens ->
            runCatching { AuxiliaryUsage.validate(Json.obj("source" to "unrecorded", "tokens" to tokens)); AuxiliaryUsage.Remainder(tokens) }.getOrNull()
        } else null
        val pauses = SessionPauses.read(session.obj("metadata"))
        val identity = session.obj("metadata").obj("varroLegacyImport").str("sourceSessionID") ?: id
        messages.forEach { value ->
            var info = value.asObjectOrNull().obj("info") ?: value.asObjectOrNull() ?: return@forEach
            if (info.str("role") != "assistant") return@forEach
            val messageId = info.str("id") ?: return@forEach
            if (!seen.add(messageId)) return@forEach
            remainder?.subtract(info)
            OriginalUsageTiming.completed(timings[id to messageId], identity, info)?.let { completed ->
                info = info.deepCopy().apply { obj("time")?.addProperty("completed", completed) }
            }
            addUsage(id, SessionPauses.capUsage(info, pauses))
        }
        if (apiVersion() == 2) {
            val auxiliary = try {
                AuxiliaryUsage.log(request("/api/experimental/session/${encode(id)}/log?follow=false&after=0",
                    RequestOptions(directory = session.str("directory"), maxResponseBytes = 32L * 1024 * 1024)).data, id)
            } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) { throw failure }
            catch (failure: Exception) {
                warn("Could not read auxiliary usage for session $id", failure)
                emptyList()
            }
            auxiliary.forEach { checkCancelled(); remainder?.subtract(it); addUsage(id, it) }
            remainder?.info()?.let { addUsage(id, it) }
        }
    }

    data class SessionReport(val title: String, val content: String)
    private data class TreeNode(val id: String, val session: JsonObject, val title: String, val depth: Int)

    /** Usage for one conversation: its root session and every nested session, read through the API. */
    fun buildSession(sessionId: String, directory: String?, now: Long = System.currentTimeMillis(), checkCancelled: () -> Unit = {}): SessionReport {
        ensureServerStarted()
        val warnings = mutableListOf<String>()
        val root = sessionTreeRoot(sessionId, directory, checkCancelled)
        val tree = sessionTree(root, warnings, checkCancelled)
        val timings = try { originalTimings(tree.map { it.id }.toSet()) } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) {
            throw failure
        } catch (failure: Exception) {
            warnings += "Could not read preserved usage timing: ${failure.message ?: failure.javaClass.simpleName}."
            emptyMap()
        }
        val usage = tree.map { node ->
            val routes = linkedMapOf<String, Total>()
            readSessionUsage(node.id, node.session, checkCancelled, timings,
                { message, failure -> warnings += "$message: ${failure.message ?: failure.javaClass.simpleName}." }) { id, info ->
                val total = Total().apply { add(id, info) }
                if (total.total > 0) routes.getOrPut(route(info)) { Total() }.merge(total)
            }
            routes
        }
        return SessionReport(root.title, renderSession(now, tree, usage, warnings))
    }

    private fun sessionRecord(id: String, directory: String?, checkCancelled: () -> Unit): Pair<TreeNode, String?> {
        checkCancelled()
        val record = request("/session/${encode(id)}", RequestOptions(directory = directory)).data.asObjectOrNull()
        require(record.str("id") == id && !record.str("directory").isNullOrEmpty()) { "OpenCode returned a malformed session $id." }
        return TreeNode(id, record!!, record.str("title")?.takeIf { it.isNotEmpty() } ?: id, 0) to record.str("parentID")?.takeIf { it.isNotEmpty() }
    }

    private fun sessionTreeRoot(sessionId: String, directory: String?, checkCancelled: () -> Unit): TreeNode {
        var (current, parent) = sessionRecord(sessionId, directory, checkCancelled)
        val visited = mutableSetOf(current.id)
        while (parent != null) {
            check(parent !in visited && visited.size <= SESSION_TREE_MAX_DEPTH) { "Could not resolve the root session of $sessionId." }
            visited += parent
            val next = sessionRecord(parent, current.session.str("directory"), checkCancelled)
            current = next.first; parent = next.second
        }
        return current
    }

    /** Depth-first so nested sessions render under the session that started them. */
    private fun sessionTree(root: TreeNode, warnings: MutableList<String>, checkCancelled: () -> Unit): List<TreeNode> {
        val nodes = mutableListOf<TreeNode>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            checkCancelled()
            val node = pending.removeLast()
            if (!visited.add(node.id)) continue
            nodes += node
            check(nodes.size <= MAX_API_SESSIONS) { "Session usage reports support up to $MAX_API_SESSIONS nested sessions." }
            val children = try {
                val data = request("/session/${encode(node.id)}/children", RequestOptions(directory = node.session.str("directory"))).data
                data.asArrayOrNull() ?: data.asObjectOrNull().arr("data")
            } catch (failure: com.intellij.openapi.progress.ProcessCanceledException) { throw failure }
            catch (failure: Exception) {
                warnings += "Could not list nested sessions of ${node.id}: ${failure.message ?: failure.javaClass.simpleName}."
                continue
            }
            if (children == null) {
                warnings += "Ignored a malformed nested session list for ${node.id}."
                continue
            }
            val childNodes = children.mapNotNull { value ->
                val record = value.asObjectOrNull()
                val id = record.str("id")?.takeIf { it.isNotEmpty() }
                if (id == null || record.str("directory").isNullOrEmpty()) {
                    warnings += "Ignored a malformed nested session without an id or directory."
                    return@mapNotNull null
                }
                TreeNode(id, record!!, record.str("title")?.takeIf { it.isNotEmpty() } ?: id, node.depth + 1) to (record.obj("time").num("created") ?: 0.0)
            }
            // The stack pops the last entry first, so push newest first to visit oldest first.
            childNodes.sortedByDescending { it.second }.forEach { pending.addLast(it.first) }
        }
        return nodes
    }

    /** Never read this machine's sidecar for an external/unknown server connection. */
    private fun originalTimings(ids: Set<String>): Map<Pair<String, String>, JsonObject> {
        if (attachOnly || apiVersion() != 2 || ids.isEmpty()) return emptyMap()
        val archive = HistoryDatabase.sidecar(databasePath, "stats")
        if (!java.nio.file.Files.isRegularFile(archive)) return emptyMap()
        Class.forName("org.sqlite.JDBC")
        val config = org.sqlite.SQLiteConfig().apply { setReadOnly(true); setBusyTimeout(1_000) }
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${archive.toAbsolutePath()}", config.toProperties()).use { database ->
            OriginalUsageTiming.read(database).filterKeys { it.first in ids }
        }
    }

    private fun route(info: JsonObject) =
        "${info.str("providerID") ?: info.obj("model").str("providerID") ?: "unknown"}\u0000${info.str("modelID") ?: info.obj("model").str("id") ?: info.obj("model").str("modelID") ?: "unknown"}" +
            (info.str("source")?.let { "\u0000$it" } ?: "")

    private fun render(
        now: Long,
        windows: List<Window>,
        groups: List<Map<String, Total>>,
        sessionCount: Long,
    ): String = buildString {
        appendLine("# OpenCode Usage Report")
        appendLine()
        appendLine("Generated ${Instant.ofEpochMilli(now)}. Retained history across all projects, $sessionCount sessions scanned.")
        windows.forEachIndexed { index, window ->
            val title = window.title
            if (title == "Undated session usage" && groups[index].isEmpty()) return@forEachIndexed
            appendLine("\n## $title\n")
            if (groups[index].isEmpty()) {
                appendLine("_No token usage._")
                return@forEachIndexed
            }
            table(groups[index])
        }
    }

    private fun StringBuilder.table(groups: Map<String, Total>) {
        header("Provider", "Model")
        val total = Total()
        groups.entries.sortedWith(compareByDescending<Map.Entry<String, Total>> { it.value.prompts.size }
            .thenByDescending { it.value.total }.thenBy { it.key }).forEach { (route, usage) ->
            val fields = route.split('\u0000')
            val source = fields.getOrNull(2)
            val label = when (source) { "generate" -> "Session generation"; "title" -> "Session titles"; "compaction" -> "Compaction"; "unrecorded" -> "Other session usage"; else -> fields[1] }
            appendLine(usage.row(if (source == null) providerLabel(fields[0]) else "Unattributed", label, showCacheWrite))
            total.merge(usage)
        }
        appendLine(total.row("**Total**", "", showCacheWrite))
    }

    private fun StringBuilder.header(first: String, second: String) {
        val labels = listOf(first, second, "Prompts", "Total", "Duration", "Input", "Output", "Reasoning", "Cache read") +
            (if (showCacheWrite) listOf("Cache write") else emptyList()) + "Cache rate"
        appendLine(labels.joinToString(" | ", "| ", " |"))
        appendLine(labels.indices.joinToString(" | ", "| ", " |") { if (it < 2) "---" else "---:" })
    }

    private fun renderSession(now: Long, tree: List<TreeNode>, usage: List<Map<String, Total>>, warnings: List<String>): String = buildString {
        val root = tree.first()
        val nested = tree.size - 1
        fun sessions(count: Int) = if (count == 1) "session" else "sessions"
        val models = sortedMapOf<String, Total>()
        usage.forEach { routes -> routes.forEach { (route, total) -> models.getOrPut(route) { Total() }.merge(total) } }
        appendLine("# OpenCode Session Usage Report")
        appendLine()
        appendLine("Session: **${escapeMarkdown(root.title)}** (`${escapeMarkdown(root.id)}`)")
        appendLine()
        appendLine("Generated ${Instant.ofEpochMilli(now)} from ${tree.size} ${sessions(tree.size)} (main session and $nested nested ${sessions(nested)}).")
        appendLine("\n## By model\n")
        if (models.isEmpty()) appendLine("_No token usage._") else table(models)
        appendLine("\n## By session\n")
        header("Session", "Models")
        val all = Total()
        tree.forEachIndexed { index, node ->
            val session = Total()
            usage[index].values.forEach(session::merge)
            all.merge(session)
            val names = usage[index].keys.map { it.split('\u0000') }.filter { it.size == 2 }
                .map { "${providerLabel(it[0])}/${it[1]}" }.distinct()
            val indent = if (node.depth > 0) "&nbsp;&nbsp;".repeat(node.depth - 1) + "↳ " else ""
            appendLine(session.row(indent + node.title, names.joinToString(", ").ifEmpty { "-" }, showCacheWrite))
        }
        appendLine(all.row("**Total**", "", showCacheWrite))
        if (warnings.isNotEmpty()) {
            appendLine("\n## Warnings\n")
            warnings.distinct().forEach { appendLine("- ${escapeMarkdown(it)}") }
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
        /** Cache write is folded into Input unless [showCacheWrite] gives it a separate column. */
        fun row(provider: String, model: String, showCacheWrite: Boolean = false) = (listOf(
            escapeMarkdown(provider), escapeMarkdown(model), integer(prompts.size.toLong()), tokens(total), duration(),
            tokens(if (showCacheWrite) input else input + cacheWrite), tokens(output), tokens(reasoning), tokens(cacheRead),
        ) + (if (showCacheWrite) listOf(tokens(cacheWrite)) else emptyList()) + cacheRate()).joinToString(" | ", "| ", " |")

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
    }

    companion object {
        private val tokenNumberFormat = ThreadLocal.withInitial {
            java.text.NumberFormat.getCompactNumberInstance(Locale.US, java.text.NumberFormat.Style.SHORT).apply {
                maximumFractionDigits = 1
                roundingMode = java.math.RoundingMode.HALF_UP
            }
        }
        private const val DAY = 86_400_000L
        private const val MAX_API_SESSIONS = 250
        private const val SESSION_TREE_MAX_DEPTH = 64
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
        private fun escapeMarkdown(value: String) = value.replace("|", "\\|").replace(Regex("[\r\n]+"), " ")
        /** OpenCode's Claude subscription provider shows as plain Claude. */
        private fun providerLabel(providerID: String) = if (providerID == "claude-agent") "claude" else providerID
    }
}
