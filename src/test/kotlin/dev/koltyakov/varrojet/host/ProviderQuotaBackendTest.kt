package dev.koltyakov.varrojet.host

import com.google.gson.JsonObject
import dev.koltyakov.varrojet.protocol.Json
import dev.koltyakov.varrojet.protocol.int
import dev.koltyakov.varrojet.protocol.str
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderQuotaBackendTest {
    @get:Rule val temporary = TemporaryFolder()

    private val processes = CopyOnWriteArrayList<Process>()

    private fun launch(): Process {
        val home = temporary.newFolder()
        val environment = System.getenv().toMutableMap().apply {
            // Tests must never read the developer's actual provider credentials.
            put("HOME", home.absolutePath)
            put("USERPROFILE", home.absolutePath)
            put("XDG_DATA_HOME", home.absolutePath)
        }
        val paths = System.getenv().entries.firstOrNull { it.key.equals("PATH", true) }
            ?.value.orEmpty().split(File.pathSeparator)
        return ProviderQuotaRuntime.launch("", environment, paths).also(processes::add)
    }

    private fun metadata(remaining: Int = 75): JsonObject = Json.obj(
        "providers" to Json.array(listOf(Json.obj(
            "id" to "quota-test", "models" to JsonObject(),
            "quota" to Json.obj("requests" to Json.obj(
                "remaining" to remaining, "limit" to 100, "resetAt" to 2_000_000_000_000L,
            )),
        ))),
    )

    @Test(timeout = 20_000)
    fun `bundled service returns quota windows with model and workspace scope and caches polls`() {
        val reads = AtomicInteger()
        ProviderQuotaBackend(::launch, { method, path, _, directory ->
            assertEquals("GET", method)
            assertEquals("/config/providers", path)
            assertEquals("/workspace/one", directory)
            metadata(if (reads.incrementAndGet() == 1) 75 else 20)
        }).use { backend ->
            val first = backend.get("quota-test", "test-model", "/workspace/one")
            assertEquals("available", first.str("status"))
            assertEquals("test-model", first.str("modelID"))
            val window = first.getAsJsonArray("windows")[0].asJsonObject
            assertEquals(75, window.int("remaining"))
            assertEquals(100, window.int("limit"))
            assertEquals(2_000_000_000_000L, window.get("resetAt").asLong)
            assertEquals(first, backend.get("quota-test", "test-model", "/workspace/one"))
            assertEquals(1, reads.get())

            backend.clearCache()
            val refreshed = backend.get("quota-test", "test-model", "/workspace/one")
            assertEquals(20, refreshed.getAsJsonArray("windows")[0].asJsonObject.int("remaining"))
        }
        processes.forEach { assertTrue(it.waitFor(5, TimeUnit.SECONDS)) }
    }

    @Test(timeout = 20_000)
    fun `workspace caches stay separate and a crashed worker restarts`() {
        ProviderQuotaBackend(::launch, { _, _, _, directory ->
            metadata(if (directory == "/workspace/one") 75 else 25)
        }).use { backend ->
            val first = backend.get("quota-test", "one", "/workspace/one")
            val second = backend.get("quota-test", "two", "/workspace/two")
            assertEquals(75, first.getAsJsonArray("windows")[0].asJsonObject.int("remaining"))
            assertEquals(25, second.getAsJsonArray("windows")[0].asJsonObject.int("remaining"))
            processes.last().destroyForcibly().waitFor()
            assertEquals("available", backend.get("quota-test", "one", "/workspace/one").str("status"))
            assertEquals(2, processes.size)
        }
    }

    @Test(timeout = 20_000)
    fun `metadata failures return an error status and providers without quotas remain unsupported`() {
        var fail = true
        ProviderQuotaBackend(::launch, { _, path, _, _ ->
            if (fail) error("test transport failure")
            if (path == "/config/providers") Json.obj(
                "providers" to Json.array(listOf(Json.obj("id" to "quota-test", "models" to JsonObject()))),
            ) else JsonObject()
        }).use { backend ->
            assertEquals("error", backend.get("quota-test", null, null).str("status"))
            fail = false
            backend.clearCache()
            assertEquals("unsupported", backend.get("quota-test", null, null).str("status"))
        }
    }

    @Test
    fun `missing Node returns actionable status with startup backoff`() {
        val attempts = AtomicInteger()
        ProviderQuotaBackend({
            attempts.incrementAndGet()
            error("Provider quotas require Node.js 22 or newer")
        }, { _, _, _, _ -> error("Must not call OpenCode") }).use { backend ->
            repeat(2) {
                val result = backend.get("openai", "model", null)
                assertEquals("error", result.str("status"))
                assertTrue(result.str("note")!!.contains("Node.js 22"))
            }
            assertEquals(1, attempts.get())
            backend.clearCache()
            backend.get("openai", "model", null)
            assertEquals(2, attempts.get())
        }
    }

    @Test(timeout = 20_000)
    fun `disposal releases in-flight polls and prevents a new worker`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = ProviderQuotaBackend(::launch, { _, _, _, _ ->
            entered.countDown()
            release.await()
            metadata()
        })
        try {
            val poll = CompletableFuture.supplyAsync { backend.get("quota-test", null, null) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            backend.close()
            assertEquals("error", poll.get(5, TimeUnit.SECONDS).str("status"))
            assertTrue(processes.single().waitFor(5, TimeUnit.SECONDS))
            assertEquals("error", backend.get("quota-test", null, null).str("status"))
            assertEquals(1, processes.size)
        } finally {
            release.countDown()
            backend.close()
        }
    }

    @Test
    fun `helper transport only accepts quota metadata and auth persistence`() {
        assertTrue(ProviderQuotaBackend.isQuotaServerRequest("GET", "/config/providers"))
        assertTrue(ProviderQuotaBackend.isQuotaServerRequest("GET", "/experimental/console"))
        assertTrue(ProviderQuotaBackend.isQuotaServerRequest("PUT", "/auth/anthropic"))
        assertFalse(ProviderQuotaBackend.isQuotaServerRequest("POST", "/session"))
        assertFalse(ProviderQuotaBackend.isQuotaServerRequest("DELETE", "/auth/openai"))
        assertFalse(ProviderQuotaBackend.isQuotaServerRequest("GET", "https://example.com"))
    }
}
