package varro.host.quota

import com.google.gson.JsonObject
import varro.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Credentials never leave the host except in requests to their provider. */
class QuotaCredentials(
    val home: Path,
    val environment: Map<String, String>,
    private val readClaudeKeychain: () -> String? = ::readClaudeKeychain,
) {
    val authPath: Path = Path.of(environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
        ?: home.resolve(".local/share").toString()).resolve("opencode/auth.json")
    val claudePath: Path = home.resolve(".claude/.credentials.json")

    fun read(path: Path): JsonObject? {
        if (!Files.isRegularFile(path)) return null
        return try {
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(1024 * 1024 + 1)
                if (bytes.size > 1024 * 1024) throw QuotaFailure("Provider credential file is too large")
                Json.parseOrNull(bytes.toString(Charsets.UTF_8)).asObjectOrNull()
                    ?: throw QuotaFailure("Provider credential file contains invalid JSON")
            }
        } catch (failure: QuotaFailure) { throw failure }
        catch (_: Exception) { throw QuotaFailure("Cannot read the provider credential file") }
    }

    fun authStore(): JsonObject = read(authPath) ?: JsonObject()

    /** Claude Agent uses Claude Code's account, never OpenCode's Anthropic credentials. */
    fun claudeAgentToken(): String? {
        environment["CLAUDE_CODE_OAUTH_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { Json.parseOrNull(readClaudeKeychain().orEmpty()).asObjectOrNull().obj("claudeAiOauth").string("accessToken") }
            .getOrNull()?.let { return it }
        // Missing, inaccessible or malformed Claude credentials are a signed-out state.
        return runCatching { read(claudePath).obj("claudeAiOauth").string("accessToken") }.getOrNull()
    }

    val v2DatabasePath: Path get() = environment["OPENCODE_DB"]?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of)
        ?: authPath.resolveSibling("opencode.db")

    internal fun v2AuthStore(provider: JsonObject): JsonObject = OpenCodeV2QuotaCredentials(v2DatabasePath).read(listOf(provider))

    fun token(provider: JsonObject, auth: JsonObject): String? {
        val id = provider.str("id") ?: return null
        val stored = auth.obj(id) ?: if (id in setOf("zai", "zai-coding-plan"))
            auth.obj(if (id == "zai") "zai-coding-plan" else "zai") else null
        val value = if (stored.str("type") == "oauth") stored.string("access") else stored.string("key")
        return value ?: provider.obj("options").string("apiKey")?.takeUnless { it == OAUTH_DUMMY }
            ?: provider.elements("env").firstNotNullOfOrNull { name ->
                name.takeIf { it.isJsonPrimitive }?.asString?.let { environment[it]?.takeIf(String::isNotBlank) }
            }
    }

    fun codex(auth: JsonObject): JsonObject? {
        auth.obj("openai")?.takeIf { it.str("type") == "oauth" && it.string("access") != null }?.let { return it }
        val file = read(Path.of(environment["CODEX_HOME"] ?: home.resolve(".codex").toString()).resolve("auth.json"))
        val tokens = file.obj("tokens")
        val access = tokens.string("access_token", "accessToken") ?: environment["CODEX_TOKEN"] ?: return null
        return Json.obj("access" to access, "accountId" to tokens.string("account_id", "accountId"))
    }

    fun gemini(providerId: String, auth: JsonObject): String? {
        listOf(providerId, "google", "gemini").distinct().forEach { id ->
            auth.obj(id)?.takeIf { it.str("type") == "oauth" }?.string("access")?.let { return it }
        }
        return read(Path.of(environment["GEMINI_HOME"] ?: home.resolve(".gemini").toString()).resolve("oauth_creds.json"))
            .string("access_token", "accessToken") ?: environment["GEMINI_ACCESS_TOKEN"]
    }

    fun copilotFallback(): String? {
        val candidates = listOfNotNull(
            environment["GH_CONFIG_DIR"]?.let { Path.of(it, "hosts.yml") },
            environment["XDG_CONFIG_HOME"]?.let { Path.of(it, "gh", "hosts.yml") },
            environment["APPDATA"]?.let { Path.of(it, "GitHub CLI", "hosts.yml") },
            home.resolve(".config/gh/hosts.yml"),
        )
        for (path in candidates.distinct()) {
            if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024) continue
            var inGithub = false
            for (line in Files.readAllLines(path)) {
                if (line.isNotBlank() && !line.first().isWhitespace()) inGithub = line.trim() == "github.com:"
                if (inGithub && line.trim().startsWith("oauth_token:")) {
                    return line.substringAfter("oauth_token:").trim().trim('"', '\'').takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    /** Compare before replacing a rotated Claude token; keep unrelated credential fields. */
    fun updateClaude(expectedRefresh: String, token: JsonObject, now: Long) {
        synchronized(CLAUDE_WRITE_LOCK) {
            FileChannel.open(claudePath.resolveSibling(".varro-quota.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val current = read(claudePath) ?: throw QuotaFailure("Claude credentials disappeared during refresh")
                    val oauth = current.obj("claudeAiOauth") ?: throw QuotaFailure("Claude OAuth credentials are missing")
                    if (oauth.string("refreshToken") != expectedRefresh) throw QuotaFailure("Claude credentials changed during refresh; polling again will use the new credentials")
                    oauth.addProperty("accessToken", token.string("access_token") ?: throw QuotaFailure("OAuth refresh returned no access token"))
                    oauth.addProperty("refreshToken", token.string("refresh_token") ?: expectedRefresh)
                    oauth.addProperty("expiresAt", now + ((token.number("expires_in") ?: 3600.0) * 1000).toLong())
                    val temp = Files.createTempFile(claudePath.parent, ".varro-credentials-", ".json")
                    try {
                        runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(claudePath)) }
                        Files.writeString(temp, Json.stringify(current))
                        Files.move(temp, claudePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temp) }
                }
            }
        }
    }

    companion object {
        const val OAUTH_DUMMY = "opencode-oauth-dummy-key"
        private val CLAUDE_WRITE_LOCK = Any()

        /** Read-only lookup, with no shell and bounded time/output. Never log credential output. */
        private fun readClaudeKeychain(): String? {
            if (!System.getProperty("os.name").lowercase().contains("mac")) return null
            val process = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", "Claude Code-credentials", "-w")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            val output = CompletableFuture.supplyAsync {
                process.inputStream.use { stream ->
                    val bytes = stream.readNBytes(256 * 1024 + 1)
                    bytes.takeIf { it.size <= 256 * 1024 }?.toString(Charsets.UTF_8)
                }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null
                val remaining = deadline - System.nanoTime()
                return if (remaining > 0) output.get(remaining, TimeUnit.NANOSECONDS) else null
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                output.cancel(true)
            }
        }

        internal fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
