package varro.host.quota

import com.google.gson.JsonObject
import org.sqlite.SQLiteConfig
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** Matches Varro's V2 credential selection without creating or modifying the database. */
internal class OpenCodeV2QuotaCredentials(private val path: Path) {
    fun read(providers: List<JsonObject>): JsonObject {
        if (!Files.isRegularFile(path)) throw QuotaFailure("OpenCode V2 credential database is unavailable")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        fun checkProgress() {
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)
                throw QuotaFailure("Timed out reading OpenCode V2 credentials")
        }
        val integrations = providers.flatMap { provider ->
            val id = provider.text("id") ?: return@flatMap emptyList()
            listOf(provider.text("integrationID") ?: id) + when (id) {
                "google", "gemini" -> listOf("google", "gemini")
                "zai", "zai-coding-plan" -> listOf("zai", "zai-coding-plan")
                else -> emptyList()
            }
        }.distinct()
        val config = SQLiteConfig().apply { setReadOnly(true); setBusyTimeout(1_000) }
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties()).use { database ->
                org.sqlite.ProgressHandler.setHandler(database, 100, object : org.sqlite.ProgressHandler() {
                    override fun progress(): Int { checkProgress(); return 0 }
                })
                database.autoCommit = false
                val filter = if (integrations.isEmpty()) "" else " AND integration_id IN (${integrations.joinToString(",") { "?" }})"
                val query = "SELECT integration_id,value FROM credential WHERE integration_id IS NOT NULL$filter " +
                    "ORDER BY active DESC,time_created DESC,id DESC LIMIT 1025"
                val auth = JsonObject()
                database.prepareStatement(query).use { statement ->
                    integrations.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                    statement.executeQuery().use { rows ->
                        val selected = mutableSetOf<String>()
                        var scanned = 0
                        while (rows.next()) {
                            checkProgress()
                            if (++scanned > 1024) throw QuotaFailure("OpenCode V2 credential query exceeded the safety limit")
                            val id = rows.getString(1)
                            if (!selected.add(id)) continue
                            val raw = rows.getString(2)
                            if (raw == null || raw.length > 1024 * 1024) throw QuotaFailure("OpenCode V2 returned invalid provider credentials")
                            val value = Json.parseOrNull(raw).asObjectOrNull()
                                ?: throw QuotaFailure("OpenCode V2 returned invalid provider credentials")
                            when (value.str("type")) {
                                "key" -> auth.add(id, Json.obj("type" to "api", "key" to (value.text("key")
                                    ?: throw QuotaFailure("OpenCode V2 returned an invalid provider API key"))))
                                "oauth" -> {
                                    if (value.text("access") == null) throw QuotaFailure("OpenCode V2 returned an invalid provider OAuth token")
                                    auth.add(id, value.deepCopy().apply {
                                        val metadata = value.obj("metadata")
                                        (metadata.text("accountID") ?: metadata.text("accountId") ?: value.text("accountId"))?.let { addProperty("accountId", it) }
                                    })
                                }
                            }
                        }
                    }
                }
                providers.forEach { provider ->
                    val id = provider.text("id") ?: return@forEach
                    auth.obj(provider.text("integrationID") ?: id)?.let { auth.add(id, it.deepCopy()) }
                }
                database.rollback()
                checkProgress()
                return auth
            }
        } catch (failure: QuotaFailure) { throw failure }
        catch (_: Exception) { throw QuotaFailure("Could not read OpenCode V2 provider credentials") }
    }
}
