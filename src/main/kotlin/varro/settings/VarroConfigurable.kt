package varro.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import javax.swing.JComponent

/**
 * Settings page under `Tools | Varro`.
 *
 * Mirrors upstream's `varro.*` VS Code settings. Where JetBrains already has an
 * equivalent concept - font sizes, in particular - the default follows the IDE
 * instead of duplicating a number the user has already chosen.
 */
class VarroConfigurable : BoundConfigurable("Varro") {

    private val settings = VarroSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("OpenCode Server") {
            row("Port:") {
                intTextField(1..65_535)
                    .bindIntText(settings::serverPort)
                    .comment("Restart the server after changing this.")
            }
            row("CLI path:") {
                textField()
                    .bindText(settings::serverCommand)
                    .comment("Leave empty to discover `opencode` on PATH and the usual global install locations.")
                    .columns(40)
            }
            row {
                checkBox("Start the server automatically when Varro needs it")
                    .bindSelected(settings::serverAutoStart)
            }
            row {
                checkBox("Install OpenCode CLI updates in the background")
                    .bindSelected(settings::serverAutoUpdate)
                    .comment("Updates are only applied while no session is running.")
            }
        }

        group("Chat") {
            row("Default permission mode:") {
                comboBox(listOf("default", "auto", "full"))
                    .bindItem(settings::chatDefaultPermissionMode.toNullableProperty())
                    .comment(
                        "`default` follows OpenCode's own rules, `auto` applies local rules, " +
                            "`full` lets a session act without confirmation.",
                    )
            }
            row("Sessions pane side:") {
                comboBox(listOf("left", "right"))
                    .bindItem(settings::chatDesktopSessionPaneSide.toNullableProperty())
            }
            row {
                checkBox("Show line-by-line edits in file-change cards")
                    .bindSelected(settings::chatShowFileDiffs)
            }
            row {
                checkBox("Expand thinking while reasoning is active")
                    .bindSelected(settings::chatExpandThinking)
            }
            row {
                checkBox("Show the changed-files panel above the composer")
                    .bindSelected(settings::chatShowChangedFiles)
            }
            row {
                checkBox("Show the elapsed turn duration")
                    .bindSelected(settings::chatShowTurnTimer)
            }
            row("Chat font size:") {
                intTextField(0..100)
                    .bindIntText(settings::chatFontSize)
                    .comment("0 follows the IDE UI font size.")
            }
            row("Chat font family:") {
                textField().bindText(settings::chatFontFamily).comment("default follows the IDE font family.")
            }
            row("Code font size:") {
                intTextField(0..100)
                    .bindIntText(settings::chatEditorFontSize)
                    .comment("0 follows the IDE editor font size.")
            }
        }

        group("Models") {
            row("Auto-approve judge model:") {
                textField()
                    .bindText(settings::chatAutoApproveModel)
                    .comment("`providerID/modelID`. Leave empty to disable model-based approval.")
                    .columns(40)
            }
            row("Commit message model:") {
                textField()
                    .bindText(settings::commitMessageModel)
                    .comment("`providerID/modelID`. Leave empty to let Varro choose.")
                    .columns(40)
            }
        }

        group("Agents") {
            row {
                checkBox("Add Varro's read-only `Ask` agent at runtime")
                    .bindSelected(settings::chatEnableAskAgent)
                    .comment("Applies to servers started by Varro. Existing configured Ask agents are preserved; no user configuration is written.")
            }
            row {
                checkBox("Let OpenCode compact sessions automatically when context fills")
                    .bindSelected(settings::chatAutoCompact)
            }
            row("Compaction reserved tokens:") {
                intTextField(0..1_000_000).bindIntText(settings::chatAutoCompactionReservedTokens)
            }
            row {
                checkBox("Generate a fallback title for untitled sessions")
                    .bindSelected(settings::chatAutoRenameUntitledSessions)
            }
        }
    }

    /**
     * Open tool windows re-read configuration on this topic, so a change lands in
     * the running webview without a reload.
     */
    override fun apply() {
        super.apply()
        ApplicationManager.getApplication().messageBus
            .syncPublisher(VarroSettings.TOPIC)
            .settingsChanged()
    }
}
