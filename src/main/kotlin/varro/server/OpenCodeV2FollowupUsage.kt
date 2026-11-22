package varro.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import varro.protocol.*

internal object OpenCodeV2FollowupUsage {
    const val MAX_BYTES = 8L * 1024 * 1024

    fun read(readInbox: () -> JsonArray, readPage: (String?) -> JsonObject, maxBytes: Long = MAX_BYTES, maxPages: Int = 10): JsonObject {
        val inbox = readInbox()
        val pages = mutableListOf<List<JsonObject>>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var bytes = 0L
        var complete = false
        while (pages.size < maxPages) {
            val page = try { readPage(cursor) } catch (_: OpenCodeResponseTooLargeException) { break }
            val records = page.arr("data") ?: error("Invalid OpenCode v2 message page")
            if (records.isEmpty) { complete = true; break }
            bytes += Json.stringify(records).toByteArray(Charsets.UTF_8).size
            if (bytes > maxBytes) break
            pages += records.map { it.asObjectOrNull() ?: error("Invalid OpenCode v2 message record") }
            cursor = page.obj("cursor").text("next")
            if (cursor == null) { complete = true; break }
            check(seen.add(cursor)) { "OpenCode repeated a message pagination cursor" }
        }
        return summarize(pages.flatten().asReversed(), complete, inbox.mapNotNull {
            it.asObjectOrNull().takeIf { item -> item.str("type") == "user" }.text("id")
        })
    }

    fun summarize(records: List<JsonObject>, complete: Boolean, pendingPromptIDs: List<String>): JsonObject {
        val intervals = mutableListOf<JsonObject>()
        val counted = mutableSetOf<String>()
        var current: JsonObject? = null
        var stepped = false
        var leadingStep = false
        for (record in records) {
            if (OpenCodeV2Projection.userPrompt(record)) {
                val id = record.text("id") ?: error("Invalid OpenCode v2 prompt identity")
                if (current != null && !stepped) { current.arr("promptIDs")!!.add(id); continue }
                current?.addProperty("final", true)
                current = Json.obj("promptIDs" to listOf(id), "usage" to Json.obj(
                    "input" to 0, "output" to 0, "reasoning" to 0, "cacheRead" to 0, "cacheWrite" to 0,
                    "cost" to 0, "steps" to 0, "missing" to 0, "active" to 0), "final" to false)
                if (!complete && intervals.isEmpty() && !leadingStep) current.addProperty("boundaryUnknown", true)
                intervals += current
                stepped = false
                continue
            }
            if (record.str("type") == "idle") {
                intervals.forEach {
                    val usage = it.obj("usage")!!
                    usage.addProperty("missing", usage.int("missing")!! + usage.int("active")!!)
                    usage.addProperty("active", 0)
                }
                continue
            }
            if (record.str("type") != "assistant") continue
            if (current == null) { leadingStep = true; continue }
            stepped = true
            if (!counted.add(record.text("id") ?: error("Invalid OpenCode v2 step identity"))) continue
            val usage = current.obj("usage")!!
            fun add(key: String, value: Double) = usage.addProperty(key, usage.num(key)!! + value)
            fun finite(value: Double?) = value?.takeIf { it.isFinite() } ?: 0.0
            val tokens = record.obj("tokens")
            if (tokens != null) {
                for (key in listOf("input", "output", "reasoning")) add(key, finite(tokens.num(key)))
                add("cacheRead", finite(tokens.obj("cache").num("read")))
                add("cacheWrite", finite(tokens.obj("cache").num("write")))
                add("cost", finite(record.num("cost")))
                add("steps", 1.0)
            } else add(if (record.obj("time").hasNonNull("completed") || record.hasNonNull("error")) "missing" else "active", 1.0)
        }
        return Json.obj("supported" to true, "complete" to complete, "intervals" to intervals, "pendingPromptIDs" to pendingPromptIDs)
    }
}
