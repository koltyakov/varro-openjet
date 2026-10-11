package varro.server

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCodeTransportReadTest {
    @Test fun `completion setup reads can be cancelled after response headers arrive`() {
        val headers = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/config/providers") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                headers.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        val transport = OpenCodeTransport({ url }, { "/fixture" }, { ServerStatus.Running(url) }, { false }, {}, {})
        val cancelled = AtomicBoolean(false)
        val result = CompletableFuture.supplyAsync {
            runCatching { transport.request("GET", "/config/providers", options = RequestOptions(cancellableBody = true,
                isCancelled = cancelled::get)) }.exceptionOrNull()
        }
        try {
            assertTrue(headers.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(result.get(2, TimeUnit.SECONDS) is CancellationException)
        } finally {
            cancelled.set(true)
            release.countDown()
            transport.dispose()
            server.stop(0)
        }
    }
}
