package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.arr
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.long
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.str
import varro.protocol.strings

/** Port of Varro's session-summary.ts. Token totals exclude cache reads. */
internal object SessionSummary {
    data class History(val messages: List<JsonObject>, val descendants: List<Descendant> = emptyList())
    data class Descendant(val tokens: JsonObject?, val messages: List<JsonObject>)

    private val fileKeys = listOf("relativePath", "file", "path", "filePath", "filepath", "filename")
    private val changeTools = setOf(
        "apply_patch", "edit", "write", "create", "file_edit", "file_write", "file_create",
        "update_file", "replace", "insert", "apply_edit", "apply_diff", "delete", "remove",
        "unlink", "rm", "file_delete", "file_remove", "move", "mv", "rename", "file_move", "file_rename",
    )
    private val generated = Regex("(?:^|/)(?:node_modules|\\.venv|venv|\\.tox|__pycache__)(?:/|$)")

    fun summarize(history: History, diffs: JsonElement? = null): JsonObject {
        val remoteEdits = summarizeDiffs(diffRecords(diffs))
        val edits = if (listOf("files", "additions", "deletions").any { remoteEdits.long(it) != 0L }) {
            remoteEdits
        } else messageEdits(history.messages)
        val session = messageTokens(history.messages)
        val subagents = emptyUsage()
        history.descendants.forEach { descendant ->
            val snapshot = tokenUsage(descendant.tokens)
            addUsage(subagents, if (snapshot.getValue("total") > 0) snapshot else messageTokens(descendant.messages))
        }
        edits.addProperty("tokens", spent(session) + spent(subagents))
        edits.add("tokenBreakdown", Json.obj(
            "session" to session, "subagents" to subagents, "subagentCount" to history.descendants.size,
        ))
        duration(history.messages).entrySet().forEach { (key, value) -> edits.add(key, value) }
        history.messages.mapNotNull { it.obj("info") }.lastOrNull {
            it.str("role") == "assistant" && it.str("mode") != "subagent" &&
                it.str("providerID") != null && it.str("modelID") != null
        }?.let { info ->
            edits.add("model", Json.obj("providerID" to info.str("providerID"), "modelID" to info.str("modelID")).apply {
                info.str("variant")?.takeIf { it.isNotEmpty() }?.let { addProperty("variant", it) }
            })
        }
        if (history.messages.any { it.obj("info").obj("summary").bool("diffsOmitted") == true }) {
            edits.addProperty("historyStatsUnavailable", true)
            edits.remove("tokenBreakdown")
        }
        return edits
    }

    private fun diffRecords(value: JsonElement?): List<JsonObject> = when {
        value == null -> emptyList()
        value.isJsonArray -> value.asJsonArray.mapNotNull { it.asObjectOrNull() }
        value.isJsonObject -> value.asJsonObject.let { record ->
            when {
                isDiff(record) -> listOf(record)
                record.arr("diffs") != null -> diffRecords(record.get("diffs"))
                else -> record.entrySet().mapNotNull { it.value.asObjectOrNull() }
            }
        }
        else -> emptyList()
    }

    private fun count(record: JsonObject?, key: String): Long? = record.num(key)
        ?.takeIf { it.isFinite() && it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1 == 0.0 }?.toLong()

    private fun isDiff(record: JsonObject) = record.str("file") != null ||
        listOf("additions", "deletions", "added", "removed").any { count(record, it) != null }

    private fun summarizeDiffs(diffs: List<JsonObject>): JsonObject {
        val relative = mutableSetOf<String>()
        val absolute = mutableSetOf<String>()
        val suffixes = mutableSetOf<String>()
        var files = 0
        var valid = 0
        var additions = 0L
        var deletions = 0L
        for (diff in diffs) {
            if (!isDiff(diff)) continue
            val file = diff.str("file")?.replace('\\', '/')?.removePrefix("./")
            if (file != null && generated.containsMatchIn(file)) continue
            valid++
            if (!file.isNullOrEmpty()) {
                val isAbsolute = file.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(file)
                val fileSuffixes = file.indices.filter { file[it] == '/' }.map { file.substring(it + 1) }.filter { it.isNotEmpty() }
                val duplicate = if (isAbsolute) file in absolute || fileSuffixes.any { it in relative }
                    else file in relative || file in suffixes
                if (!duplicate) {
                    files++
                    if (isAbsolute) { absolute.add(file); suffixes.addAll(fileSuffixes) } else relative.add(file)
                }
            }
            additions += count(diff, "additions") ?: count(diff, "added") ?: 0
            deletions += count(diff, "deletions") ?: count(diff, "removed") ?: 0
        }
        return Json.obj("files" to (if (files > 0) files else valid), "additions" to additions, "deletions" to deletions)
    }

