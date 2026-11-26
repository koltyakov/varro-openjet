package varro.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.ui.Refreshable
import varro.host.VarroProjectService
import varro.host.Walkthrough
import varro.host.WalkthroughDigest
import varro.host.WalkthroughFile
import varro.host.WalkthroughGit
import varro.host.WalkthroughModel
import varro.host.WalkthroughService
import varro.host.WalkthroughTooLargeException
import varro.server.ServerStatus
import varro.settings.VarroSettings
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generates an AI-guided reading order through uncommitted Git changes and opens
 * it as a Markdown report.
 *
 * Port of upstream `varro.walkThroughChanges` (`walkthrough-service.ts`). The
 * user picks staged, unstaged (including untracked files), or all changes when
 * both exist. The walkthrough uses the review model when one is set, otherwise
 * the active chat's exact selection.
 */
class WalkThroughChangesAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = chooseRepository(event, project) ?: return
        val run = project.service<WalkthroughRuns>()
        if (!run.active.compareAndSet(false, true)) {
            notify(project, "A changes walkthrough is already being generated.")
            return
        }
        Flow(project, root, run.active).start()
    }

    /** One walkthrough from Git capture to the opened report; [active] is released exactly once. */
    private class Flow(private val project: Project, private val root: String, private val active: AtomicBoolean) {
        private val log = logger<WalkThroughChangesAction>()
        private val released = AtomicBoolean(false)

        fun release() { if (released.compareAndSet(false, true)) active.set(false) }

        fun start() = runPhase("Reading uncommitted changes", { indicator ->
            val changes = WalkthroughGit.capture(root) { check(indicator) }
            val next: () -> Boolean = { pickScope(changes) }
            next
        })

        private fun pickScope(changes: WalkthroughGit.Changes): Boolean {
            val hasStaged = changes.staged.isNotBlank()
            val hasUnstaged = changes.unstaged.isNotBlank()
            if (!hasStaged && !hasUnstaged) {
                notify(project, "There are no uncommitted changes in $root.")
                return false
            }
            if (!hasStaged) return prepare(changes, Scope.UNSTAGED)
            if (!hasUnstaged) return prepare(changes, Scope.STAGED)
            var chosen = false
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(Scope.entries.toList())
                .setTitle("Which changes should the walkthrough explain?")
                .setItemChosenCallback { scope ->
                    chosen = true
                    val started = runCatching { prepare(changes, scope) }.onFailure { report(it) }.getOrDefault(false)
                    if (!started) release()
                }
                .addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        // The chosen callback can run before or after close; check once both settled.
                        ApplicationManager.getApplication().invokeLater { if (!chosen) release() }
                    }
                })
                .createPopup()
                .showCenteredInCurrentWindow(project)
            return true
        }

        /** Returns false when nothing was started and the caller must release. */
        private fun prepare(changes: WalkthroughGit.Changes, scope: Scope): Boolean {
            val files = buildList {
                if (scope != Scope.UNSTAGED) addAll(Walkthrough.parseUnifiedDiff(changes.staged, "staged"))
                if (scope != Scope.STAGED) addAll(Walkthrough.parseUnifiedDiff(changes.unstaged, "unstaged"))
            }
            val service = VarroProjectService.getInstance(project)
            val model = Walkthrough.selectModel(VarroSettings.getInstance().reviewModel,
                WalkthroughModel.fromJson(service.activeChatModel))
            if (files.none { it.hunks.isNotEmpty() }) {
                notify(project, "There are no textual changes to walk through.")
                return false
            }
            val generator = WalkthroughService(service.server.transport::request, { service.server.transport.apiVersion })
            runPhase("Preparing changes walkthrough", { indicator ->
                awaitServer(service, indicator)
                // Size the request to the model, and refuse an oversized diff before spending a request.
                val budget = Walkthrough.digestBudget(generator.readContextLimit(model, root,
                    varro.server.RequestOptions(directory = root, isCancelled = { cancelled(indicator) })))
                check(indicator)
                val digest = Walkthrough.buildDigest(files, budget)
                val next: () -> Boolean = { generate(generator, files, digest, scope, model) }
                next
            })
            return true
        }

        private fun generate(generator: WalkthroughService, files: List<WalkthroughFile>, digest: WalkthroughDigest,
                             scope: Scope, model: WalkthroughModel?): Boolean {
            runPhase("Generating changes walkthrough (${Walkthrough.formatTokens(digest.digest.length)} tokens)", { indicator ->
                val report = generator.generate(files, digest, scope.label, root, model) { cancelled(indicator) }
                val next: () -> Boolean = {
                    VarroProjectService.getInstance(project).editor
                        .openText(report.content, "Walkthrough: ${report.title}.md", "markdown", previewOnly = true)
                    false
                }
                next
            })
            return true
        }

        /**
         * Runs [work] in a cancellable background task. Its result runs on the EDT
         * and returns whether it handed the flow to another phase; otherwise, and on
         * failure or cancellation, the single-flight guard is released.
         */
        private fun runPhase(title: String, work: (ProgressIndicator) -> () -> Boolean) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
                private var next: (() -> Boolean)? = null
                private var handedOff = false

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    next = work(indicator)
                }

                override fun onSuccess() {
                    if (project.isDisposed) return
                    handedOff = runCatching { next?.invoke() == true }
                        .onFailure { report(it) }
                        .getOrDefault(false)
                }

                override fun onThrowable(error: Throwable) = report(error)

                override fun onFinished() { if (!handedOff) release() }
            })
        }

        private fun report(error: Throwable) {
            if (error is CancellationException || project.isDisposed) return
            val message = error.message ?: error.toString()
            if (error is WalkthroughTooLargeException) {
                notify(project, "$message Stage a subset and walk through the staged changes, or pick a model with a larger context window.",
                    NotificationType.ERROR)
                return
            }
            log.warn("Changes walkthrough failed: $message", error)
            notify(project, "Could not generate the walkthrough: $message", NotificationType.ERROR)
        }

        private fun cancelled(indicator: ProgressIndicator) = indicator.isCanceled || project.isDisposed

        private fun check(indicator: ProgressIndicator) {
            indicator.checkCanceled()
            if (project.isDisposed) throw CancellationException("Project closed")
        }

        private fun awaitServer(service: VarroProjectService, indicator: ProgressIndicator) {
            service.ensureServerStarted()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SERVER_START_SECONDS)
            while (true) {
                check(indicator)
                when (val status = service.server.currentStatus()) {
                    is ServerStatus.Running -> return
                    is ServerStatus.Error -> error(status.message)
                    else -> Unit
                }
                if (System.nanoTime() - deadline >= 0) error("OpenCode did not become available within $SERVER_START_SECONDS seconds")
                Thread.sleep(100)
            }
        }
    }

    private enum class Scope(val label: String, private val description: String?) {
        STAGED("Staged changes", "What the next commit contains"),
        UNSTAGED("Unstaged changes", "Working tree edits and new files"),
        ALL("All uncommitted changes", null);

        override fun toString() = description?.let { "$label — $it" } ?: label
    }

    private fun repositoryRoots(event: AnActionEvent, project: Project): List<String> {
        val panel = Refreshable.PANEL_KEY.getData(event.dataContext) as? CheckinProjectPanel
        panel?.roots?.map { it.path }?.takeIf { it.isNotEmpty() }?.let { return it.distinct().sorted() }
        val git = ProjectLevelVcsManager.getInstance(project).allVcsRoots
            .filter { it.vcs?.name == "Git" }
            .map { it.path.path }
        if (git.isNotEmpty()) return git.distinct().sorted()
        return listOfNotNull(project.guessProjectDir()?.path ?: project.basePath)
    }

    private fun chooseRepository(event: AnActionEvent, project: Project): String? {
        val roots = repositoryRoots(event, project)
        if (roots.size <= 1) return roots.firstOrNull()
        val selection = javax.swing.JComboBox(roots.toTypedArray())
        val panel = javax.swing.JPanel(java.awt.BorderLayout(0, 8)).apply {
            add(javax.swing.JLabel("Select a repository to walk through."), java.awt.BorderLayout.NORTH)
            add(selection, java.awt.BorderLayout.CENTER)
        }
        val result = DialogBuilder(project).title("Varro Changes Walkthrough").centerPanel(panel).show()
        return if (result == DialogWrapper.OK_EXIT_CODE) selection.selectedItem as? String else null
    }

    private companion object {
        const val SERVER_START_SECONDS = 30L

        fun notify(project: Project, message: String, type: NotificationType = NotificationType.INFORMATION) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(VarroProjectService.NOTIFICATION_GROUP)
                .createNotification("Varro", message, type)
                .notify(project)
        }
    }
}

/** Single-flight guard: one changes walkthrough per project at a time, as upstream. */
@Service(Service.Level.PROJECT)
class WalkthroughRuns {
    val active = AtomicBoolean(false)
}
