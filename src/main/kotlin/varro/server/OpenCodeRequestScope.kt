package varro.server

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A request URL plus the directory it ended up scoped to. */
data class ScopedRequest(val url: String, val directory: String?)

/**
 * Attaches workspace scope to an OpenCode request.
 *
 * Port of `src/extension/util/opencode-request.ts`. OpenCode resolves sessions
 * per directory, so every non-global request carries the workspace both as a
 * query parameter and as a header. Routes under `/api` additionally want the
 * bracketed `location[directory]` form.
 */
object OpenCodeRequestScope {

    const val DIRECTORY_HEADER: String = "x-opencode-directory"

    fun scope(baseUrl: String, path: String, directory: String?): ScopedRequest {
        // Guard against a webview-supplied path escaping to another origin.
        // `//host/path` is a protocol-relative URL, not a path.
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw IllegalArgumentException("Unsupported OpenCode API path: $path")
        }

        val base = URI.create(baseUrl)
        val pathname = path.substringBefore('?').substringBefore('#')
        val rawQuery = path.substringAfter('?', "").substringBefore('#')
        val params = parseQuery(rawQuery)

        val normalizedDirectory = normalizeDirectory(directory)
        val isGlobalPath = pathname.startsWith("/global/")
        val isApiPath = pathname.startsWith("/api/")

        val explicitDirectory = if (params.containsKey("directory")) {
            normalizeDirectory(params["directory"]?.firstOrNull())
        } else {
            null
        }
        val explicitLocationDirectory = if (params.containsKey("location[directory]")) {
            normalizeDirectory(params["location[directory]"]?.firstOrNull())
        } else {
            null
        }

        if (!isGlobalPath) {
            when {
                explicitDirectory != null -> params["directory"] = mutableListOf(explicitDirectory)
                params.containsKey("directory") -> params.remove("directory")
                normalizedDirectory != null -> params["directory"] = mutableListOf(normalizedDirectory)
            }

            if (isApiPath) {
                val locationDirectory = explicitDirectory ?: normalizedDirectory
                when {
                    explicitLocationDirectory != null ->
                        params["location[directory]"] = mutableListOf(explicitLocationDirectory)
                    params.containsKey("location[directory]") -> params.remove("location[directory]")
                    locationDirectory != null ->
                        params["location[directory]"] = mutableListOf(locationDirectory)
                }
            }
        }

        val scopedDirectory = if (!isGlobalPath) {
            explicitLocationDirectory ?: explicitDirectory ?: normalizedDirectory
        } else {
            normalizedDirectory
        }

        val query = buildQuery(params)
        val url = buildString {
            append(base.scheme).append("://").append(base.authority)
            append(pathname)
            if (query.isNotEmpty()) append('?').append(query)
        }
        return ScopedRequest(url, scopedDirectory)
    }

    fun directoryHeaders(directory: String?): Map<String, String> =
        if (directory.isNullOrEmpty()) emptyMap() else mapOf(DIRECTORY_HEADER to directory)

    /**
     * Trims trailing separators but otherwise preserves the original spelling.
     * OpenCode session lookups on Windows regress when drive casing or path
     * separators are rewritten, so path identity is left alone.
     */
    fun normalizeDirectory(directory: String?): String? {
        val trimmed = directory?.trim()
        if (trimmed.isNullOrEmpty()) return null
        // A bare drive root (`C:\`) or UNC share root has no redundant separator to drop.
        if (DRIVE_ROOT.matches(trimmed) || UNC_ROOT.matches(trimmed)) return trimmed
        return trimmed.trimEnd('/', '\\').ifEmpty { trimmed }
    }

    private val DRIVE_ROOT = Regex("""^[A-Za-z]:[\\/]+$""")
    private val UNC_ROOT = Regex("""^(?:\\\\|//)[^\\/]+[\\/][^\\/]+[\\/]*$""")

    private fun parseQuery(rawQuery: String): LinkedHashMap<String, MutableList<String>> {
        val params = LinkedHashMap<String, MutableList<String>>()
        if (rawQuery.isEmpty()) return params
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = if (pair.contains('=')) decode(pair.substringAfter('=')) else ""
            params.getOrPut(key) { mutableListOf() }.add(value)
        }
        return params
    }

    private fun buildQuery(params: Map<String, List<String>>): String =
        params.entries
            .flatMap { (key, values) -> values.map { "${encode(key)}=${encode(it)}" } }
            .joinToString("&")

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun decode(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8)
        }.getOrDefault(value)
}
