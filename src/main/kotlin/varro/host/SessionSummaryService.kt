package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.asArrayOrNull
import varro.protocol.asObjectOrNull
import varro.protocol.obj
import varro.protocol.str
import java.net.URLEncoder

internal class SessionSummaryService(
    private val readLocal: (String) -> SessionSummary.History?,
    private val request: (String, String?) -> JsonElement?,
) {
    fun read(sessionId: String, directory: String?): JsonObject {
        readLocal(sessionId)?.let { return SessionSummary.summarize(it) }
        fun encoded(id: String) = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")
        fun records(path: String) = request(path, directory).asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull() } ?: error("OpenCode returned invalid session history: $path")
        val diffs = request("/session/${encoded(sessionId)}/diff", directory)
        val messages = records("/session/${encoded(sessionId)}/message")
        val sessions = records("/session?limit=1000000")
        val children = sessions.groupBy { it.str("parentID") }
        val seen = mutableSetOf(sessionId)
        val pending = ArrayDeque<String>().apply { add(sessionId) }
        val descendants = mutableListOf<SessionSummary.Descendant>()
        while (pending.isNotEmpty()) {
            for (child in children[pending.removeFirst()].orEmpty()) {
                val id = child.str("id") ?: continue
                if (!seen.add(id)) continue
                pending.add(id)
                descendants.add(SessionSummary.Descendant(child.obj("tokens"), records("/session/${encoded(id)}/message")))
            }
        }
        return SessionSummary.summarize(SessionSummary.History(messages, descendants), diffs)
    }
}
