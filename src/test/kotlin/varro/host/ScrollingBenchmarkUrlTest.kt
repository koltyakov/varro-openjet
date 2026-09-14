package varro.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScrollingBenchmarkUrlTest {
    @Test fun `accepts explicit IPv4 and IPv6 loopback fixture URLs`() {
        assertEquals(ScrollingBenchmarkUrl.DEFAULT, ScrollingBenchmarkUrl.validate(ScrollingBenchmarkUrl.DEFAULT))
        assertEquals("http://[::1]:4186/?run=manual", ScrollingBenchmarkUrl.validate(" http://[::1]:4186/?run=manual "))
    }

    @Test fun `rejects remote URLs ambiguous hosts and credentials`() {
        listOf("https://example.com:4186/", "http://localhost:4186/", "file:///tmp/index.html",
            "http://127.0.0.1/", "http://127.0.0.1:99999/", "http://user:pass@127.0.0.1:4186/",
            "http://127.0.0.1:4186/#secret", "http://127.0.0.1.example.com:4186/").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { ScrollingBenchmarkUrl.validate(url) }
        }
    }
}
