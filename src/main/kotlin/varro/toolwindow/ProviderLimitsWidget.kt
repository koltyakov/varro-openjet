package varro.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.concurrency.AppExecutorUtil
import varro.host.VarroProjectService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ProviderLimitsWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "VarroProviderLimits"
    override fun getDisplayName() = "Varro pinned provider limits"
    override fun isAvailable(project: Project) = true
    override fun canBeEnabledOn(statusBar: StatusBar) = true
    override fun createWidget(project: Project): StatusBarWidget = ProviderLimitsWidget(project)
}

private class ProviderLimitsWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var text = ""
    private var tooltip = ""
    @Volatile private var disposed = false
    private var timer: ScheduledFuture<*>? = null
    override fun ID() = "VarroProviderLimits"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText() = text
    override fun getAlignment() = 0f
    override fun getTooltipText() = tooltip.takeIf { it.isNotEmpty() }?.let { "<html>${StringUtil.escapeXmlEntities(it).replace("\n", "<br/>")}</html>" }
    override fun getClickConsumer(): com.intellij.util.Consumer<java.awt.event.MouseEvent>? = null
    override fun install(statusBar: StatusBar) {
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) {
                    val display = project.getServiceIfCreated(VarroProjectService::class.java)?.pinnedProviderLimitsDisplay()
                    val nextText = display?.text.orEmpty()
                    val nextTooltip = display?.tooltip.orEmpty()
                    if (text != nextText || tooltip != nextTooltip) {
                        text = nextText; tooltip = nextTooltip; statusBar.updateWidget(ID())
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }
    override fun dispose() { disposed = true; timer?.cancel(false) }
}
