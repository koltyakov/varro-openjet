package varro.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import varro.protocol.Json
import varro.protocol.asArrayOrNull
import varro.protocol.asObjectOrNull
import varro.protocol.strings

/**
 * Project-scoped persistence for the state upstream keeps in VS Code's workspace
 * Memento.
 *
 * Everything here is stored as a JSON string rather than a typed bean. The
 * shapes - model preferences, queued-message snapshots with their image and PDF
 * attachments, plan state - are owned and versioned by the webview, and upstream
 * treats the host as a dumb durable store for them too. Modelling them in Kotlin
 * would add a second schema to keep in sync for no benefit, and would drop
 * fields whenever the webview added one.
 *
 * The [XmlSerializer][com.intellij.util.xmlb.XmlSerializer] only sees `String`
 * fields, which keeps the on-disk format stable and diffable.
 */
@Service(Service.Level.PROJECT)
@State(name = "VarroOpenJetStore", storages = [Storage("varro-openjet.xml")])
class VarroStore : PersistentStateComponent<VarroStore.StoreState> {

    class StoreState {
        /** `Record<sessionId, PermissionMode>` */
        @JvmField var sessionPermissionModes: String = "{}"

        /** `Record<sessionId, ChatModelSelection>` */
        @JvmField var sessionSelectedModels: String = "{}"

        /** `Record<sessionId, number | null>` - plan-skip markers. */
        @JvmField var sessionPlanState: String = "{}"

        /** `Record<sessionId, string>` - agent per session, paired with plan state. */
        @JvmField var sessionPlanAgents: String = "{}"

        /** Upstream's `ModelPreferences`: pins, hidden entries, display names, order. */
        @JvmField var modelPreferences: String = "{}"

        /** `string[]` of pinned root session ids, in display order. */
        @JvmField var pinnedSessionIds: String = "[]"

        /** `string[]` of sessions hidden from the user-visible catalog (helper sessions). */
        @JvmField var hiddenSessionIds: String = "[]"

        /** `QueuedMessageSnapshot[]` - the authoritative copy, including attachments. */
        @JvmField var queuedMessages: String = "[]"

        /** `RecycleBinEntry[]` - recycled session trees awaiting restore or expiry. */
        @JvmField var recycleBin: String = "[]"

        /** `directory` | `descendants` | `project` */
        @JvmField var sessionHistoryScope: String = "directory"

        /** Mirror of the webview's `vscode.getState()` snapshot, per surface. */
        @JvmField var viewStates: String = "{}"
        @JvmField var browserStorage: String = "{}"
        @JvmField var editorRoutes: String = "{}"

        /** Session ids whose run was interrupted by a reload and can be resumed. */
        @JvmField var interruptedSessionIds: String = "[]"

        /** `Record<sessionId, { kind, unread, markerAt }>` - completed/plan-ready badges. */
        @JvmField var sessionUnreadState: String = "{}"

        /** Ralph orchestration runs, keyed by manager session id. */
        @JvmField var ralphRuns: String = "{}"
    }

    private var state = StoreState()

    override fun getState(): StoreState = state

    override fun loadState(state: StoreState) {
        this.state = state
    }

    // --- Object-valued entries ------------------------------------------------

    var sessionPermissionModes: JsonObject
        get() = readObject(state.sessionPermissionModes)
        set(value) { state.sessionPermissionModes = Json.stringify(value) }

    var sessionSelectedModels: JsonObject
        get() = readObject(state.sessionSelectedModels)
        set(value) { state.sessionSelectedModels = Json.stringify(value) }

    var sessionPlanState: JsonObject
        get() = readObject(state.sessionPlanState)
        set(value) { state.sessionPlanState = Json.stringify(value) }

    var sessionPlanAgents: JsonObject
        get() = readObject(state.sessionPlanAgents)
        set(value) { state.sessionPlanAgents = Json.stringify(value) }

    var modelPreferences: JsonObject
        get() = readObject(state.modelPreferences)
        set(value) { state.modelPreferences = Json.stringify(value) }

    var sessionUnreadState: JsonObject
        get() = readObject(state.sessionUnreadState)
        set(value) { state.sessionUnreadState = Json.stringify(value) }

    var ralphRuns: JsonObject
        get() = readObject(state.ralphRuns)
        set(value) { state.ralphRuns = Json.stringify(value) }

    var editorRoutes: JsonObject
        get() = readObject(state.editorRoutes)
        set(value) { state.editorRoutes = Json.stringify(value) }

    // --- Array-valued entries -------------------------------------------------

    var pinnedSessionIds: List<String>
        get() = readArray(state.pinnedSessionIds).strings()
        set(value) { state.pinnedSessionIds = Json.stringify(Json.array(value)) }

    var hiddenSessionIds: Set<String>
        get() = readArray(state.hiddenSessionIds).strings().toSet()
        set(value) { state.hiddenSessionIds = Json.stringify(Json.array(value)) }

    var interruptedSessionIds: List<String>
        get() = readArray(state.interruptedSessionIds).strings()
        set(value) { state.interruptedSessionIds = Json.stringify(Json.array(value.distinct())) }

    var queuedMessages: JsonArray
        get() = readArray(state.queuedMessages)
        set(value) { state.queuedMessages = Json.stringify(value) }

    var recycleBin: JsonArray
        get() = readArray(state.recycleBin)
        set(value) { state.recycleBin = Json.stringify(value) }

    // --- Scalars --------------------------------------------------------------

    var sessionHistoryScope: String
        get() = state.sessionHistoryScope.takeIf { it in HISTORY_SCOPES } ?: "directory"
        set(value) { if (value in HISTORY_SCOPES) state.sessionHistoryScope = value }

    // --- Per-surface view state -----------------------------------------------

    @Synchronized
    fun browserStorage(): JsonObject = readObject(state.browserStorage)

    @Synchronized
    fun updateBrowserStorage(key: String, value: String?) {
        val storage = readObject(state.browserStorage)
        if (value == null) storage.remove(key) else storage.addProperty(key, value)
        state.browserStorage = Json.stringify(storage)
    }

    /** The webview's `vscode.getState()` snapshot for one surface. */
    @Synchronized
    fun viewState(viewId: String): JsonObject {
        val saved = readObject(state.viewStates).get(viewId).asObjectOrNull() ?: JsonObject()
        // Read snapshots produced before the bridge envelope was unwrapped.
        return saved.get("state").asObjectOrNull() ?: saved
    }

    @Synchronized
    fun setViewState(viewId: String, value: JsonObject) {
        val all = readObject(state.viewStates)
        all.add(viewId, value)
        state.viewStates = Json.stringify(all)
    }

    // --- Helpers --------------------------------------------------------------

    /**
     * A malformed or hand-edited entry degrades to empty rather than failing the
     * whole component load, which would take the tool window down with it.
     */
    private fun readObject(raw: String): JsonObject =
        Json.parseOrNull(raw).asObjectOrNull() ?: JsonObject()

    private fun readArray(raw: String): JsonArray =
        Json.parseOrNull(raw).asArrayOrNull() ?: JsonArray()

    companion object {
        private val HISTORY_SCOPES = setOf("directory", "descendants", "project")

        fun getInstance(project: Project): VarroStore = project.service()
    }
}
