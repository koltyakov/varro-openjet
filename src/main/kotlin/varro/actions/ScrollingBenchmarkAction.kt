package varro.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import varro.host.JcefSupport
import varro.host.ScrollingBenchmark
import varro.host.ScrollingBenchmarkUrl

class ScrollingBenchmarkAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val url = Messages.showInputDialog(project,
            "Start npm run benchmark in webview/, then enter its loopback URL.",
            "Varro Scrolling Benchmark", null, ScrollingBenchmarkUrl.DEFAULT, null) ?: return
        openBenchmark(project, url)
    }
}

/** Opt-in launch hook for `runIde`; ordinary project startup does nothing. */
class ScrollingBenchmarkStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val url = System.getProperty("varro.scrollBenchmark.url") ?: return
        ToolWindowManager.getInstance(project).invokeLater {
            if (!project.isDisposed) openBenchmark(project, url)
        }
    }
}

private fun openBenchmark(project: Project, url: String) {
    if (!JcefSupport.isAvailable()) {
        Messages.showErrorDialog(project, "This IDE runtime does not provide JCEF.", "Varro Scrolling Benchmark")
        return
    }
    val validated = runCatching { ScrollingBenchmarkUrl.validate(url) }.getOrElse {
        Messages.showErrorDialog(project, it.message ?: "Invalid fixture URL", "Varro Scrolling Benchmark")
        return
    }
    ScrollingBenchmark.open(project, validated)
}
