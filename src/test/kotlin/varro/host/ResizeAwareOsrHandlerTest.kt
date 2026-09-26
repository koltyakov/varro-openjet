package varro.host

import org.cef.browser.CefBrowser
import org.cef.handler.CefNativeRenderHandler
import org.cef.handler.CefRenderHandler
import org.junit.Assert.*
import org.junit.Test
import com.intellij.ui.jcef.JBCefBrowserBase
import java.awt.BorderLayout
import java.awt.Rectangle
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ResizeAwareOsrHandlerTest {
    private val browser = proxy<CefBrowser> { _, _ -> null }

    @Test
    fun `paint wrapper preserves the native macOS edit shortcut browser lookup`() {
        SwingUtilities.invokeAndWait {
            val instance = Any()
            val property = JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP
            val panel = JPanel(BorderLayout()).apply { putClientProperty(property, instance) }
            val focusedView = JPanel()
            panel.add(focusedView, BorderLayout.CENTER)
            // JcefShortcutProvider checks only the context component and its parent.
            fun shortcutBrowser() = focusedView.getClientProperty(property)
                ?: (focusedView.parent as? JComponent)?.getClientProperty(property)
            assertSame(instance, shortcutBrowser())

            ResizeAwareOsrHandlerFactory().installPaintGuard(panel, focusedView)

            assertTrue(focusedView.parent is JLayer<*>)
            assertSame(panel, focusedView.parent.parent)
            assertSame("Select All, Copy, Cut and Paste must still reach this browser", instance, shortcutBrowser())
        }
    }

    @Test
    fun `new raster is populated outside incremental dirty region after abrupt growth and shrink`() {
        var image = ByteArray(0)
        var imageWidth = 0
        var imageHeight = 0
        val bounds = Rectangle()
        val frames = OsrFrameState()
        val delegate = renderProxy<CefRenderHandler>(bounds) { method, args ->
            if (method == "onPaint") {
                val width = args[4] as Int
                val height = args[5] as Int
                if (imageWidth != width || imageHeight != height) {
                    imageWidth = width
                    imageHeight = height
                    image = ByteArray(width * height)
                }
                val buffer = args[3] as ByteBuffer
                for (rect in args[2] as Array<*>) {
                    rect as Rectangle
                    for (y in rect.y until rect.y + rect.height) {
                        for (x in rect.x until rect.x + rect.width) image[y * width + x] = buffer.get(y * width + x)
                    }
                }
            }
            null
        }
        val handler = ResizeAwareRenderHandler(delegate, frames)
        val dirty = arrayOf(Rectangle(0, 0, 1, 1))

        for ((width, height) in listOf(4 to 2, 4 to 8, 2 to 3, 4 to 2)) {
            bounds.setSize(width, height)
            handler.onPaint(browser, false, dirty, ByteBuffer.wrap(ByteArray(width * height) { 7 }), width, height)
            assertArrayEquals("The entire new raster must be initialized", ByteArray(width * height) { 7 }, image)
            frames.painted()
        }

        // Stable-size updates must retain the incremental path used during scrolling.
        handler.onPaint(browser, false, dirty, ByteBuffer.wrap(ByteArray(8) { 9 }), 4, 2)
        assertEquals(9.toByte(), image[0])
        assertEquals(7.toByte(), image[1])
    }

    @Test
    fun `remote paints copy full new raster and preserve shared memory identity and popup independence`() {
        val calls = mutableListOf<List<Any?>>()
        val bounds = Rectangle()
        val frames = OsrFrameState()
        val delegate = renderProxy<CefNativeRenderHandler>(bounds) { method, args ->
            if (method == "onPaintWithSharedMem") calls.add(args.toList())
            null
        }
        val handler = ResizeAwareNativeHandler(delegate, frames)
        fun paint(popup: Boolean, width: Int, height: Int) {
            if (!popup) bounds.setSize(width, height)
            handler.onPaintWithSharedMem(browser, popup, 3, "frame", 42L, width, height)
            frames.painted()
        }

        paint(false, 400, 300)
        paint(false, 400, 300)
        paint(true, 30, 20)
        paint(false, 400, 300)
        paint(false, 400, 900)
        paint(false, 400, 300)
        paint(true, 30, 20)
        paint(true, 40, 20)

        assertEquals(listOf(0, 3, 0, 3, 0, 0, 3, 0), calls.map { it[2] })
        for (call in calls) {
            assertSame(browser, call[0])
            assertEquals("frame", call[3])
            assertEquals(42L, call[4])
        }
        assertEquals(listOf(400, 900), calls[4].takeLast(2))
    }

    @Test
    fun `old sized frames cannot replace a new viewport frame on either paint path`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val accepted = mutableListOf<Pair<Int, Int>>()
        val delegate = renderProxy<CefNativeRenderHandler>(bounds, 2.0) { method, args ->
            when (method) {
                "onPaint" -> accepted.add(args[4] as Int to args[5] as Int)
                "onPaintWithSharedMem" -> accepted.add(args[5] as Int to args[6] as Int)
            }
            null
        }
        val handler = ResizeAwareNativeHandler(delegate)
        fun paint(width: Int, height: Int) {
            handler.onPaint(browser, false, emptyArray(), ByteBuffer.allocate(0), width, height)
            handler.onPaintWithSharedMem(browser, false, 1, "frame", 42L, width, height)
        }
        paint(800, 600)
        bounds.height = 900
        paint(800, 600) // Still in flight when the terminal collapses.
        paint(800, 1800)
        paint(800, 600) // Late old-size frame must not make the UI shrink again.
        paint(400, 900) // Logical dimensions are not a Retina raster.
        bounds.height = 300
        paint(800, 1800)
        paint(800, 600)
        assertEquals(listOf(800 to 600, 800 to 600, 800 to 1800, 800 to 1800, 800 to 600, 800 to 600), accepted)
    }

    @Test
    fun `coalesced remote callbacks keep full copy until guarded Swing paint completes`() {
        val bounds = Rectangle(0, 0, 40, 30)
        val frames = OsrFrameState()
        val counts = mutableListOf<Int>()
        val delegate = renderProxy<CefNativeRenderHandler>(bounds) { method, args ->
            if (method == "onPaintWithSharedMem") {
                assertTrue("Frame publication must use the same lock as Swing painting", Thread.holdsLock(frames))
                counts.add(args[2] as Int)
            }
            null
        }
        val handler = ResizeAwareNativeHandler(delegate, frames)
        fun callback() = handler.onPaintWithSharedMem(browser, false, 3, "frame", 42L, bounds.width, bounds.height)
        callback()
        callback()
        callback()
        SwingUtilities.invokeAndWait {
            var painted = false
            val view = object : JComponent() {
                override fun paintComponent(g: Graphics) {
                    assertTrue(Thread.holdsLock(frames))
                    assertTrue(frames.view.fullCopyPending)
                    painted = true
                }
            }
            val layer = JLayer(view, OsrPaintGuard(frames))
            layer.setSize(40, 30)
            layer.doLayout()
            val graphics = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB).createGraphics()
            try {
                layer.paint(graphics)
            } finally {
                graphics.dispose()
            }
            assertTrue(painted)
        }
        callback()
        callback() // Coalesced partial updates also need all damage, not only the last rectangle.
        frames.painted()
        callback()
        bounds.height = 90
        callback()
        callback()
        assertEquals(listOf(0, 0, 0, 3, 0, 3, 0, 0), counts)
    }

    @Test
    fun `skipped frame forces resync even if Swing repaints the cached frame first`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val frames = OsrFrameState()
        val counts = mutableListOf<Int>()
        val delegate = renderProxy<CefNativeRenderHandler>(bounds) { method, args ->
            if (method == "onPaintWithSharedMem") counts.add(args[2] as Int)
            null
        }
        val handler = ResizeAwareNativeHandler(delegate, frames)
        handler.onPaintWithSharedMem(browser, false, 3, "frame", 42L, 400, 300)
        frames.painted()
        handler.onPaintWithSharedMem(browser, false, 3, "frame", 42L, 400, 100)
        frames.painted()
        handler.onPaintWithSharedMem(browser, false, 3, "frame", 42L, 400, 300)
        assertEquals(listOf(0, 0), counts)
    }

    @Test
    fun `fractional device scale accepts pixel rounding but rejects stale sizes`() {
        val bounds = Rectangle(0, 0, 101, 51)
        var accepted = 0
        val delegate = renderProxy<CefNativeRenderHandler>(bounds, 1.25) { method, _ ->
            if (method == "onPaintWithSharedMem") accepted++
            null
        }
        val handler = ResizeAwareNativeHandler(delegate)
        handler.onPaintWithSharedMem(browser, false, 1, "frame", 42L, 126, 63)
        handler.onPaintWithSharedMem(browser, false, 1, "frame", 42L, 127, 64)
        handler.onPaintWithSharedMem(browser, false, 1, "frame", 42L, 101, 51)
        assertEquals(2, accepted)
    }

    @Test
    fun `wrapper preserves render geometry and native resource disposal`() {
        val bounds = Rectangle(0, 0, 400, 900)
        var disposed = false
        val delegate = proxy<CefNativeRenderHandler> { method, _ ->
            when (method) {
                "getViewRect" -> bounds
                "getDeviceScaleFactor" -> 2.0
                "disposeNativeResources" -> { disposed = true; null }
                else -> null
            }
        }
        val handler = ResizeAwareNativeHandler(delegate)
        assertSame(bounds, handler.getViewRect(browser))
        assertEquals(2.0, handler.getDeviceScaleFactor(browser), 0.0)
        handler.disposeNativeResources()
        assertTrue(disposed)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T

    private inline fun <reified T> renderProxy(
        bounds: Rectangle,
        scale: Double = 1.0,
        crossinline invoke: (String, Array<out Any?>) -> Any?,
    ): T = proxy { method, args ->
        when (method) {
            "getViewRect" -> bounds
            "getDeviceScaleFactor" -> scale
            else -> invoke(method, args)
        }
    }
}
