package varro.server

/** Clock and operations are injected so idle, retry and version transitions can be tested. */
class IdleMaintenance(
    private val enabled: () -> Boolean,
    private val idle: () -> Boolean,
    private val upgrade: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var quietSince: Long? = null
    private var nextAttempt = 0L

    @Synchronized fun tick() {
        val time = now()
        if (!enabled() || !idle()) { quietSince = null; return }
        val since = quietSince ?: time.also { quietSince = it }
        if (time - since < 60_000 || time < nextAttempt) return
        // Also serves as cross-project exclusion for a CLI installation shared by IDE windows.
        synchronized(UPDATE_LOCK) {
            if (time < globalNextAttempt || !enabled() || !idle()) return
            nextAttempt = time + 15 * 60_000
            globalNextAttempt = nextAttempt
            if (upgrade()) {
                nextAttempt = time + 6 * 60 * 60_000
                globalNextAttempt = nextAttempt
            }
        }
    }

    companion object {
        private val UPDATE_LOCK = Any()
        private var globalNextAttempt = 0L
    }
}
