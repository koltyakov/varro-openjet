package varro.host

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import varro.protocol.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs OpenCode setup commands in the IDE terminal and tracks terminal selection.
 *
 * Port of the terminal parts of `context-provider.ts` and
 * `sidebar-provider-actions.ts`.
 *
 * The command allowlist is the important part. Upstream restricts
 * `terminal/run` to commands Varro itself authors - the auth flows plus the
 * install and update commands offered by the server-status recovery states - so
 * a compromised webview cannot run arbitrary shell text in the user's terminal.
 * That restriction is reproduced exactly here.
 */
class TerminalService(private val project: Project) {

    private val log = logger<TerminalService>()

    /** Last captured terminal selection, offered to the composer as context. */
    private val selection = AtomicReference<JsonObject?>(null)

    fun currentSelection(): JsonObject? = selection.get()

    fun setSelection(text: String, terminalName: String) {
        selection.set(
            if (text.isBlank()) null else Json.obj("text" to text, "terminalName" to terminalName),
        )
    }

    fun clearSelection() = selection.set(null)

    /**
     * Runs a command the *webview* asked for. Only the allowlist below is
     * accepted, so a compromised webview cannot put arbitrary shell text in front
     * of the user.
     */
    fun run(command: String, title: String) {
        if (command !in ALLOWED_COMMANDS) {
            log.warn("Refusing to run a terminal command outside the allowlist: $command")
            return
        }
        runTrusted(command, title)
    }

    /**
     * Runs a command the *host* composed from validated inputs, such as handing a
     * session to the OpenCode TUI. The allowlist does not apply because the
     * command text never came from the webview - but every caller is still
     * responsible for validating whatever it interpolates.
     */
    fun runTrusted(command: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            val workingDirectory = project.guessProjectDir()?.path ?: project.basePath
            val opened = runCatching {
                val manager = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
                // `createShellWidget` is deprecated but is the only overload present
                // across the 252-262 range this plugin supports; its replacement
                // landed later than the floor.
                @Suppress("DEPRECATION")
                val widget = manager.createShellWidget(workingDirectory, title, true, true)
                widget.sendCommandToExecute(command)
                true
            }.getOrElse { failure ->
                log.warn("Failed to open the IDE terminal for `$command`", failure)
                false
            }

            if (!opened) {
                // The terminal plugin is optional and can be disabled. Copying the
                // command is a usable fallback: the user can paste it anywhere.
                com.intellij.openapi.ide.CopyPasteManager.getInstance()
                    .setContents(java.awt.datatransfer.StringSelection(command))
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(VarroProjectService.NOTIFICATION_GROUP)
                    .createNotification(
                        "Varro",
                        "The IDE terminal is unavailable. `$command` was copied to the clipboard.",
                        com.intellij.notification.NotificationType.INFORMATION,
                    )
                    .notify(project)
            }
        }
    }

    companion object {
        /**
         * Commands the webview may ask for. Mirrors upstream's
         * `ALLOWED_TERMINAL_COMMANDS`: provider authentication plus the documented
         * install and upgrade paths surfaced by the server-status recovery UI.
         */
        val ALLOWED_COMMANDS: Set<String> = setOf(
            "opencode auth login",
            "opencode auth",
            "opencode providers logout",
            "opencode upgrade",
            "npm install -g opencode-ai",
            "npm install -g opencode-ai@latest",
            "pnpm add -g opencode-ai@latest",
            "bun add -g opencode-ai@latest",
            "yarn global add opencode-ai@latest",
            "brew upgrade sst/tap/opencode",
            "brew install sst/tap/opencode",
            "scoop update opencode",
            "choco upgrade opencode",
            "curl -fsSL https://opencode.ai/install | bash",
        )
    }
}
