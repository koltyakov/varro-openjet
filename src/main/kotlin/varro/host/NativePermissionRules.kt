package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import varro.protocol.*

/** Native last-match semantics. Unknown rules cannot preserve an earlier grant. */
internal object NativePermissionRules {
    private data class Rule(val action: String, val resource: String, val effect: String)
    private fun parse(value: JsonElement): Rule? {
        val rule = value.asObjectOrNull()
        return Rule(rule.str("action") ?: return null, rule.str("resource") ?: return null,
            rule.str("effect")?.takeIf { it in setOf("allow", "ask", "deny") } ?: return null)
    }
    private val windows = System.getProperty("os.name").lowercase().contains("windows")
    private fun normalize(value: String) = value.replace('\\', '/').let { if (windows) it.lowercase() else it }
    private fun matches(value: String, pattern: String): Boolean {
        val normalized = normalize(pattern)
        val optionalArguments = normalized.endsWith(" *")
        val source = (if (optionalArguments) normalized.dropLast(2) else normalized).map {
            when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) }
        }.joinToString("") + if (optionalArguments) "( .*)?" else ""
        return Regex("^$source$", RegexOption.DOT_MATCHES_ALL).matches(normalize(value))
    }

    fun resolve(rules: JsonArray, action: String, resource: String): String? {
        var effect: String? = null
        for (value in rules) {
            val rule = parse(value) ?: return null
            if (matches(action, rule.action) && matches(resource, rule.resource)) effect = rule.effect
        }
        return effect
    }

    private fun covers(existing: String, proposed: String): Boolean {
        val left = normalize(existing)
        val right = normalize(proposed)
        if (left == right || left == "*") return true
        if (right.none { it == '*' || it == '?' }) return matches(right, left)
        if (!left.endsWith('*') || left.dropLast(1).any { it == '*' || it == '?' }) return false
        return right.startsWith(left.dropLast(1))
    }

    private fun mayOverlap(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.none { it == '*' || it == '?' }) return matches(a, b)
        if (b.none { it == '*' || it == '?' }) return matches(b, a)
        val prefixA = a.takeWhile { it != '*' && it != '?' }
        val prefixB = b.takeWhile { it != '*' && it != '?' }
        return prefixA.startsWith(prefixB) || prefixB.startsWith(prefixA)
    }

    fun appendAllows(existing: JsonArray, action: String, patterns: List<String>): JsonArray {
        val result = existing.deepCopy()
        for (resource in patterns.distinct()) {
            val covered = result.withIndex().any { (index, value) ->
                val rule = parse(value)
                rule?.effect == "allow" && matches(action, rule.action) && covers(rule.resource, resource) &&
                    result.drop(index + 1).all { later ->
                        parse(later)?.let {
                            it.effect == "allow" || !matches(action, it.action) || !mayOverlap(resource, it.resource)
                        } == true
                    }
            }
            if (!covered) result.add(Json.obj("action" to action, "resource" to resource, "effect" to "allow"))
        }
        return result
    }
}
