package dev.koltyakov.varrojet.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings, the JetBrains counterpart of Varro's `varro.*`
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

    /** Port OpenCode serves on. Upstream default; restart the server to apply. */
    var serverPort: Int = DEFAULT_SERVER_PORT

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

    /** `left` or `right`; which side the sessions pane takes on wide layouts. */
    var chatDesktopSessionPaneSide: String = "right"

    /** `default`, `auto` or `full`. */
    var chatDefaultPermissionMode: String = "auto"

    /** `providerID/modelID` for the auto-approve judge; empty lets Varro pick. */
    var chatAutoApproveModel: String = ""

    var chatAutoRenameUntitledSessions: Boolean = false
    var chatEnableAskAgent: Boolean = true
    var chatAutoCompact: Boolean = true
    var chatAutoCompactionReservedTokens: Int = 4096

    /**
     * Chat font size. `0` follows the IDE editor font size, which is the closest
     * JetBrains analogue of VS Code's `chat.fontSize`.
     */
    var chatFontSize: Int = 0

    /** Font size for code blocks. `0` follows the IDE editor font size. */
    var chatEditorFontSize: Int = 0

    /** Font family override; `default` follows the IDE. */
    var chatFontFamily: String = "default"

    // --- commit message -------------------------------------------------------

    /** `providerID/modelID` used to generate commit messages; empty auto-selects. */
    var commitMessageModel: String = ""

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

    fun normalizedPort(): Int = serverPort.takeIf { it in 1..65_535 } ?: DEFAULT_SERVER_PORT

    fun permissionMode(): String =
        chatDefaultPermissionMode.takeIf { it in PERMISSION_MODES } ?: "default"

    fun sessionPaneSide(): String =
        chatDesktopSessionPaneSide.takeIf { it == "left" || it == "right" } ?: "right"

    companion object {
        const val DEFAULT_SERVER_PORT = 4096
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
