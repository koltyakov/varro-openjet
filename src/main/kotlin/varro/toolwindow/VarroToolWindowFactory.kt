package varro.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import varro.host.VarroProjectService
import varro.host.WebviewHost
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Creates the Varro tool window.
 *
 * The webview lives here, but the services behind it belong to
 * [VarroProjectService]: closing the tool window must not stop a running
 * OpenCode session, and reopening it must reattach to one.
 */
class VarroToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = VarroProjectService.getInstance(project)
        val content = ContentFactory.getInstance().createContent(null, null, false)
        content.isCloseable = false
        content.component = buildPanel(project, service, content)
        toolWindow.contentManager.addContent(content)
        registerTitleActions(toolWindow, service)
    }

    private fun buildPanel(
        project: Project,
        service: VarroProjectService,
        parent: com.intellij.openapi.Disposable,
    ): JPanel {
        if (!varro.host.JcefSupport.isAvailable()) return unsupportedPanel()
        if (!varro.host.WebviewAssets.isBundlePresent()) return missingBundlePanel()

        val host = service.createPanel(WebviewHost.Surface.SIDEBAR)
        // The browser is a native resource: tie it to the content so closing the
        // tool window releases it. The OpenCode server behind it is owned by the
        // project service and keeps running.
        Disposer.register(parent, host)
        return JPanel(BorderLayout()).apply {
            add(host.component, BorderLayout.CENTER)
        }
    }

    /**
     * Shown when the IDE has no JCEF runtime. This is a real configuration -
     * some distributions ship without it, and users can disable it - so it needs
     * an explanation rather than an empty panel.
     */
    private fun unsupportedPanel(): JPanel = messagePanel(
        title = "Varro needs the embedded browser",
        body = "This IDE build does not provide JCEF, or it has been disabled.\n\n" +
            "Enable it in Help | Find Action | Choose Boot Java Runtime for the IDE, " +
            "and select a runtime 'with JCEF'.",
    )

    /**
     * Shown when the plugin was built without running the webview build, which is
     * easy to do with `-PskipWebview=true` and otherwise looks like a blank panel.
     */
    private fun missingBundlePanel(): JPanel = messagePanel(
        title = "The Varro webview bundle is missing",
        body = "This build does not contain the compiled webview.\n\n" +
            "Rebuild with ./scripts/build.sh (or ./gradlew buildPlugin) without -PskipWebview=true.",
    )

    private fun messagePanel(title: String, body: String): JPanel {
        val panel = JPanel(BorderLayout())
        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(24)
            add(JBLabel("<html><b>$title</b></html>"))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JBLabel("<html>${body.replace("\n", "<br/>")}</html>").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        panel.add(inner, BorderLayout.NORTH)
        return panel
    }

    private fun registerTitleActions(toolWindow: ToolWindow, service: VarroProjectService) {
        val actions = listOfNotNull(
            ActionManager.getInstance().getAction("Varro.NewSession"),
            ActionManager.getInstance().getAction("Varro.SearchSessions"),
            ActionManager.getInstance().getAction("Varro.RestartServer"),
        )
        if (actions.isNotEmpty()) toolWindow.setTitleActions(actions)
        toolWindow.setAdditionalGearActions(com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            listOf("Varro.NewEditor", "Varro.ToggleFileDiffs", "Varro.Settings", "Varro.Usage", "Varro.About")
                .mapNotNull { ActionManager.getInstance().getAction(it) }.forEach { add(it) }
        })
    }

    /** The tool window is registered eagerly so its icon is always available. */
    override fun shouldBeAvailable(project: Project): Boolean = true
}
