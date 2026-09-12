package varro.store

import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
import varro.protocol.str
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-scoped persistence for the state upstream keeps in VS Code's workspace
 * Memento.
 *
 * Everything here is stored as a JSON string rather than a typed bean. The
 * shapes - queued-message snapshots with their image and PDF
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

        /** Browser-only selections from older versions have been imported. */
        @JvmField var sessionSelectionsMigrated: Boolean = false

        /** `Record<sessionId, number | null>` - plan-skip markers. */
        @JvmField var sessionPlanState: String = "{}"

        /** `Record<sessionId, string>` - agent per session, paired with plan state. */
        @JvmField var sessionPlanAgents: String = "{}"

        /** Legacy project preferences, retained only for migration to VarroModelStore. */
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

        /** Completed session ids in the webview's visible Completed filter. */
        @JvmField var completedSessionUnreadIds: String = "[]"

        /** Ralph orchestration runs, keyed by manager session id. */
        @JvmField var ralphRuns: String = "{}"
        @JvmField var permissionRules: String = "{}"
    }

    private var state = StoreState()
    enum class SessionSelection { MODEL, PERMISSION_MODE }

    private val selectionListeners = CopyOnWriteArrayList<(SessionSelection) -> Unit>()

    override fun getState(): StoreState = state

    override fun loadState(state: StoreState) {
        this.state = state
    }

    // --- Object-valued entries ------------------------------------------------

    var sessionPermissionModes: JsonObject
        @Synchronized get() = readObject(state.sessionPermissionModes)
        @Synchronized set(value) {
            state.sessionPermissionModes = Json.stringify(value)
            selectionListeners.forEach { it(SessionSelection.PERMISSION_MODE) }
        }

    var sessionSelectedModels: JsonObject
        @Synchronized get() = readObject(state.sessionSelectedModels)
        @Synchronized set(value) {
            state.sessionSelectedModels = Json.stringify(value)
            selectionListeners.forEach { it(SessionSelection.MODEL) }
        }

    fun addSelectionListener(listener: (SessionSelection) -> Unit): () -> Unit {
        selectionListeners.add(listener)
        return { selectionListeners.remove(listener) }
    }

    @Synchronized
    fun updateSessionPermissionMode(sessionId: String, mode: JsonElement?) {
        sessionPermissionModes = updateSelection(sessionPermissionModes, sessionId, mode)
    }

    @Synchronized
    fun updateSessionModel(sessionId: String, model: JsonElement?) {
        sessionSelectedModels = updateSelection(sessionSelectedModels, sessionId, model)
    }

    @Synchronized
    fun migrateSessionPermissionModes(modes: JsonObject) {
        sessionPermissionModes = mergeMissingSelections(sessionPermissionModes, modes)
    }

    @Synchronized
    fun migrateSessionModels(models: JsonObject) {
        sessionSelectedModels = mergeMissingSelections(sessionSelectedModels, models)
    }

    /** Import before boot snapshots can replace browser-only selections with an empty host map. */
    @Synchronized
    fun migrateBrowserSessionSelections() {
        if (state.sessionSelectionsMigrated) return
        val browser = browserStorage()
        migrateSessionPermissionModes(readObject(browser.str("varro.sessionPermissionModes") ?: "{}"))
        migrateSessionModels(readObject(browser.str("varro.sessionSelectedModels") ?: "{}"))
        state.sessionSelectionsMigrated = true
    }

    private fun updateSelection(record: JsonObject, sessionId: String, value: JsonElement?): JsonObject {
        if (value == null || value.isJsonNull) record.remove(sessionId) else record.add(sessionId, value)
        return record
    }

    private fun mergeMissingSelections(current: JsonObject, legacy: JsonObject): JsonObject {
        legacy.entrySet().forEach { (id, value) ->
            if (!current.has(id) && !value.isJsonNull) current.add(id, value)
        }
        return current
    }

    var sessionPlanState: JsonObject
        get() = readObject(state.sessionPlanState)
        set(value) { state.sessionPlanState = Json.stringify(value) }

    var sessionPlanAgents: JsonObject
        @Synchronized get() = readObject(state.sessionPlanAgents)
        @Synchronized set(value) { state.sessionPlanAgents = Json.stringify(value) }

    @Synchronized
    fun updateSessionAgent(sessionId: String, agent: String) {
        sessionPlanAgents = sessionPlanAgents.apply { addProperty(sessionId, agent) }
    }

    var legacyModelPreferences: JsonObject
        get() = readObject(state.modelPreferences)
        set(value) { state.modelPreferences = Json.stringify(value) }

    var sessionUnreadState: JsonObject
        get() = readObject(state.sessionUnreadState)
        set(value) { state.sessionUnreadState = Json.stringify(value) }

    @Synchronized
    fun removeSessionUnreadState(sessionIds: Collection<String>) {
        val unread = sessionUnreadState
        var removed = false
        sessionIds.forEach { removed = unread.remove(it) != null || removed }
        if (removed) sessionUnreadState = unread
        completedSessionUnreadIds = completedSessionUnreadIds - sessionIds.toSet()
    }

    @Synchronized
    fun retainSessionUnreadState(sessionIds: Set<String>) {
        val unread = sessionUnreadState
        val removed = unread.keySet().removeIf { it !in sessionIds }
        if (removed) sessionUnreadState = unread
        completedSessionUnreadIds = completedSessionUnreadIds.filter { it in sessionIds }
    }

    var completedSessionUnreadIds: List<String>
        get() = readArray(state.completedSessionUnreadIds).strings()
        set(value) { state.completedSessionUnreadIds = Json.stringify(Json.array(value.distinct())) }

    var ralphRuns: JsonObject
        get() = readObject(state.ralphRuns)
        set(value) { state.ralphRuns = Json.stringify(value) }

    var permissionRules: JsonObject
        @Synchronized get() = readObject(state.permissionRules)
        @Synchronized set(value) { state.permissionRules = Json.stringify(value) }

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
