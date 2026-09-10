package dev.koltyakov.varrojet.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.koltyakov.varrojet.host.VarroProjectService

/**
 * IDE commands around the chat surface.
 *
 * Port of `src/extension/commands.ts`. Each `varro.*` VS Code command maps to an
 * action here; the keymap entries live in `plugin.xml`.
 */
abstract class VarroAction : AnAction(), DumbAware {

    final override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        perform(project, VarroProjectService.getInstance(project), event)
    }

    protected abstract fun perform(project: Project, service: VarroProjectService, event: AnActionEvent)

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** `varro.chat.focus` - reveal the tool window and focus the composer. */
class FocusChatAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.focusInput()
}

/** `varro.chat.newSession` */
class NewSessionAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.newSession()
}

/** `varro.chat.searchSessions` */
class SearchSessionsAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.searchSessions()
}

/** `varro.chat.abort` - stop the active run. */
class AbortAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.abort()
}

/** `varro.chat.previousSession` */
class PreviousSessionAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.switchSession("previous")
}

/** `varro.chat.nextSession` */
class NextSessionAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.switchSession("next")
}

/** `varro.server.restart` */
class RestartServerAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.restartServer(force = false)
}

/**
 * `varro.chat.addToContext` / `varro.chat.addSelectionToContext`.
 *
 * The editor selection is already tracked continuously by the context provider,
 * so this refreshes the snapshot and brings the composer forward rather than
 * pushing a separate attachment.
 */
class AddToContextAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) =
        service.addToContext()

    override fun update(event: AnActionEvent) {
        // Offered wherever there is a file to talk about, with or without a selection.
        event.presentation.isEnabled =
            event.project != null && event.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }
}

/** Opens the JCEF developer tools for the chat webview. */
class OpenDevToolsAction : VarroAction() {
    override fun perform(project: Project, service: VarroProjectService, event: AnActionEvent) {
        service.showToolWindow()
        service.openDevTools()
    }
}
