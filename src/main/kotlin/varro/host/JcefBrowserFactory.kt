package varro.host

import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.jcef.JBCefBrowser

internal object JcefBrowserFactory {
    fun preferredFrameRate(): Int = Registry.intValue("ide.browser.jcef.osr.framerate", 0).takeIf { it > 0 } ?: 60

    fun create(frameRate: Int = preferredFrameRate()): JBCefBrowser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        // Out-of-process JCEF can force OSR even when windowed rendering was requested.
        .apply { if (frameRate > 0) setWindowlessFramerate(frameRate) }
        .setEnableOpenDevToolsMenuItem(true)
        .build()
        .apply {
            // The remote browser has a separate native setter. Queue it until its
            // native peer exists instead of relying only on creation settings.
            if (isOffScreenRendering && frameRate > 0) cefBrowser.setWindowlessFrameRate(frameRate)
        }
}
