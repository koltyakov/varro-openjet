package varro.store

import com.google.gson.JsonObject
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import varro.protocol.Json
import varro.protocol.asObjectOrNull

/** Model preferences shared by every project in this IDE. */
@Service(Service.Level.APP)
@State(name = "VarroOpenJetModelStore", storages = [Storage("varro-openjet.xml")])
class VarroModelStore : PersistentStateComponent<VarroModelStore.StoreState> {
    class StoreState {
        @JvmField var modelPreferences: String = "{}"
        @JvmField var initialized: Boolean = false
    }

    private var state = StoreState()

    override fun getState(): StoreState = state

    override fun loadState(state: StoreState) {
        this.state = state
    }

    var modelPreferences: JsonObject
        @Synchronized get() = Json.parseOrNull(state.modelPreferences).asObjectOrNull() ?: JsonObject()
        @Synchronized set(value) {
            state.modelPreferences = Json.stringify(value)
            state.initialized = true
        }

    /** An old project or browser snapshot must never replace established global preferences. */
    @Synchronized
    fun migrate(preferences: JsonObject): Boolean {
        if (state.initialized || modelPreferences.size() > 0 || preferences.size() == 0) return false
        modelPreferences = preferences
        return true
    }

    fun interface Listener {
        fun preferencesChanged()
    }

    companion object {
        val TOPIC = Topic.create("Varro model preferences changed", Listener::class.java)

        fun getInstance(): VarroModelStore = service()
    }
}
