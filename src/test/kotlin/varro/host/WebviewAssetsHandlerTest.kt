package varro.host

import org.cef.callback.CefCallback
import org.cef.misc.IntRef
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class WebviewAssetsHandlerTest {
    @Test fun `legacy callbacks stream the entire asset in chunks then signal EOF`() {
        val bytes = "export default 'webview';".toByteArray()
        val handler = WebviewAssets.Handler(WebviewAssets.Resolved(bytes, "text/javascript"))
        val callback = Callback()
        assertTrue(handler.processRequest(null, callback))
        assertTrue(callback.continued)
        assertFalse(callback.cancelled)

        val length = IntRef()
        handler.getResponseHeaders(null, length, null)
        assertEquals(bytes.size, length.get())

        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(5)
        val read = IntRef()
        while (handler.readResponse(buffer, buffer.size, read, null)) {
            assertTrue(read.get() in 1..buffer.size)
            output.write(buffer, 0, read.get())
        }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(0, read.get())
    }

    @Test fun `missing asset is handled with an empty response rather than cancelled`() {
        val handler = WebviewAssets.Handler(null)
        val callback = Callback()
        assertTrue(handler.processRequest(null, callback))
        assertTrue(callback.continued)
        assertFalse(callback.cancelled)
        val length = IntRef(-1)
        handler.getResponseHeaders(null, length, null)
        assertEquals(0, length.get())
        val read = IntRef(-1)
        assertFalse(handler.readResponse(ByteArray(8), 8, read, null))
        assertEquals(0, read.get())
    }

    @Test fun `cancelling an asset discards unread bytes`() {
        val handler = WebviewAssets.Handler(WebviewAssets.Resolved(ByteArray(32), "text/plain"))
        handler.processRequest(null, Callback())
        handler.cancel()
        val read = IntRef(-1)
        assertFalse(handler.readResponse(ByteArray(8), 8, read, null))
        assertEquals(0, read.get())
    }

    private class Callback : CefCallback {
        var continued = false
        var cancelled = false
        override fun Continue() { continued = true }
        override fun cancel() { cancelled = true }
    }
}
