package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Optional APIs are discovered from the connected server, not its version string. */
internal data class OpenCodeApiCapabilities(val sessionArchiving: Boolean, val savedPermissionEditing: Boolean) {
    companion object {
        fun read(value: JsonElement?): OpenCodeApiCapabilities {
            val spec = value.asObjectOrNull()
            fun properties(path: String) = properties(spec.obj("paths").obj(path).obj("patch")
                .obj("requestBody").obj("content").obj("application/json")?.get("schema"), spec, 0)
            return OpenCodeApiCapabilities(properties("/api/session/{sessionID}").contains("archived"),
                properties("/api/permission/saved/{id}").containsAll(listOf("action", "resource")))
        }

        private fun properties(value: JsonElement?, spec: JsonObject?, depth: Int): Set<String> {
            val schema = value.asObjectOrNull() ?: return emptySet()
            if (depth > 8) return emptySet()
            schema.str("\$ref")?.takeIf { it.startsWith("#/components/schemas/") }?.let {
                val name = it.removePrefix("#/components/schemas/").replace("~1", "/").replace("~0", "~")
                return properties(spec.obj("components").obj("schemas")?.get(name), spec, depth + 1)
            }
            return schema.obj("properties")?.keySet().orEmpty() + listOf("allOf", "anyOf", "oneOf").flatMap { key ->
                schema.arr(key).orEmpty().flatMap { properties(it, spec, depth + 1) }
            }
        }
    }
}
