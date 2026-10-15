package varro.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Kotlin uses ordinary directories, not upstream's asynchronous Windows junction preparation. */
class OpenCodeV2SessionStateTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test(timeout = 10_000) fun `concurrent annotation writers share a newly created directory without losing updates`() {
        val directory = temporary.root.toPath().resolve("annotations")
        val writers = List(8) { OpenCodeV2SessionState(directory) }
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val updates = (0 until 24).map { index -> executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                writers[index % writers.size].update("ses_fixture", Json.obj("writer_$index" to true))
            } }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            updates.forEach { it.get(5, TimeUnit.SECONDS) }
            val saved = OpenCodeV2SessionState(directory).read("ses_fixture")
            repeat(24) { assertEquals(true, saved.bool("writer_$it")) }
            assertFalse(Files.exists(directory.resolve("ses_fixture.json.lock")))
            assertEquals(listOf("ses_fixture.json"), Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() })
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun `completed writes do not hide a later directory conflict and failure permits a fresh attempt`() {
        val directory = temporary.root.toPath().resolve("annotations")
        val state = OpenCodeV2SessionState(directory)
        state.update("ses_fixture", Json.obj("before" to true))
        state.remove("ses_fixture")
        Files.delete(directory)
        Files.writeString(directory, "fixture conflict")
        assertThrows(java.io.IOException::class.java) { state.update("ses_fixture", Json.obj("blocked" to true)) }
        assertEquals("fixture conflict", Files.readString(directory))
        Files.delete(directory)
        state.update("ses_fixture", Json.obj("retried" to true))
        val saved = state.read("ses_fixture")
        assertEquals(true, saved.bool("retried"))
        assertFalse(saved.has("blocked"))
    }
}
