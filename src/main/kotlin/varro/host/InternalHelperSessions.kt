package varro.host

import com.google.gson.JsonObject
import varro.protocol.*

/** Recognize server-side helpers from other Varro hosts, including older completion helpers. */
internal object InternalHelperSessions {
    fun isHidden(session: JsonObject): Boolean {
        if (session.obj("metadata").str("varroInternal") in setOf("permission-judge", "commit-message", "prompt-completion")) return true
        val title = session.str("title").orEmpty()
        val named = title.startsWith("Varro prompt completion: ") || title.startsWith("Varro permission judge: ") ||
            title.removePrefix("Varro commit message: ").takeIf { title.startsWith("Varro commit message: ") }?.matches(Regex("[1-9][0-9]*")) == true
        if (!named) return false
        val rules = session.arr("permission")?.toList()?.map { it.asObjectOrNull() } ?: return false
        if (rules.size < 2) return false
        val last = rules.last()
        return last.str("permission") == "StructuredOutput" && last.str("pattern") == "*" && last.str("action") == "allow" &&
            rules.dropLast(1).any { it.str("permission") == "*" } &&
            rules.dropLast(1).all { it.str("permission") != null && it.str("pattern") == "*" && it.str("action") == "deny" }
    }
}
