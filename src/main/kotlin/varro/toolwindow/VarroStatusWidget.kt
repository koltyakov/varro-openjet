package varro.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import varro.host.VarroProjectService
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class VarroStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "VarroStatus"
    override fun getDisplayName() = "Varro"
    override fun isAvailable(project: Project) = true
    override fun canBeEnabledOn(statusBar: StatusBar) = true
    override fun createWidget(project: Project): StatusBarWidget = VarroStatusWidget(project)
}

private class VarroStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var timer: ScheduledFuture<*>? = null
    @Volatile private var text = "Varro"
    @Volatile private var toolWindowVisible = false
    @Volatile private var disposed = false

    override fun ID() = "VarroStatus"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText() = if (toolWindowVisible) "" else text
    override fun getAlignment() = 0f
    override fun getTooltipText() = when {
        toolWindowVisible -> null
        hasUnreadStatus() -> "$text. Click to view completed sessions."
        hasRunningStatus() -> "$text. Click to view running sessions."
        else -> "$text. Click to open Varro chat."
    }
    override fun getClickConsumer() = if (toolWindowVisible) null else Consumer<MouseEvent> {
        val service = VarroProjectService.getInstance(project)
        when {
            hasUnreadStatus() -> service.openCompletedSessions()
            hasRunningStatus() -> service.openRunningSessions()
            else -> service.showToolWindow()
        }
    }
    override fun install(statusBar: StatusBar) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        toolWindowVisible = toolWindowManager.getToolWindow("Varro")?.isVisible == true
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                val next = toolWindowManager.getToolWindow("Varro")?.isVisible == true
                if (next != toolWindowVisible) {
                    toolWindowVisible = next
                    statusBar.updateWidget(ID())
                }
            }
        })
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (!disposed && !project.isDisposed) {
                val nextText = project.getServiceIfCreated(VarroProjectService::class.java)?.statusBarText() ?: "Varro"
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) {
                        val nextVisible = ToolWindowManager.getInstance(project).getToolWindow("Varro")?.isVisible == true
                        if (nextText != text || nextVisible != toolWindowVisible) {
                            text = nextText
                            toolWindowVisible = nextVisible
                            statusBar.updateWidget(ID())
                        }
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }
    private fun hasUnreadStatus() = text.startsWith("Varro: ") && text.endsWith(" unread")
    private fun hasRunningStatus() = text == "Varro: running"
    override fun dispose() { disposed = true; timer?.cancel(false); timer = null }
}