    private fun messageEdits(messages: List<JsonObject>): JsonObject {
        val diffs = mutableListOf<JsonObject>()
        var truncated = false
        for (message in messages) {
            val summary = message.obj("info").obj("summary")
            truncated = truncated || summary.bool("diffsOmitted") == true || summary.bool("diffsTruncated") == true
            diffs.addAll(diffRecords(summary?.get("diffs")))
            for (part in message.arr("parts")?.mapNotNull { it.asObjectOrNull() }.orEmpty()) {
                if (part.str("type") == "patch") {
                    part.arr("files")?.strings()?.filter { it.isNotEmpty() }?.forEach { diffs.add(Json.obj("file" to it)) }
                    continue
                }
                if (part.str("type") != "tool" || part.str("tool") == null) continue
                val state = part.obj("state")
                val metadata = state.obj("metadata")
                val metadataFiles = metadata.arr("files")
                if (metadataFiles != null) {
                    metadataFiles.mapNotNull { it.asObjectOrNull() }.forEach { diff ->
                        fileKeys.take(4).firstNotNullOfOrNull { diff.str(it)?.takeIf(String::isNotEmpty) }?.let {
                            diffs.add(diff.deepCopy().apply { addProperty("file", it) })
                        }
                    }
                    continue
                }
                if (part.str("tool")!!.trim().lowercase().substringAfterLast('.') !in changeTools) continue
                val source = metadata?.deepCopy() ?: JsonObject()
                state.obj("input")?.entrySet()?.forEach { (key, value) -> source.add(key, value) }
                val file = fileKeys.firstNotNullOfOrNull { source.str(it)?.takeIf(String::isNotEmpty) } ?: continue
                diffs.add(Json.obj("file" to file,
                    "additions" to (source.get("additions")?.takeUnless { it.isJsonNull } ?: source.get("linesAdded")),
                    "deletions" to (source.get("deletions")?.takeUnless { it.isJsonNull } ?: source.get("linesRemoved"))))
            }
        }
        return summarizeDiffs(diffs).apply { if (truncated && long("files") == 0L) addProperty("filesTruncated", true) }
    }

    private fun emptyUsage() = linkedMapOf("total" to 0L, "input" to 0L, "output" to 0L,
        "reasoning" to 0L, "cacheRead" to 0L, "cacheWrite" to 0L)

    private fun tokenUsage(tokens: JsonObject?): MutableMap<String, Long> = emptyUsage().apply {
        for (key in listOf("input", "output", "reasoning")) this[key] = count(tokens, key) ?: 0
        this["cacheRead"] = count(tokens.obj("cache"), "read") ?: 0
        this["cacheWrite"] = count(tokens.obj("cache"), "write") ?: 0
        this["total"] = count(tokens, "total")?.takeIf { it > 0 } ?: values.sum()
    }

    private fun messageTokens(messages: List<JsonObject>) = emptyUsage().apply {
        messages.mapNotNull { it.obj("info") }.filter { it.str("role") == "assistant" }
            .forEach { addUsage(this, tokenUsage(it.obj("tokens"))) }
    }

    private fun addUsage(target: MutableMap<String, Long>, source: Map<String, Long>) {
        source.forEach { (key, value) -> target[key] = target.getValue(key) + value }
    }

    private fun spent(usage: Map<String, Long>) = (usage.getValue("total") - usage.getValue("cacheRead")).coerceAtLeast(0)

    private fun duration(messages: List<JsonObject>): JsonObject {
        var total = 0L
        var prompt: Long? = null
        var firstAssistant: Long? = null
        var completed: Long? = null
        var lastCompleted = false
        fun flush() {
            val start = prompt ?: firstAssistant
            if (lastCompleted && completed != null && start != null) total += (completed!! - start).coerceAtLeast(0)
            prompt = null; firstAssistant = null; completed = null; lastCompleted = false
        }
        for (message in messages) {
            val info = message.obj("info")
            if (info.str("role") != "assistant") {
                flush()
                if (info.str("role") == "user") prompt = info.obj("time").long("created")
                continue
            }
            if (info.str("mode") == "subagent") continue
            if (firstAssistant == null) firstAssistant = info.obj("time").long("created")
            val end = info.obj("time").long("completed")
            lastCompleted = end != null
            if (end != null) completed = maxOf(completed ?: end, end)
        }
        val active = if (lastCompleted) null else prompt ?: firstAssistant
        flush()
        return Json.obj("durationMs" to total, "activeStartedAt" to active)
    }
}
