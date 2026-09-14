package varro.host

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import varro.protocol.Json
import java.awt.BorderLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JPanel

/** Requests the same JCEF mode as WebviewHost. The IDE may override it in remote mode. */
object ScrollingBenchmark {
    fun open(project: Project, url: String) {
        val manager = ToolWindowManager.getInstance(project)
        val window = checkNotNull(manager.getToolWindow(ID)) { "Scrolling benchmark tool window is not registered" }
        window.isAvailable = true
        window.setToHideOnEmptyContent(true)
        val frameRate = System.getProperty("varro.scrollBenchmark.frameRate")?.toIntOrNull()
            ?: JcefBrowserFactory.preferredFrameRate()
        val browser = JcefBrowserFactory.create(frameRate)
        // Initialize the Swing/OSR component before requesting the native browser.
        val browserComponent = browser.component
        val metadata = Json.stringify(Json.obj(
            "protocol" to "jcef-scroll-host-v1",
            "pluginVersion" to VarroBuild.version,
            "idePid" to ProcessHandle.current().pid(),
            "requestedFrameRate" to frameRate,
            "frameRatePolicy" to "explicit-native-setter-v1",
            "browserImplementation" to browser.cefBrowser.javaClass.name,
            "pluginLocation" to ScrollingBenchmark::class.java.getResource("ScrollingBenchmark.class")?.toString(),
        ))
        logger<ScrollingBenchmark>().info("Scrolling benchmark native host: $metadata")
        val panel = JPanel(BorderLayout()).apply {
            add(browserComponent, BorderLayout.CENTER)
            add(JButton("Open benchmark developer tools").apply {
                addActionListener { browser.openDevtools() }
            }, BorderLayout.SOUTH)
        }
        val content = ContentFactory.getInstance().createContent(panel, "Scrolling fixture", false)
        content.setDisposer(browser)
        window.contentManager.addContent(content)
        window.contentManager.setSelectedContent(content)
        browser.loadURL(url + (if ('?' in url) "&" else "?") + "nativeHost=" + URLEncoder.encode(metadata, StandardCharsets.UTF_8))
        window.activate { browser.cefBrowser.setFocus(true) }
    }

    private const val ID = "Varro Benchmark"
}
