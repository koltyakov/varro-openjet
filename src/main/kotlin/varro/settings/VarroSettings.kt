package varro.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings cache, the JetBrains counterpart of Varro's `varro.*`
 * configuration. OpenJetSharedSettings synchronizes an explicit set of core fields
 * across IDEs; appearance fields remain local.
 *
 * The JetBrains settings UI uses this component for Varro's
 * VS Code configuration. Keys keep their upstream names so the webview's
 * settings deep links (`vscode/open-settings` with a `varro.chat.…` query)
 * still resolve to something meaningful.
 */
@Service(Service.Level.APP)
@State(
    name = "VarroOpenJetSettings",
    storages = [Storage(value = "varro-openjet.xml", roamingType = RoamingType.DEFAULT)],
)
class VarroSettings : PersistentStateComponent<VarroSettings> {

    // --- server ---------------------------------------------------------------

    /** Zero selects a port automatically; a positive configured port is fixed. */
    var serverPort: Int = 0

    /** Explicit path to the OpenCode CLI. Empty means "discover on PATH". */
    var serverCommand: String = ""

    /** Install OpenCode CLI updates in the background when the server is idle. */
    var serverAutoUpdate: Boolean = true

    /** Start OpenCode automatically the first time the tool window needs it. */
    var serverAutoStart: Boolean = true

    // --- chat -----------------------------------------------------------------

    var chatShowFileDiffs: Boolean = false
    var chatExpandThinking: Boolean = false
    var chatShowChangedFiles: Boolean = false
    var chatShowTurnTimer: Boolean = true
    var chatGroupComposerBlocks: Boolean = true
    /** Application-only consent. Repository configuration must never enable network requests. */
    var chatRemoteImages: String = "ask"
    /** Restored by new editor/window chats; each open chat keeps its own toggle. */
    var windowChatThemeReversed: Boolean = false
    /** Mirrors `varro.chat.autoAttachProblems`; retain the persisted name for existing preferences. */
    var chatEnableProblemsContext: Boolean = false
    /** Automatic file/selection context only. Explicit attachments and database context are unaffected. */
    var chatAutoAttachActiveFile: Boolean = true

    /** `left` or `right`; which side the sessions pane takes on wide layouts. */
    var chatDesktopSessionPaneSide: String = "right"

    /** `default`, `auto` or `full`. */
    var chatDefaultPermissionMode: String = "auto"

    /** `providerID/modelID` for the auto-approve judge; empty lets Varro pick. */
    var chatAutoApproveModel: String = ""

    /** The decision reviewer route, shared under its existing persistence key. */
    var chatAutoApproveDecisionModel: String = ""
    /** Hides only the experimental Models section, not a configured reviewer. */
    var debugShowNativeDecisionModels: Boolean = false
    /** Application settings only; a repository cannot supply system instructions here. */
    var permissionRejectionMode: String = "default"
    var permissionRejectionCustom: String = ""

    /** Direct API autocomplete; empty disables it. Requires an API key and OpenCode V2. */
    var chatPromptCompletionModel: String = ""

    /** Optional extra inference after completed Build responses. Application settings only. */
    var chatFollowupsEnabled: Boolean = true
    /** Empty uses the current session model and reasoning. */
    var chatFollowupModel: String = ""
    /** Application-only opt-in. Sends correlation identifiers as auxiliary prompt text. */
    var chatPromptTrace: Boolean = false

    /**
     * Mirrors `varro.chat.cacheWarningTokens`: warn above the composer when an idle
     * session's prompt cache has likely expired and its context holds at least this many
     * tokens. `0` turns the warning off.
     */
    var chatPromptCacheWarningTokens: Int = DEFAULT_PROMPT_CACHE_WARNING_TOKENS

    /** Legacy local Jev opt-in, used until the user writes a unified decision selection. */
    var decisionsJevAutoApprove: Boolean = false

    fun decisionModel(): String = chatAutoApproveDecisionModel.trim().ifEmpty {
        if (decisionsJevAutoApprove) varro.host.JevDecisions.DIRECT_ROUTE else ""
    }

    fun selectDecisionModel(route: String) {
        chatAutoApproveDecisionModel = route.trim()
        decisionsJevAutoApprove = false
    }

    var chatAutoRenameUntitledSessions: Boolean = false
    var chatEnableAskAgent: Boolean = true

    /**
     * Chat font size. `0` follows the IDE editor font size, which is the closest
     * JetBrains analogue of VS Code's `chat.fontSize`.
     */
    var chatFontSize: Int = 0

    /** Font size for code blocks. `0` follows the IDE editor font size. */
    var chatEditorFontSize: Int = 0

    /** Optional absolute Handy executable path. Empty searches standard locations and PATH. */
    var dictationHandyPath: String = ""
    var providerLimitsStatusBarDisplay: String = "rotating"
    /** Show cache writes separately; when off, they are added to Input. */
    var usageReportsShowCacheWrite: Boolean = false

    /** Font family override; `default` follows the IDE. */
    var chatFontFamily: String = "default"

    // Notification preferences stay local to each IDE. All channels are opt-in.
    var notificationsNative: Boolean = false
    var notificationsSoundPermission: Boolean = false
    var notificationsSoundQuestion: Boolean = false
    var notificationsSoundCompleted: Boolean = false
    var notificationsSoundPlanReady: Boolean = false

    // --- commit message -------------------------------------------------------

    /** `providerID/modelID` used to generate commit messages; empty auto-selects. */
    var commitMessageModel: String = ""
    var commitMessageRules: String = ""

    /**
     * Mirrors `varro.walkthrough.model`: `provider/model` or `provider/model#variant` for change
     * walkthroughs. Empty uses the active chat's selected model and reasoning.
     */
    var reviewModel: String = ""

    // --- JetBrains-specific ---------------------------------------------------

    /**
     * Offscreen rendering for the JCEF browser. Windowed rendering is faster and
     * handles IME better, but breaks transparency and some Linux window managers,
     * so it stays switchable.
     */
    var webviewOffscreenRendering: Boolean = false

    override fun getState(): VarroSettings = this

    override fun loadState(state: VarroSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun fixedServerPort(): Int? = serverPort.takeIf { it in 1..65_535 }

    fun remoteImageMode(): String = chatRemoteImages.takeIf { it == "off" || it == "on" } ?: "ask"

    fun permissionMode(): String =
        chatDefaultPermissionMode.takeIf { it in PERMISSION_MODES } ?: "default"

    fun promptCacheWarningTokens(): Int = chatPromptCacheWarningTokens.takeIf { it >= 0 } ?: DEFAULT_PROMPT_CACHE_WARNING_TOKENS

    fun sessionPaneSide(): String =
        chatDesktopSessionPaneSide.takeIf { it == "left" || it == "right" } ?: "right"

    companion object {
        const val DEFAULT_SERVER_PORT = 4096
        /** `package.json` default for `varro.chat.cacheWarningTokens`. */
        const val DEFAULT_PROMPT_CACHE_WARNING_TOKENS = 300_000
        private val PERMISSION_MODES = setOf("default", "auto", "full")

        /** Fires after any settings change so open tool windows can re-push config. */
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Varro settings", Listener::class.java)

        fun getInstance(): VarroSettings = service()
    }

    fun interface Listener {
        fun settingsChanged()
    }
}
