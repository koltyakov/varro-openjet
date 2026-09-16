package varro.host

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

internal object JcefBrowserFactory {
    // JCEF in IDEA 2025.2 has no invalidate API. Keep the resize notification
    // there and use the explicit frame request on runtimes that support it.
    private val invalidateMethod = try {
        CefBrowser::class.java.getMethod("invalidate")
    } catch (_: NoSuchMethodException) {
        null
    }

    fun preferredFrameRate(): Int = Registry.intValue("ide.browser.jcef.osr.framerate", 0).takeIf { it > 0 } ?: 60

    fun create(frameRate: Int = preferredFrameRate()): JBCefBrowser {
        val rendering = ResizeAwareOsrHandlerFactory()
        return JBCefBrowser.createBuilder()
            // Keep browser pixels in Swing's paint hierarchy. A native browser window
            // can clip overlapping IDE popups and leave stale regions when they close.
            .setOffScreenRendering(true)
            .setOSRHandlerFactory(rendering)
            .apply { if (frameRate > 0) setWindowlessFramerate(frameRate) }
            .setEnableOpenDevToolsMenuItem(true)
            .build()
            .apply {
                // The remote browser has a separate native setter. Queue it until its
                // native peer exists instead of relying only on creation settings.
                if (isOffScreenRendering && frameRate > 0) cefBrowser.setWindowlessFrameRate(frameRate)
                if (isOffScreenRendering) {
                    rendering.installPaintGuard(this)
                    followComponentSize(this)
                }
            }
    }

    private fun followComponentSize(browser: JBCefBrowser) {
        val view = browser.cefBrowser.uiComponent
        val listener = object : ComponentAdapter() {
            private fun resize() {
                if (!view.isShowing || view.width <= 0 || view.height <= 0) return
                // JBCefOsrComponent delays its resize notification by 100 ms. During
                // a live resize Swing otherwise paints the old, smaller frame in
                // the new bounds. Component events are coalesced on the EDT and
                // arrive after layout, so notify CEF using the current view size.
                // OSR ignores these arguments and asks its render handler for the
                // bounds and device scale.
                browser.cefBrowser.wasResized(0, 0)
                // Request fresh pixels as well as new bounds. Otherwise the OSR
                // resize pusher only starts after the platform's delayed resize.
                invalidateMethod?.invoke(browser.cefBrowser)
            }

            override fun componentResized(event: ComponentEvent) = resize()
            override fun componentShown(event: ComponentEvent) = resize()
        }
        view.addComponentListener(listener)
        Disposer.register(browser, Disposable { view.removeComponentListener(listener) })
    }
}
