package varro.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/** Registered normally, but shown only when the diagnostic action is requested. */
class ScrollingBenchmarkToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = false
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = Unit
}
