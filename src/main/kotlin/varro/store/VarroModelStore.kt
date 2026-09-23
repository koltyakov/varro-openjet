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
import varro.protocol.arr
import varro.protocol.obj
import varro.protocol.strings

/** IDE-local model cache, synchronized across products by OpenJetSharedSettings once initialized. */
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

    /** Apply only the changes made by this view, retaining concurrent edits from other chats. */
    @Synchronized
    fun update(base: JsonObject?, next: JsonObject) {
        if (base == null) {
            modelPreferences = next
            return
        }
        val merged = modelPreferences
        for (key in listOf("hiddenProviders", "hiddenModels", "addedModels", "removedModels", "pinnedModels")) {
            val before = base.arr(key)?.strings().orEmpty().toSet()
            val after = next.arr(key)?.strings().orEmpty()
            val removed = before - after.toSet()
            merged.add(key, Json.array((merged.arr(key)?.strings().orEmpty().filterNot { it in removed } +
                after.filterNot { it in before }).distinct()))
        }
        for (key in listOf("providerOrder", "modelOrder")) {
            val order = if (base.get(key) != next.get(key)) next.arr(key) else merged.arr(key)
            merged.add(key, order?.deepCopy() ?: Json.array(emptyList<String>()))
        }
        for (key in listOf("modelVariantSelections", "modelDisplayNames")) {
            val before = base.obj(key) ?: JsonObject()
            val after = next.obj(key) ?: JsonObject()
            val values = merged.obj(key) ?: JsonObject()
            for (name in before.keySet() + after.keySet()) {
                if (before.get(name) == after.get(name)) continue
                if (after.has(name)) values.add(name, after.get(name).deepCopy()) else values.remove(name)
            }
            merged.add(key, values)
        }
        modelPreferences = merged
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
