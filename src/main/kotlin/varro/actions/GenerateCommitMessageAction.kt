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
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.ui.Refreshable
import com.intellij.vcs.commit.CommitMessageUi
import varro.host.CommitMessageService
import varro.host.VarroProjectService
import varro.host.CommitMessageGenerations
import varro.settings.CommitMessageRules
import com.intellij.openapi.components.service
import com.intellij.icons.AllIcons
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
        val roots = repositoryRoots(event)
        val running = roots.any { event.project?.service<CommitMessageGenerations>()?.isActive(it) == true }
        event.presentation.text = if (running && roots.size > 1) "Generate or Cancel Commit Message with Varro"
            else if (running) "Cancel Commit Message Generation" else "Generate Commit Message with Varro"
        event.presentation.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Diff.MagicResolveToolbar
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = commitPanel(event) ?: return
        val root = chooseRepository(event) ?: return
        val generations = project.service<CommitMessageGenerations>()
        if (generations.isActive(root)) { generations.cancel(root); return }
        val panel = checkinPanel(event)
        val existing = commitText(target, panel)

        if (!existing.isNullOrBlank()) {
            val replace = MessageDialogBuilder
                .yesNo("Replace the commit message?", "The commit message box already has text.")
                .yesText("Replace")
                .noText("Keep")
                .ask(project)
            if (!replace) return
        }

        val attempt = generations.begin(root) ?: return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating commit message", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    var scheduled = false
                    fun check() {
                        if (indicator.isCanceled || project.isDisposed) attempt.cancelled.set(true)
                        attempt.check()
                    }
                    try {
                        check()
                        val service = VarroProjectService.getInstance(project)
                        val generator = CommitMessageService(project, service.server, service.settings)

                        val changes = collectChanges(root, ::check) ?: run {
                            check()
                            notify(project, "There are no changes to describe.")
                            return
                        }

                        val rules = project.service<CommitMessageRules>().resolve(root, service.settings.commitMessageRules)
                        when (val result = generator.generate(changes.diff, changes.recentMessages, root, rules) {
                            indicator.isCanceled || project.isDisposed || attempt.cancelled.get()
                        }) {
                            is CommitMessageService.Result.Generated -> {
                                check()
                                val latest = collectChanges(root, ::check, changes.staged)
                                check()
                                if (latest?.diff != changes.diff) {
                                    notify(project, "Repository changes changed during generation. Generate the commit message again.")
                                    return
                                }
                                ApplicationManager.getApplication().invokeLater {
                                    try {
                                        check()
                                        val current = commitText(target, panel)
                                        if ((current != null && current == existing) || MessageDialogBuilder.yesNo("Replace the commit message?", "The commit input changed during generation or could not be read.").ask(project)) {
                                            check()
                                            target.setCommitMessage(result.message)
                                        }
                                    } catch (_: java.util.concurrent.CancellationException) {
                                        // A cancelled attempt must not write late output.
                                    } finally { generations.finish(root, attempt) }
                                }
                                scheduled = true
                            }
                            is CommitMessageService.Result.Failed -> { check(); notify(project, result.reason) }
                            CommitMessageService.Result.NoChanges -> { check(); notify(project, "There are no changes to describe.") }
                        }
                    } catch (_: java.util.concurrent.CancellationException) {
                        // Cancellation is silent and leaves the commit input untouched.
                    } finally { if (!scheduled) generations.finish(root, attempt) }
                }
            },
        )
    }

    private data class Changes(val diff: String, val recentMessages: List<String>, val staged: Boolean)

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
    private fun collectChanges(root: String, check: () -> Unit, stagedOnly: Boolean? = null): Changes? {
        fun git(vararg parameters: String): String? {
            check()
            val process = ProcessBuilder(listOf("git") + parameters)
                .directory(File(root))
                .redirectErrorStream(false)
                .start()
            val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
            val output = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIT_TIMEOUT_SECONDS)
                while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    check()
                    if (System.nanoTime() >= deadline) error("Git preparation timed out")
                }
                check()
                return if (process.exitValue() == 0) output.get(1, TimeUnit.SECONDS) else null
            } finally {
                process.destroyForcibly()
                output.cancel(true)
                reader.shutdownNow()
            }
        }

        // Staged first; only fall back to the working tree when the index is empty,
        // so the message always describes exactly one of the two.
        val staged = if (stagedOnly != false) git("diff", "--cached", "--no-color", "--no-ext-diff").orEmpty() else ""
        val useStaged = stagedOnly ?: staged.isNotBlank()
        val diff = (if (useStaged) staged else git("diff", "--no-color", "--no-ext-diff"))?.takeIf { it.isNotBlank() } ?: return null

        val recent = git("log", "-n", "20", "--pretty=format:%s")
            ?.lines()
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return Changes(diff, recent, useStaged)
    }

    private fun commitPanel(event: AnActionEvent): CommitMessageI? =
        event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
            ?: Refreshable.PANEL_KEY.getData(event.dataContext) as? CommitMessageI

    private fun checkinPanel(event: AnActionEvent): CheckinProjectPanel? =
        Refreshable.PANEL_KEY.getData(event.dataContext) as? CheckinProjectPanel
            ?: commitPanel(event) as? CheckinProjectPanel

    private fun commitText(target: CommitMessageI, panel: CheckinProjectPanel?): String? =
        (target as? CommitMessageUi)?.text ?: panel?.commitMessage

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 30L
    }

    private fun repositoryRoots(event: AnActionEvent): List<String> =
        checkinPanel(event)?.roots?.map { it.path }?.takeIf { it.isNotEmpty() }?.sorted()
            ?: listOfNotNull(event.project?.guessProjectDir()?.path ?: event.project?.basePath)

    internal fun chooseRepository(event: AnActionEvent): String? {
        val roots = repositoryRoots(event)
        if (roots.size <= 1) return roots.firstOrNull()
        val selection = javax.swing.JComboBox(roots.toTypedArray())
        val panel = javax.swing.JPanel(java.awt.BorderLayout(0, 8)).apply {
            add(javax.swing.JLabel("Choose the Git repository to generate, cancel, or configure."), java.awt.BorderLayout.NORTH)
            add(selection, java.awt.BorderLayout.CENTER)
        }
        val result = DialogBuilder(event.project).title("Varro Commit Message").centerPanel(panel).show()
        return if (result == DialogWrapper.OK_EXIT_CODE) selection.selectedItem as? String else null
    }

    private fun notify(project: Project, message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup(VarroProjectService.NOTIFICATION_GROUP)
            .createNotification("Varro", message, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }
}
