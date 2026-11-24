package varro.host

import com.google.gson.JsonObject
import varro.protocol.*

/** Maps browser `log` messages to IDE log levels. */
internal object WebviewLog {
    enum class Level { WARN, INFO, DEBUG }

    /**
     * Browser errors log as warnings, and warnings and explicit `info` diagnostics log as info.
     * Untagged messages stay at debug to keep routine browser chatter out of the IDE log.
     */
    fun entry(payload: JsonObject?): Pair<Level, String>? {
        val message = payload.str("msg") ?: return null
        val detail = (payload.str("error") ?: payload.str("data"))?.let { " - $it" }.orEmpty()
        return when (payload.str("level")) {
            "error" -> Level.WARN to "webview: $message$detail"
            "warn" -> Level.INFO to "webview: $message$detail"
            "info" -> Level.INFO to "webview: $message$detail"
            else -> Level.DEBUG to "webview: $message"
        }
    }
}
