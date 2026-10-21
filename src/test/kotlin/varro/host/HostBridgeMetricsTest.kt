package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*

class HostBridgeMetricsTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `real bridge timing samples are bounded and ignore stale acknowledgements`() {
        var now = 0L
        val metrics = HostBridgeMetrics(temporary.root.toPath(), "editor") { now }
        try {
            metrics.loading()
            now += 12_000_000
            metrics.ready()
            repeat(600) {
                val id = metrics.sent(100)
                now += 2_000_000
                metrics.acknowledge(id)
            }
            val abandoned = metrics.sent(20)
            metrics.loading()
            metrics.acknowledge(abandoned)
            val report = metrics.snapshot()
            assertEquals(600L, report.long("acknowledged"))
            assertEquals(512, report.obj("roundTripMs").int("count"))
            assertEquals(2.0, report.obj("roundTripMs")!!.get("p95").asDouble, 0.001)
            assertEquals(12.0, report.arr("startupMs")!![0].asDouble, 0.001)
            assertEquals(0, report.int("pending"))
        } finally { metrics.close() }
    }
}
