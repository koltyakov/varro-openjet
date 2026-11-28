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
    private val shared = OpenJetSharedSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Shared OpenJet settings") {
            row {
                comment("Model lists and core settings are shared across JetBrains IDEs. Appearance stays local. VS Code settings are separate.")
            }
            row {
                comment(shared.path)
            }
            row {
                button("Use this IDE's settings to initialize sharing") {
                    try {
                        shared.initializeFromThisIde()
                        reset()
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            "Shared settings are active. Other JetBrains IDEs running OpenJet will pick them up automatically.",
                            "OpenJet settings",
                        )
                    } catch (error: Exception) {
                        com.intellij.openapi.ui.Messages.showErrorDialog(error.message ?: "Unable to save shared settings", "OpenJet settings")
                    }
                }.comment("Run this in the IDE whose saved model preferences you want to keep. An existing shared file is never replaced.")
            }
        }
        group("OpenCode Server") {
            row("Port:") {
                textField()
                    .bindText({ settings.fixedServerPort()?.toString().orEmpty() }, { settings.serverPort = it.trim().toIntOrNull() ?: 0 })
                    .validationOnApply {
                        val text = it.text.trim()
                        if (text.isEmpty() || text.toIntOrNull() in 1..65_535) null
                        else com.intellij.openapi.ui.ValidationInfo("Enter a port from 1 to 65535 or leave it blank.", it)
                    }
                    .columns(8)
                    .comment("Leave blank to let Varro choose a port. If you enter a port, Varro uses only that port. Reopen the project after changing it.")
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
                    .comment("Turn off if you run the server yourself. Some CLI and server configuration actions are then unavailable.")
            }
            row {
                checkBox("Install OpenCode CLI updates in the background")
                    .bindSelected(settings::serverAutoUpdate)
                    .comment("Updates stay on the installed major version and run only while no session is active.")
            }
        }

        group("Chat") {
            row {
                checkBox("Automatically attach the active file or editor selection")
                    .bindSelected(settings::chatAutoAttachActiveFile)
                    .comment("Applies to queued messages. Files and database context you attach yourself are still included.")
            }
            row {
                checkBox("Include IDE problems in chat context")
                    .bindSelected(settings::chatEnableProblemsContext)
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
            row {
                checkBox("Group composer panels into tabs")
                    .bindSelected(settings::chatGroupComposerBlocks)
                    .comment("Shows three or more panels as tabs. Turn off to stack them.")
            }
            row("Chat font size:") {
                intTextField(0..100)
                    .bindIntText(settings::chatFontSize)
                    .comment("0 follows the IDE UI font size.")
            }
            row("Remote Markdown images:") {
                comboBox(listOf("off", "ask", "on"))
                    .bindItem(settings::chatRemoteImages.toNullableProperty())
                    .comment("Ask shows a Load image button. On loads nearby images automatically. Image servers can see this host's IP address. No cookies or credentials are sent.")
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

        group("Dictation") {
            row { comment("Start Hex or Handy and configure its model and permissions first. Running Hex takes priority; otherwise running Handy is used. Apps are discovered in standard installation locations and PATH.") }
            row { comment("Click the mic again to finish and keep the text; right-click to cancel. Requires a local IDE host. Hex needs control CLI support.") }
            row { comment("Optional lifecycle logs track recording and processing. Disable Handy's Auto Submit option to review text before sending.") }
        }

        group("Notifications") {
            row {
                checkBox("Show desktop notifications when this project window is not focused")
                    .bindSelected(settings::notificationsNative)
                    .comment("Permissions, questions, completed replies and plans ready for review. Also requires IDE and operating-system notification permissions.")
            }
            row { comment("Play the water-bubble sound for selected events when this project window is not focused, including while another IDE window is active. Sound is independent of desktop notifications.") }
            row { checkBox("Permission requests, including subagents").bindSelected(settings::notificationsSoundPermission) }
            row { checkBox("Questions needing your answer").bindSelected(settings::notificationsSoundQuestion) }
            row { checkBox("Completed replies").bindSelected(settings::notificationsSoundCompleted) }
            row { checkBox("Plans ready for review").bindSelected(settings::notificationsSoundPlanReady) }
        }

        group("Permissions") {
            row("Default permission mode:") {
                comboBox(listOf("default", "auto", "full"))
                    .bindItem(settings::chatDefaultPermissionMode.toNullableProperty())
                    .comment("Default follows OpenCode's rules. Auto reviews eligible requests. Full grants access without confirmation.")
            }
            row("Auto-approve judge model:") {
                textField().bindText(settings::chatAutoApproveModel).columns(40)
                    .comment("Chat model for Auto mode, as provider/model. When a decision model is set, this model handles its failures. Leave empty to use OpenCode's small model or the current chat model.")
            }
            row("Decision model:") {
                textField().bindText(settings::decisionModel, settings::selectDecisionModel).columns(40)
                    .comment("Reviews permission details before the chat judge. Connect TypeSafe Jev in Models or set <code>TYPESAFE_API_KEY</code>, then use <code>typesafe/jev-latest</code>. Other routes require OpenCode 2.2.0 or later. Failures go to the chat judge. Leave empty to turn it off.")
            }
            row {
                checkBox("Show experimental OpenCode decision models in the Models view")
                    .bindSelected(settings::debugShowNativeDecisionModels)
                    .comment("Hiding this section does not disable a configured reviewer.")
            }
            row("Rejected-permission guidance:") {
                comboBox(listOf("default", "custom", "off"))
                    .bindItem(settings::permissionRejectionMode.toNullableProperty())
                    .comment("Sets the chat instructions for rejected permissions. Off adds no instructions. OpenCode still enforces permission rules.")
            }
            row("Custom rejection guidance:") {
                textArea().bindText(settings::permissionRejectionCustom)
                    .comment("Used in custom mode. Empty uses the built-in guidance.")
            }
        }

        group("Usage and Limits") {
            row("Pinned provider quotas:") {
                comboBox(listOf("rotating", "all"))
                    .bindItem(settings::providerLimitsStatusBarDisplay.toNullableProperty())
                    .comment("Rotate pinned providers every 15 seconds or show them all. Providers selected in visible chats are left out. Hover for details.")
            }
            row {
                checkBox("Show cache write tokens in usage reports")
                    .bindSelected(settings::usageReportsShowCacheWrite)
                    .comment("When off, cache writes count toward the Input column.")
            }
            row("Expired prompt cache warning:") {
                intTextField(0..100_000_000)
                    .bindIntText(settings::chatPromptCacheWarningTokens)
                    .comment("Warn when an idle session with at least this many context tokens has likely lost its prompt cache. Its next message can cost more. Set to 0 to turn the warning off.")
            }
        }

        group("Utilities") {
            row {
                checkBox("Suggest follow-up actions after Build responses")
                    .bindSelected(settings::chatFollowupsEnabled)
                    .comment("Requires OpenCode V2. Sends the request and final answer to a model, which can use paid tokens or subscription quota.")
            }
            row("Follow-up model:") {
                textField()
                    .bindText(settings::chatFollowupModel)
                    .comment("Provider/model for suggested next steps. Leave empty to use the session's model and reasoning, or choose Use for follow-ups in Models.")
                    .columns(40)
            }
            row("Prompt completion model:") {
                textField()
                    .bindText(settings::chatPromptCompletionModel)
                    .comment("Provider/model for suggestions as you type. Requires OpenCode V2 and a supported API-key connection. Your draft and recent prompts go to the provider. Tab accepts, Escape dismisses. Leave empty to turn it off.")
                    .columns(40)
            }
            row {
                checkBox("Trace auxiliary inference prompts")
                    .bindSelected(settings::chatPromptTrace)
                    .comment("For usage-tracking proxies. Adds a <code>VARRO_TRACE_V1</code> line with the request's purpose and session IDs or a workspace hash. The proxy must remove the line before it reaches the model.")
            }
            row("Commit message model:") {
                textField()
                    .bindText(settings::commitMessageModel)
                    .comment("`providerID/modelID`. Leave empty to let Varro choose.")
                    .columns(40)
            }
            row("Commit message rules:") {
                textArea().bindText(settings::commitMessageRules)
                    .comment("Style and format preferences, for example Conventional Commits with a scope. Cannot enable tools or commits.")
            }
            row("Walkthrough model:") {
                textField()
                    .bindText(settings::reviewModel)
                    .comment("Use provider/model or provider/model#variant to set the reasoning level. Leave empty to use the active chat's model and reasoning, or choose Use for reviews in Models.")
                    .columns(40)
            }
        }

        group("Agents") {
            row {
                checkBox("Add Varro's read-only `Ask` agent at runtime")
                    .bindSelected(settings::chatEnableAskAgent)
                    .comment("Applies to servers started by Varro. Existing configured Ask agents are preserved; no user configuration is written.")
            }
            row { comment("Configure automatic compaction in OpenCode. On-demand compaction remains available in chat.") }
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
