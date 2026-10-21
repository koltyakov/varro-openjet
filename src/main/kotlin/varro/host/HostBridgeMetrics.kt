package varro.host

import varro.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in timings from the real browser bridge. Never records payloads, paths or chat content. */
internal class HostBridgeMetrics(
    private val directory: Path,
    private val surface: String,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val file = directory.resolve("bridge-${UUID.randomUUID()}.json")
    private var started = clock()
    private var loads = 0
    private var nextId = 0L
    private var batches = 0L
    private var characters = 0L
    private var acknowledged = 0L
    private var peakViews = active.incrementAndGet()
    private var closed = false
    private val pending = LinkedHashMap<Long, Long>()
    private val roundTrips = ArrayDeque<Double>()
    private val startup = ArrayDeque<Double>()

    @Synchronized fun loading() {
        if (loads++ > 0) started = clock()
        pending.clear()
    }

    @Synchronized fun ready() {
        addSample(startup, (clock() - started) / 1_000_000.0)
        save()
    }

    @Synchronized fun sent(size: Int): Long {
        val id = ++nextId
        batches++
        characters += size
        peakViews = maxOf(peakViews, active.get())
        if (pending.size >= 4096) pending.remove(pending.keys.first())
        pending[id] = clock()
        return id
    }

    @Synchronized fun acknowledge(id: Long) {
        val sent = pending.remove(id) ?: return
        addSample(roundTrips, (clock() - sent) / 1_000_000.0)
        acknowledged++
        if (acknowledged % 128L == 0L) save()
    }

    @Synchronized internal fun snapshot() = Json.obj(
        "protocol" to "openjet-native-bridge-v1", "surface" to surface,
        "loads" to loads, "peakViews" to peakViews, "batches" to batches,
        "characters" to characters, "acknowledged" to acknowledged, "pending" to pending.size,
        "startupMs" to startup.toList(), "roundTripMs" to summary(roundTrips.toList()),
        "sampleLimit" to 512,
    )

    private fun save() {
        val json = snapshot().toString()
        writer.execute {
            runCatching {
                Files.createDirectories(directory)
                val temporary = Files.createTempFile(directory, ".bridge-", ".tmp")
                try {
                    Files.writeString(temporary, json)
                    Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        save()
        active.decrementAndGet()
    }

    companion object {
        private val active = AtomicInteger()
        private val writer by lazy { Executors.newSingleThreadExecutor { Thread(it, "Varro bridge metrics").apply { isDaemon = true } } }
        fun configured(surface: String): HostBridgeMetrics? = System.getProperty("varro.hostBenchmark.directory")
            ?.takeIf { it.isNotBlank() }?.let { HostBridgeMetrics(Path.of(it), surface) }

        private fun addSample(samples: ArrayDeque<Double>, value: Double) {
            if (samples.size == 512) samples.removeFirst()
            samples.addLast(value)
        }

        private fun summary(samples: List<Double>): Any {
            val sorted = samples.sorted()
            return Json.obj("count" to sorted.size, "p50" to sorted.getOrNull(sorted.size / 2),
                "p95" to sorted.getOrNull((sorted.size * 0.95).toInt().coerceAtMost((sorted.size - 1).coerceAtLeast(0))),
                "max" to sorted.lastOrNull())
        }
    }
}
