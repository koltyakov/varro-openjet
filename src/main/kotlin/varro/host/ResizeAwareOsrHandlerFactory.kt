package varro.host

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefOSRHandlerFactory
import org.cef.browser.CefBrowser
import org.cef.handler.CefNativeRenderHandler
import org.cef.handler.CefRenderHandler
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Rectangle
import java.nio.ByteBuffer
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.plaf.LayerUI
import kotlin.math.ceil
import kotlin.math.floor

/** Keep the platform's input/HiDPI handling and make frame publication atomic with Swing painting. */
internal class ResizeAwareOsrHandlerFactory : JBCefOSRHandlerFactory {
    private val frames = OsrFrameState()

    override fun createCefRenderHandler(component: JComponent): CefRenderHandler {
        val delegate = super.createCefRenderHandler(component)
        return if (delegate is CefNativeRenderHandler) ResizeAwareNativeHandler(delegate, frames) else ResizeAwareRenderHandler(delegate, frames)
    }

    fun installPaintGuard(browser: JBCefBrowser) {
        installPaintGuard(browser.component, browser.cefBrowser.uiComponent as JComponent)
    }

    internal fun installPaintGuard(panel: JComponent, view: JComponent) {
        panel.remove(view)
        val layer = JLayer(view, OsrPaintGuard(frames))
        // JCEF's macOS edit actions find the browser on the focused component or
        // its immediate parent. Preserve that lookup when inserting the paint layer.
        layer.putClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP,
            panel.getClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP))
        panel.add(layer, BorderLayout.CENTER)
    }
}

internal class OsrPaintGuard(private val frames: OsrFrameState) : LayerUI<JComponent>() {
    override fun paint(g: Graphics, c: JComponent) {
        synchronized(frames) {
            super.paint(g, c)
            frames.painted()
        }
    }
}

internal open class ResizeAwareRenderHandler(
    private val delegate: CefRenderHandler,
    protected val frames: OsrFrameState = OsrFrameState(),
) : CefRenderHandler by delegate {
    protected fun accepts(browser: CefBrowser, popup: Boolean, width: Int, height: Int): Boolean {
        if (popup) return true
        val bounds = delegate.getViewRect(browser)
        val scale = delegate.getDeviceScaleFactor(browser)
        val matches = matchesRaster(width, bounds.width * scale) && matchesRaster(height, bounds.height * scale)
        if (!matches) frames.view.invalidate()
        return matches
    }

    private fun matchesRaster(actual: Int, expected: Double): Boolean =
        actual > 0 && expected > 0 && actual >= floor(expected) && actual <= ceil(expected)

    override fun onPaint(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRects: Array<Rectangle>,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
    ) = synchronized(frames) {
        if (!accepts(browser, popup, width, height)) return@synchronized
        val rectangles = if (frames.needsFullPaint(popup, width, height)) arrayOf(Rectangle(0, 0, width, height)) else dirtyRects
        delegate.onPaint(browser, popup, rectangles, buffer, width, height)
    }
}

/** Accessed under the per-browser monitor by the callback and Swing paint paths. */
internal class OsrFrameState {
    val view = RasterSize()
    private val popup = RasterSize()

    fun needsFullPaint(popup: Boolean, width: Int, height: Int): Boolean =
        (if (popup) this.popup else view).needsFullPaint(width, height)

    fun painted() {
        view.painted()
        popup.painted()
    }

    class RasterSize {
        private var width = -1
        private var height = -1
        var fullCopyPending = true
            private set
        private var skippedFrame = false
        private var awaitingPaint = false

        fun invalidate() {
            skippedFrame = true
        }

        fun needsFullPaint(width: Int, height: Int): Boolean {
            // The native delegate retains only the latest dirty-rectangle metadata.
            // If Swing coalesces callbacks, copy all pixels to include earlier damage.
            if (awaitingPaint || skippedFrame || this.width != width || this.height != height) fullCopyPending = true
            skippedFrame = false
            awaitingPaint = true
            this.width = width
            this.height = height
            return fullCopyPending
        }

        fun painted() {
            fullCopyPending = false
            awaitingPaint = false
        }
    }
}

internal class ResizeAwareNativeHandler(
    private val delegate: CefNativeRenderHandler,
    frames: OsrFrameState = OsrFrameState(),
) : ResizeAwareRenderHandler(delegate, frames), CefNativeRenderHandler {
    override fun onPaintWithSharedMem(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRectsCount: Int,
        sharedMemName: String,
        sharedMemHandle: Long,
        width: Int,
        height: Int,
    ) = synchronized(frames) {
        if (!accepts(browser, popup, width, height)) return@synchronized
        // The platform allocates a blank image when the raster size changes, then
        // copies only dirty rectangles. Those rectangles may describe an incremental
        // update, not the entire new image. Zero tells its shared-memory loader to
        // copy the full raster. The buffer still uses its original width/height.
        // Several callbacks can arrive before Swing paints. Keep requesting a full
        // copy until that paint completes; otherwise a subsequent callback can
        // overwrite the shared-memory dirty count before the new image is seeded.
        val count = if (frames.needsFullPaint(popup, width, height)) 0 else dirtyRectsCount
        delegate.onPaintWithSharedMem(browser, popup, count, sharedMemName, sharedMemHandle, width, height)
    }

    override fun disposeNativeResources() = delegate.disposeNativeResources()
}
