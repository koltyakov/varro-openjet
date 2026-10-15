package varro.settings

import com.intellij.openapi.components.*

/** Repository overrides live in IDE workspace state, never in an untrusted repository file. */
@Service(Service.Level.PROJECT)
@State(name = "VarroCommitMessageRules", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CommitMessageRules : PersistentStateComponent<CommitMessageRules.State> {
    class State { var repositories: MutableMap<String, String> = mutableMapOf() }
    private var saved = State()
    @Synchronized override fun getState(): State = State().also { it.repositories.putAll(saved.repositories) }
    @Synchronized override fun loadState(state: State) { saved = state }
    @Synchronized fun resolve(root: String, default: String): String = saved.repositories[root] ?: default
    @Synchronized fun set(root: String, rules: String) {
        if (rules.isBlank()) saved.repositories.remove(root) else saved.repositories[root] = rules.trim()
    }
}
