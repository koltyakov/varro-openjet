package varro.host

import com.google.gson.JsonObject
import varro.protocol.bool

/** Direction of a `command/navigate-history` request. */
enum class SessionHistoryDirection(val wire: String) { BACK("back"), FORWARD("forward") }

/**
 * Session history availability a webview reports in `commands/state`.
 *
 * Port of `CommandHistoryState` in upstream `message-router.ts` and the matching
 * `parseWebviewMessage` checks. The webview owns the history itself; the host only
 * keeps these flags to enable Back and Forward.
 */
data class CommandHistoryState(val canNavigateBack: Boolean = false, val canNavigateForward: Boolean = false) {

    fun allows(direction: SessionHistoryDirection) = when (direction) {
        SessionHistoryDirection.BACK -> canNavigateBack
        SessionHistoryDirection.FORWARD -> canNavigateForward
    }

    companion object {
        val NONE = CommandHistoryState()

        /**
         * Missing flags mean the view cannot navigate, as older webviews omit them.
         * A present non-boolean flag returns `null`, because upstream rejects the
         * whole `commands/state` message.
         */
        fun parse(payload: JsonObject?): CommandHistoryState? {
            fun flag(name: String): Boolean? = if (payload?.has(name) != true) false else payload.bool(name)
            return CommandHistoryState(
                canNavigateBack = flag("canNavigateBack") ?: return null,
                canNavigateForward = flag("canNavigateForward") ?: return null,
            )
        }
    }
}
