package varro.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import varro.protocol.Json

class WebviewLogTest {
    @Test fun `explicit info diagnostics reach the IDE log while untagged messages stay at debug`() {
        assertEquals(WebviewLog.Level.INFO to "webview: Provider catalog returned no providers (read 1 of 3)",
            WebviewLog.entry(Json.obj("msg" to "Provider catalog returned no providers (read 1 of 3)", "level" to "info")))
        assertEquals(WebviewLog.Level.DEBUG to "webview: routine", WebviewLog.entry(Json.obj("msg" to "routine", "data" to "omitted")))
        assertEquals(WebviewLog.Level.INFO to "webview: slow - detail", WebviewLog.entry(Json.obj("msg" to "slow", "level" to "warn", "data" to "detail")))
        assertEquals(WebviewLog.Level.WARN to "webview: failed - boom", WebviewLog.entry(Json.obj("msg" to "failed", "level" to "error", "error" to "boom", "data" to "ignored")))
        assertNull(WebviewLog.entry(Json.obj("level" to "info")))
        assertNull(WebviewLog.entry(null))
    }
}
