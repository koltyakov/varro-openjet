package varro.host.quota

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class OpenCodeV2QuotaCredentialsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `selection matches upstream active newest creation and descending id without database writes`() {
        val path = temporary.newFolder().toPath().resolve("opencode.db")
        writeQuotaCredentials(path, Json.array(listOf(
            Json.obj("id" to "old", "integrationID" to "xai", "active" to false, "created" to 1, "value" to Json.obj("type" to "oauth", "access" to "old-token")),
            Json.obj("id" to "active", "integrationID" to "xai", "active" to true, "created" to 2, "value" to Json.obj("type" to "oauth", "access" to "active-token")),
            Json.obj("id" to "newer", "integrationID" to "xai", "active" to false, "created" to 3, "value" to Json.obj("type" to "key", "key" to "inactive-key")),
            Json.obj("id" to "a", "integrationID" to "openrouter", "created" to 1, "value" to Json.obj("type" to "key", "key" to "older-key")),
            Json.obj("id" to "b", "integrationID" to "openrouter", "created" to 1, "value" to Json.obj("type" to "key", "key" to "newer-key")),
        )))
        val before = Files.readAllBytes(path)
        val auth = OpenCodeV2QuotaCredentials(path).read(emptyList())
        assertEquals("active-token", auth.obj("xai").str("access"))
        assertEquals("api", auth.obj("openrouter").str("type"))
        assertEquals("newer-key", auth.obj("openrouter").str("key"))
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `account metadata and provider integration aliases are preserved`() {
        val path = temporary.newFolder().toPath().resolve("opencode.db")
        for (metadata in listOf(Json.obj("accountID" to "current"), Json.obj("accountId" to "current"),
            Json.obj("accountID" to "current", "accountId" to "legacy"))) {
            writeQuotaCredentials(path, Json.array(listOf(Json.obj("active" to true, "integrationID" to "codex-integration",
                "value" to Json.obj("type" to "oauth", "access" to "fixture-token", "metadata" to metadata)))))
            val auth = OpenCodeV2QuotaCredentials(path).read(listOf(Json.obj("id" to "openai", "integrationID" to "codex-integration")))
            assertEquals("current", auth.obj("openai").str("accountId"))
            assertEquals("fixture-token", auth.obj("openai").str("access"))
        }
    }

    @Test fun `unrelated malformed credentials are excluded from a provider-scoped query`() {
        val path = temporary.newFolder().toPath().resolve("opencode.db")
        writeQuotaCredentials(path, Json.array(listOf(
            Json.obj("active" to true, "integrationID" to "zai", "value" to Json.obj("type" to "key", "key" to "fixture-key")),
            Json.obj("active" to true, "integrationID" to "unrelated", "rawValue" to "invalid JSON"),
        )))
        val auth = OpenCodeV2QuotaCredentials(path).read(listOf(Json.obj("id" to "zai")))
        assertEquals("fixture-key", auth.obj("zai").str("key"))
        assertFalse(auth.has("unrelated"))
    }

    @Test fun `newest inactive credential is used when no active credential exists like upstream`() {
        val path = temporary.newFolder().toPath().resolve("opencode.db")
        writeQuotaCredentials(path, Json.array(listOf(
            Json.obj("id" to "z", "active" to false, "created" to 1, "integrationID" to "openai",
                "value" to Json.obj("type" to "oauth", "access" to "older-token")),
            Json.obj("id" to "a", "active" to false, "created" to 2, "integrationID" to "openai",
                "value" to Json.obj("type" to "oauth", "access" to "newer-token")),
        )))
        val auth = OpenCodeV2QuotaCredentials(path).read(listOf(Json.obj("id" to "openai")))
        assertEquals("newer-token", auth.obj("openai").str("access"))
    }

    @Test fun `Zai and Gemini shared credentials remain available without unrelated integrations`() {
        val path = temporary.newFolder().toPath().resolve("opencode.db")
        for ((id, alias) in listOf("zai" to "zai-coding-plan", "zai-coding-plan" to "zai", "google" to "gemini", "gemini" to "google")) {
            writeQuotaCredentials(path, Json.array(listOf(
                Json.obj("active" to true, "integrationID" to alias, "value" to Json.obj("type" to "oauth", "access" to "alias-token")),
                Json.obj("active" to true, "integrationID" to "unrelated", "rawValue" to "invalid JSON"),
            )))
            val provider = Json.obj("id" to id)
            val auth = OpenCodeV2QuotaCredentials(path).read(listOf(provider))
            val creds = QuotaCredentials(temporary.newFolder().toPath(), emptyMap())
            assertEquals(id, "alias-token", if (id.startsWith("zai")) creds.token(provider, auth) else creds.gemini(id, auth))
            assertFalse(auth.has("unrelated"))
        }
    }

    @Test fun `database overrides and XDG data paths remain isolated`() {
        val home = temporary.newFolder().toPath()
        val data = temporary.newFolder().toPath()
        val override = temporary.newFolder().toPath().resolve("custom.db")
        assertEquals(home.resolve(".local/share/opencode/opencode.db"), QuotaCredentials(home, emptyMap()).v2DatabasePath)
        assertEquals(data.resolve("opencode/opencode.db"), QuotaCredentials(home, mapOf("XDG_DATA_HOME" to data.toString())).v2DatabasePath)
        assertEquals(override, QuotaCredentials(home, mapOf("XDG_DATA_HOME" to data.toString(), "OPENCODE_DB" to override.toString())).v2DatabasePath)
    }

    @Test fun `missing database is not created and schema failures never expose secrets`() {
        val path = temporary.newFolder().toPath().resolve("missing.db")
        assertThrows(QuotaFailure::class.java) { OpenCodeV2QuotaCredentials(path).read(emptyList()) }
        assertFalse(Files.exists(path))
        Files.writeString(path, "not a database with fixture-secret")
        val error = assertThrows(QuotaFailure::class.java) { OpenCodeV2QuotaCredentials(path).read(emptyList()) }
        assertEquals("Could not read OpenCode V2 provider credentials", error.message)
    }
}
