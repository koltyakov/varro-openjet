package varro.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import varro.settings.CommitMessageRules
import varro.settings.VarroSettings

/** Native equivalent of resource-scoped commit-message style preferences. */
class CommitMessageRulesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = GenerateCommitMessageAction().chooseRepository(event) ?: return
        val rules = project.service<CommitMessageRules>()
        val value = Messages.showMultilineInputDialog(project,
            "Style and format for $root. Empty uses the application default.", "Varro Commit Message Rules",
            rules.resolve(root, VarroSettings.getInstance().commitMessageRules), null, null) ?: return
        rules.set(root, value)
    }
}
