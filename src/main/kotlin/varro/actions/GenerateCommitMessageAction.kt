package varro.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.ui.Refreshable
import varro.host.CommitMessageService
import varro.host.VarroProjectService
import com.intellij.openapi.project.guessProjectDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Generates a commit message from the current changes and writes it into the
 * commit input.
 *
 * Port of `varro.generateCommitMessage`. It uses staged changes when the index
 * has any and the unstaged working tree otherwise, never both - a message
 * describing a mix would not match the commit that follows it. Nothing is staged
 * and nothing is committed.
 */
class GenerateCommitMessageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && commitPanel(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = commitPanel(event) ?: return
        val existing = (target as? CheckinProjectPanel)?.commitMessage.orEmpty()

        if (existing.isNotBlank()) {
            val replace = MessageDialogBuilder
                .yesNo("Replace the commit message?", "The commit message box already has text.")
                .yesText("Replace")
                .noText("Keep")
                .ask(project)
            if (!replace) return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating commit message", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val service = VarroProjectService.getInstance(project)
                    val generator = CommitMessageService(project, service.server, service.settings)

                    val changes = collectChanges(project) ?: run {
                        notify(project, "There are no changes to describe.")
                        return
                    }

                    when (val result = generator.generate(changes.diff, changes.recentMessages)) {
                        is CommitMessageService.Result.Generated ->
                            ApplicationManager.getApplication().invokeLater {
                                target.setCommitMessage(result.message)
                            }
                        is CommitMessageService.Result.Failed -> notify(project, result.reason)
                        CommitMessageService.Result.NoChanges ->
                            notify(project, "There are no changes to describe.")
                    }
                }
            },
        )
    }

    private data class Changes(val diff: String, val recentMessages: List<String>)

    /**
     * Reads the diff from Git directly rather than from the IDE's change list:
     * the model needs the actual patch text, and `git diff` is the same source
     * the eventual commit will use.
     *
     * This shells out instead of using Git4Idea's repository API on purpose. That
     * API's types extend the DVCS platform module, which pulls a second bundled
     * dependency in for two commands, and shelling out works in any IDE that has
     * Git on PATH regardless of which VCS plugins are installed.
     */
    private fun collectChanges(project: Project): Changes? {
        val root = project.guessProjectDir()?.path ?: project.basePath ?: return null

        fun git(vararg parameters: String): String? = runCatching {
            val process = ProcessBuilder(listOf("git") + parameters)
                .directory(File(root))
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() == 0) output else null
        }.getOrNull()

        // Staged first; only fall back to the working tree when the index is empty,
        // so the message always describes exactly one of the two.
        val diff = git("diff", "--cached", "--no-color", "--no-ext-diff")?.takeIf { it.isNotBlank() }
            ?: git("diff", "--no-color", "--no-ext-diff")?.takeIf { it.isNotBlank() }
            ?: return null

        val recent = git("log", "-n", "20", "--pretty=format:%s")
            ?.lines()
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return Changes(diff, recent)
    }

    private fun commitPanel(event: AnActionEvent): CommitMessageI? =
        event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
            ?: Refreshable.PANEL_KEY.getData(event.dataContext) as? CommitMessageI

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 30L
    }

    private fun notify(project: Project, message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup(VarroProjectService.NOTIFICATION_GROUP)
            .createNotification("Varro", message, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }
}
