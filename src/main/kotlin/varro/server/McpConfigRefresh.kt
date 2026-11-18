package varro.server

/** Saving defaults is independent of active work. Only the optional runtime reload waits for idle. */
internal class McpConfigRefresh(
    private val idle: () -> Boolean,
    private val refresh: (global: Boolean) -> Boolean,
) {
    private var pending: Boolean? = null
    private var revision = 0L

    @Synchronized fun request(global: Boolean) {
        pending = pending == true || global
        revision++
    }

    fun flush() {
        val (global, version) = synchronized(this) { (pending ?: return) to revision }
        if (!idle() || !refresh(global)) return
        synchronized(this) { if (revision == version) pending = null }
    }
}
