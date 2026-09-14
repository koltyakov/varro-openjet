package varro.actions

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.toolWindow.ProblemNode
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import varro.host.VarroProjectService
import varro.host.WorkspaceProblems
import varro.protocol.Json
import varro.settings.VarroSettings

/** JetBrains Problems-view mirror of `varro.chat.addProblemsToContext`. */
class AddProblemsToContextAction : VarroAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null &&
            VarroSettings.getInstance().chatEnableProblemsContext && problem(event) != null
    }

    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) {
        val problem = problem(event) ?: return
        val diagnostic = ApplicationManager.getApplication().runReadAction<com.google.gson.JsonObject?> {
            val node = event.getData(PlatformCoreDataKeys.SELECTED_ITEM) as? ProblemNode
            WorkspaceProblems.fromProblem(problem, WorkspaceProblems.highlights(project, problem.file), node?.getSeverity())
        } ?: return
        service.addProblems(Json.array(listOf(diagnostic)))
    }

    private fun problem(event: AnActionEvent): FileProblem? =
        (event.getData(PlatformCoreDataKeys.SELECTED_ITEM) as? ProblemNode)?.problem as? FileProblem
}
