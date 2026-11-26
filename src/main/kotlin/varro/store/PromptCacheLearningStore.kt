package varro.store

import com.google.gson.JsonElement
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import varro.protocol.Json

/**
 * Learned prompt cache lifetimes, kept per IDE installation like upstream's global state:
 * accounts and plans span projects. Not shared across IDEs.
 */
@Service(Service.Level.APP)
@State(name = "VarroOpenJetPromptCacheLearning", storages = [Storage("varro-openjet-prompt-cache.xml")])
class PromptCacheLearningStore : PersistentStateComponent<PromptCacheLearningStore.StoreState> {
    class StoreState {
        @JvmField var learned: String = ""
    }

    private var state = StoreState()

    override fun getState(): StoreState = state

    override fun loadState(state: StoreState) {
        this.state = state
    }

    @Synchronized fun load(): JsonElement? = Json.parseOrNull(state.learned)

    @Synchronized fun save(value: JsonElement) {
        state.learned = Json.stringify(value)
    }

    companion object {
        fun getInstance(): PromptCacheLearningStore = service()
    }
}
