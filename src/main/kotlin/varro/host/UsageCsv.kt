package varro.host

import varro.protocol.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

internal object UsageCsv {
    private data class Key(val day: String, val provider: String, val model: String, val variant: String)
    private class Group {
        val usage = UsageReport.Total()
        var messages = 0
        var cost = 0.0
        var costed = 0
    }
    data class Export(val csv: String, val rows: Int)

    fun read(path: Path, readAnnotations: (String) -> com.google.gson.JsonObject = varro.server.OpenCodeV2SessionState()::read,
        check: () -> Unit = {}): Export {
        val groups = linkedMapOf<Key, Group>()
        val count = LocalUsageDatabase(path, readAnnotations, boundedBodies = false).read(null, check) { session, info ->
            val timestamp = info.obj("time").long("completed") ?: info.obj("time").long("created") ?: error("Cannot export usage with a missing timestamp")
            require(timestamp >= 0) { "Cannot export usage with an invalid timestamp" }
            val provider = info.text("providerID") ?: info.obj("model").text("providerID") ?: error("Cannot export usage without provider identity")
            val model = info.text("modelID") ?: info.obj("model").text("id") ?: info.obj("model").text("modelID") ?: error("Cannot export usage without model identity")
            val usage = UsageReport.Total().apply { add(session, info) }
            val cost = info.num("cost")?.takeIf { it.isFinite() && it >= 0 }
            if (usage.total > 0 || cost != null) {
                val day = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString()
                val key = Key(day, provider, model, info.obj("model").text("variant") ?: info.text("variant") ?: "unknown")
                check(key in groups || groups.size < 100_000) { "Usage CSV exceeds the daily row limit. No partial export was saved." }
                groups.getOrPut(key) { Group() }.apply {
                    this.usage.merge(usage); messages++
                    if (cost != null) { this.cost += cost; costed++ }
                }
            }
        }
        check(count != null) { "No local OpenCode usage database or statistics archive was found" }
        val csv = StringBuilder("\uFEFFday_utc,provider,model,reasoning_setting,messages,input_tokens,output_tokens,reasoning_tokens,cache_read_tokens,cache_write_tokens,total_tokens,reported_cost_usd,costed_messages\r\n")
        var bytes = csv.toString().toByteArray().size.toLong()
        groups.entries.sortedWith(compareBy({ it.key.day }, { it.key.provider }, { it.key.model }, { it.key.variant })).forEach { (key, group) ->
            check()
            val usage = group.usage
            val line = listOf(key.day, key.provider, key.model, key.variant, group.messages, usage.input, usage.output, usage.reasoning,
                usage.cacheRead, usage.cacheWrite, usage.total, if (group.costed == 0) "" else java.math.BigDecimal.valueOf(group.cost).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString(), group.costed)
                .joinToString(",") { cell(it.toString()) } + "\r\n"
            bytes += line.toByteArray().size
            check(bytes <= 64L * 1024 * 1024) { "Usage CSV exceeds the 64 MB export limit. No partial export was saved." }
            csv.append(line)
        }
        return Export(csv.toString(), groups.size)
    }

    fun validateDestination(destination: Path, database: Path) {
        require(destination.fileName.toString().endsWith(".csv", ignoreCase = true)) { "Choose a .csv destination" }
        require(!Files.isSymbolicLink(destination)) { "CSV destination must not be a symlink" }
        if (Files.exists(destination, NOFOLLOW_LINKS)) HistoryDatabase.validateFile(destination)
        val protected = listOf(database, HistoryDatabase.sidecar(database, "stats"), HistoryDatabase.sidecar(database, "transcripts"))
            .flatMap { base -> listOf(base, Path.of("$base-wal"), Path.of("$base-shm"), Path.of("$base-journal")) }
        require(protected.none { target -> destination.toAbsolutePath().normalize() == target.toAbsolutePath().normalize() ||
            Files.exists(target) && Files.exists(destination) && Files.isSameFile(target, destination) }) { "CSV export must not overwrite OpenCode databases or sidecars" }
    }

    fun write(destination: Path, database: Path, export: Export, check: () -> Unit = {}) {
        validateDestination(destination, database)
        check()
        val staged = Files.createTempFile(destination.toAbsolutePath().parent, ".opencode-usage-", ".csv")
        try {
            java.nio.channels.FileChannel.open(staged, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(export.csv.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) { check(); channel.write(bytes) }
                channel.force(true)
            }
            check()
            validateDestination(destination, database)
            Files.move(staged, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staged) }
    }

    private fun cell(value: String): String {
        val text = if (Regex("^[\\u0000-\\u0020]*[=+@-]").containsMatchIn(value)) "'$value" else value
        return if (text.any { it in ",\"\r\n" }) "\"${text.replace("\"", "\"\"")}\"" else text
    }
}
