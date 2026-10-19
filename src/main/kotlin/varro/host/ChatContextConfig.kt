package varro.host

import com.google.gson.JsonObject
import varro.settings.VarroSettings

/** Keep boot snapshots and live updates on the same context-attachment contract. */
internal fun JsonObject.addChatContextConfig(settings: VarroSettings) {
    addProperty("autoAttachProblems", settings.chatEnableProblemsContext)
    addProperty("autoAttachActiveFile", settings.chatAutoAttachActiveFile)
}
