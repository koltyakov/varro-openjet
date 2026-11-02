package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CitationSymbolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.root.toPath() }
    private fun request(vararg symbols: String) = Json.obj("symbols" to symbols.toList())

    @Test fun `one exact indexed definition links and duplicates share cached lookup`() {
        val file = Files.writeString(root.resolve("loss.py"), "text")
        val calls = AtomicInteger()
        CitationSymbols({ name, _ -> calls.incrementAndGet(); listOf(CitationSymbols.Definition(name, file, 11)) }).use { service ->
            val result = service.find(request("loss_weights()", "loss_weights()", "plain text"), root.toString(), { _, _ -> true })
            assertEquals(Json.obj("path" to file.toString(), "line" to 12), result[0])
            assertEquals(result[0], result[1])
            assertTrue(result[2].isJsonNull)
            assertEquals(1, calls.get())
            service.find(request("loss_weights()"), root.toString(), { _, _ -> true })
            assertEquals(1, calls.get())
        }
    }

    @Test fun `ambiguous missing fuzzy and outside symbols remain inert`() {
        val file = Files.writeString(root.resolve("file"), "text")
        val exact = CitationSymbols.Definition("some_call", file, 0)
        for (values in listOf(emptyList(), listOf(exact, exact.copy(character = 1)), listOf(exact.copy(name = "some_call_extra")),
            listOf(exact.copy(path = root.parent.resolve("other"))), listOf(exact.copy(line = -1)), listOf(exact.copy(path = root.resolve("missing"))),
            List(1001) { exact })) {
            CitationSymbols({ _, _ -> values }).use { service ->
                assertTrue(service.find(request("some_call()"), root.toString(), { _, _ -> true })[0].isJsonNull)
            }
        }
        CitationSymbols({ _, _ -> listOf(exact, exact) }).use { service ->
            assertFalse(service.find(request("some_call()"), root.toString(), { _, _ -> true })[0].isJsonNull)
        }
    }

    @Test fun `scope authorization and strict validation happen before index calls`() {
        CitationSymbols({ _, _ -> error("Must not look up") }).use { service ->
            assertThrows(IllegalArgumentException::class.java) { service.find(request("f()"), root.toString(), { _, _ -> false }) }
            for (invalid in listOf(Json.obj("symbols" to listOf(1)), Json.obj("symbols" to List(33) { "f()" }), request("x".repeat(129)), request(""),
                request("f()").apply { addProperty("sessionID", "../bad") }, request("f()").apply { addProperty("directory", 5) })) {
                assertThrows(IllegalArgumentException::class.java) { service.find(invalid, root.toString(), { _, _ -> error("Must not authorize") }) }
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) { service.find(request("f()"), root.toString(), { _, _ -> true }, { true }) }
        }
        assertEquals("loss_weights", CitationSymbols.parse("loss_weights()"))
        assertEquals("CustomMultiTaskRLBenchEnv", CitationSymbols.parse("CustomMultiTaskRLBenchEnv"))
        listOf("plain", "transporter / franka / clip", "obj.method()", "file.ts", "Class", "f(x)").forEach { assertNull(CitationSymbols.parse(it)) }
        assertTrue(ApiRoutes.isAllowed("POST", ApiRoutes.Endpoints.CITATION_SYMBOLS))
        assertFalse(ApiRoutes.isAllowed("GET", ApiRoutes.Endpoints.CITATION_SYMBOLS))
        assertFalse(ApiRoutes.isAllowed("POST", "${ApiRoutes.Endpoints.CITATION_SYMBOLS}?directory=/repo"))
    }

    @Test fun `slow providers return pending then reuse late results with bounded outstanding calls`() {
        val file = Files.writeString(root.resolve("file"), "text")
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        val count = AtomicInteger()
        CitationSymbols({ name, _ -> count.incrementAndGet(); started.countDown(); release.await(); listOf(CitationSymbols.Definition(name, file, 0)) }, budgetMs = 10).use { service ->
            val pending = service.find(request("f()", "g()", "h()"), root.toString(), { _, _ -> true })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(pending.all { it.asString == "pending" })
            repeat(4) { service.find(request("f()", "g()", "h()"), root.toString(), { _, _ -> true }) }
            assertEquals(2, count.get())
            release.countDown()
            val result = service.find(request("f()", "g()"), root.toString(), { _, _ -> true })
            assertTrue(result.all { it.isJsonObject })
            assertEquals(2, count.get())
        }
    }

    @Test fun `expired calls are capped and open the breaker instead of starting unlimited providers`() {
        var now = 0L
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val started = CountDownLatch(4)
        CitationSymbols({ _, _ -> count.incrementAndGet(); started.countDown(); release.await(); emptyList() }, { now }, 1).use { service ->
            service.find(request("a()", "b()"), root.toString(), { _, _ -> true })
            now = 20_000
            service.find(request("c()", "d()"), root.toString(), { _, _ -> true })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            now = 40_000
            repeat(10) { assertTrue(service.find(request("e()"), root.toString(), { _, _ -> true })[0].isJsonNull) }
            assertEquals(4, count.get())
            release.countDown()
        }
    }

    @Test fun `project scope and independent positive negative TTLs prevent stale links`() {
        var now = 0L
        val file = Files.writeString(root.resolve("file"), "text")
        val child = Files.createDirectories(root.resolve("child"))
        val calls = AtomicInteger()
        CitationSymbols({ name, _ -> calls.incrementAndGet(); listOf(CitationSymbols.Definition(name, file, 0)) }, { now }).use { service ->
            assertFalse(service.find(request("f()"), root.toString(), { _, _ -> true })[0].isJsonNull)
            assertTrue(service.find(request("f()"), child.toString(), { _, _ -> true })[0].isJsonNull)
            now = 1_000
            service.find(request("f()"), child.toString(), { _, _ -> true })
            service.find(request("f()"), root.toString(), { _, _ -> true })
            assertEquals(3, calls.get())
            now = 15_000
            service.find(request("f()"), root.toString(), { _, _ -> true })
            assertEquals(4, calls.get())
        }
    }
}
