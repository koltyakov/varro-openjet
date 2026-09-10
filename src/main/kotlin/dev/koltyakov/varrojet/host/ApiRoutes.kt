package dev.koltyakov.varrojet.host

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Allowlist for `api/request` paths.
 *
 * Port of `isAllowedApiRequest` in `src/extension/util/webview-message.ts`. This
 * is the host's authorization boundary: the webview can only reach routes named
 * here, with the methods and query parameters named here. Anything else is
 * rejected before it reaches OpenCode, so a compromised or buggy webview cannot
 * turn the host into an open proxy onto the OpenCode API.
 *
 * The table is deliberately explicit rather than pattern-based. Each route
 * states its methods and exactly which query keys it tolerates, because several
 * OpenCode endpoints change scope entirely based on a query parameter.
 */
object ApiRoutes {

    const val NAMESPACE = "/varro"

    object Endpoints {
        const val PROVIDER_LIMIT = "$NAMESPACE/provider-limit"
        const val PLAN_OPEN = "$NAMESPACE/plan/open"
        const val OPENCODE_CONFIG = "$NAMESPACE/opencode-config"
        const val OPENCODE_CONFIG_MODEL_ROUTING = "$NAMESPACE/opencode-config/model-routing"
        const val OPENCODE_CONFIG_PERMISSIONS = "$NAMESPACE/opencode-config/permissions"
        const val SESSION = "$NAMESPACE/session"
        const val SESSION_TRASH = "$NAMESPACE/session-trash"
        const val SESSION_HISTORY_SCOPE = "$NAMESPACE/session-history-scope"
        const val WORKSPACE_FILE = "$NAMESPACE/workspace-file"
        const val WORKSPACE_FILE_PICK = "$NAMESPACE/workspace-file/pick"
        const val WORKSPACE_PATH_RESOLVE = "$NAMESPACE/workspace-path/resolve"
        const val PERMISSION_JUDGE = "$NAMESPACE/permission/judge"
        const val PERMISSION_JUDGE_MODEL = "$NAMESPACE/permission/judge/model"
        const val PERMISSION_SESSION_ALLOW = "$NAMESPACE/permission/session-allow"
        const val PERMISSION_SESSION_RULES = "$NAMESPACE/permission/session-rules"
        const val PERMISSION_SERVER_MEMORY = "$NAMESPACE/permission/server-memory"
        const val PERMISSION_PROJECT_ALLOW = "$NAMESPACE/permission/project-allow"
    }

    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_QUERY_LENGTH = 2048
    private const val MAX_SEGMENT_LENGTH = 512
    private const val MAX_SESSION_PAGE_LIMIT = 1_000_000

    /** Session sub-resources that accept a POST action. */
    private val SESSION_ACTIONS = setOf(
        "abort", "fork", "prompt_async", "revert", "summarize", "unrevert", "init", "command",
    )

    /**
     * A parsed relative request.
     *
     * [rawSegments] keeps the percent-encoded form because that is what has to be
     * validated: decoding first would turn `%2f` into a real separator and let a
     * crafted id span two route segments. [segments] is the decoded form used for
     * literal matching and for reading ids back out.
     */
    data class Request(
        val method: String,
        val pathname: String,
        val rawSegments: List<String>,
        val segments: List<String>,
        val query: Map<String, List<String>>,
    ) {
        val hasQuery: Boolean get() = query.isNotEmpty()
    }

    data class Route(val segments: List<String>, val allow: (Request, Map<String, String>) -> Boolean)

    fun isAllowed(method: String, path: String): Boolean {
        val request = parse(method, path) ?: return false
        if (!request.rawSegments.all(::isSafeSegment)) return false
        for (route in ROUTES) {
            val params = match(route.segments, request.segments) ?: continue
            return route.allow(request, params)
        }
        return false
    }

    /** Extracts the `:id` of a `/varro/session/:id/...` request, if it is one. */
    fun varroSessionId(path: String): String? {
        val request = parse("GET", path) ?: return null
        val prefix = Endpoints.SESSION.split('/').filter(String::isNotEmpty)
        if (request.segments.size != prefix.size + 2) return null
        prefix.forEachIndexed { index, segment -> if (request.segments[index] != segment) return null }
        return request.segments[prefix.size]
    }

    fun parse(method: String, path: String): Request? {
        if (!path.startsWith("/") || path.startsWith("//")) return null
        val pathname = path.substringBefore('?').substringBefore('#')
        val rawQuery = path.substringAfter('?', "").substringBefore('#')
        if (pathname.length > MAX_PATH_LENGTH || rawQuery.length > MAX_QUERY_LENGTH) return null

        val query = LinkedHashMap<String, MutableList<String>>()
        if (rawQuery.isNotEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val key = decode(pair.substringBefore('='))
                val value = if (pair.contains('=')) decode(pair.substringAfter('=')) else ""
                query.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

        val rawSegments = pathname.split('/').filter(String::isNotEmpty)
        return Request(
            method = method.uppercase(),
            pathname = pathname,
            rawSegments = rawSegments,
            segments = rawSegments.map(::decode),
            query = query,
        )
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

    /**
     * A segment must be a real name. Called with the still-encoded segment, so
     * `%2f` and `%2e%2e` are caught here rather than after decoding has already
     * turned them into a separator or a traversal.
     */
    private fun isSafeSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment.length > MAX_SEGMENT_LENGTH) return false
        if (ENCODED_SEPARATOR.containsMatchIn(segment)) return false
        val decoded = decode(segment)
        if (decoded == "." || decoded == "..") return false
        return !decoded.contains('/') && !decoded.contains('\\')
    }

    private val ENCODED_SEPARATOR = Regex("%2f|%5c", RegexOption.IGNORE_CASE)

    /** `:name` captures one segment; every other segment matches literally. */
    private fun match(pattern: List<String>, segments: List<String>): Map<String, String>? {
        if (pattern.size != segments.size) return null
        val params = HashMap<String, String>()
        pattern.forEachIndexed { index, patternSegment ->
            if (patternSegment.startsWith(":")) {
                params[patternSegment.substring(1)] = segments[index]
            } else if (patternSegment != segments[index]) {
                return null
            }
        }
        return params
    }

    // --- Query predicates -----------------------------------------------------

    private fun Request.onlyQuery(vararg keys: String): Boolean =
        query.keys.all { it in keys }

    private fun Request.required(key: String): Boolean =
        query[key]?.firstOrNull()?.isNotBlank() == true

    private fun Request.singleRequired(key: String): Boolean {
        val values = query[key] ?: return false
        return values.size == 1 && values[0].isNotBlank()
    }

    private fun Request.positiveInteger(key: String): Boolean {
        val values = query[key] ?: return false
        if (values.size != 1 || !values[0].all(Char::isDigit) || values[0].isEmpty()) return false
        val parsed = values[0].toLongOrNull() ?: return false
        return parsed > 0 && parsed <= MAX_SESSION_PAGE_LIMIT
    }

    /** `directory` may appear at most once, and must be non-empty when present. */
    private fun Request.optionalDirectory(): Boolean = withOptionalDirectory()

    private fun Request.withOptionalDirectory(vararg keys: String): Boolean {
        val directories = query["directory"] ?: emptyList()
        return onlyQuery(*keys, "directory") &&
            directories.size <= 1 &&
            (directories.isEmpty() || required("directory"))
    }

    private fun Request.sessionSearchQuery(): Boolean =
        withOptionalDirectory("limit", "search", "roots") &&
            positiveInteger("limit") &&
            singleRequired("search") &&
            query["roots"]?.size == 1 &&
            query["roots"]?.firstOrNull() == "true"

    private fun methodsNoQuery(vararg methods: String): (Request, Map<String, String>) -> Boolean =
        { request, _ -> request.method in methods && !request.hasQuery }

    private fun route(pattern: String, allow: (Request, Map<String, String>) -> Boolean): Route =
        Route(pattern.split('/').filter(String::isNotEmpty), allow)

    // --- The table ------------------------------------------------------------

    private val ROUTES: List<Route> = listOf(
        route("/global/health", methodsNoQuery("GET")),
        route("/global/config", methodsNoQuery("GET")),
        route("/model/default", methodsNoQuery("GET")),
        route("/config/providers", methodsNoQuery("GET")),
        route("/provider", methodsNoQuery("GET")),
        route("/provider/auth", methodsNoQuery("GET")),
        route("/auth/:id", methodsNoQuery("PUT", "DELETE")),
        route("/command", methodsNoQuery("GET")),
        route("/mcp", methodsNoQuery("GET")),
        route("/lsp", methodsNoQuery("GET")),
        route("/vcs/status", methodsNoQuery("GET")),
        route("/agent", methodsNoQuery("GET")),
        route("/question", methodsNoQuery("GET")),
        route("/permission", methodsNoQuery("GET")),
        route("/permission/:id/reply", methodsNoQuery("POST")),

        route("/session") { request, _ ->
            (
                request.method == "GET" && (
                    !request.hasQuery ||
                        (request.withOptionalDirectory("limit") && request.positiveInteger("limit")) ||
                        request.sessionSearchQuery()
                    )
                ) || (request.method == "POST" && request.optionalDirectory())
        },
        route("/session/status", methodsNoQuery("GET")),
        route("/experimental/workspace/status", methodsNoQuery("GET")),

        // --- Varro host namespace --------------------------------------------
        route(Endpoints.PROVIDER_LIMIT) { request, _ ->
            request.method == "GET" &&
                request.onlyQuery("providerID", "modelID") &&
                request.required("providerID")
        },
        route(Endpoints.WORKSPACE_FILE) { request, _ ->
            request.method == "GET" && request.onlyQuery("path") && request.required("path")
        },
        route(Endpoints.WORKSPACE_PATH_RESOLVE) { request, _ ->
            request.method == "GET" && request.onlyQuery("path") && request.required("path")
        },
        route(Endpoints.WORKSPACE_FILE_PICK, methodsNoQuery("GET")),
        route(Endpoints.SESSION_HISTORY_SCOPE) { request, _ ->
            (request.method == "GET" || request.method == "POST") &&
                request.onlyQuery("directory") &&
                request.required("directory")
        },
        route(Endpoints.OPENCODE_CONFIG, methodsNoQuery("GET")),
        route(Endpoints.OPENCODE_CONFIG_MODEL_ROUTING, methodsNoQuery("POST")),
        route(Endpoints.OPENCODE_CONFIG_PERMISSIONS, methodsNoQuery("GET", "POST")),
        route(Endpoints.PERMISSION_JUDGE, methodsNoQuery("POST")),
        route(Endpoints.PERMISSION_PROJECT_ALLOW) { request, _ ->
            request.method == "POST" && request.optionalDirectory()
        },
        route(Endpoints.PERMISSION_SESSION_ALLOW) { request, _ ->
            request.method == "POST" && request.optionalDirectory()
        },
        route(Endpoints.PERMISSION_SESSION_RULES) { request, _ ->
            when (request.method) {
                "GET" -> request.onlyQuery("sessionId", "directory") && request.required("sessionId")
                "POST" -> request.optionalDirectory()
                else -> false
            }
        },
        route(Endpoints.PERMISSION_SERVER_MEMORY) { request, _ ->
            when (request.method) {
                "GET" -> request.onlyQuery("sessionId", "directory")
                "DELETE" -> request.optionalDirectory()
                else -> false
            }
        },
        route(Endpoints.PERMISSION_JUDGE_MODEL) { request, _ ->
            request.method == "GET" && request.onlyQuery("providerID", "modelID", "variant")
        },
        route("${Endpoints.SESSION}/:id/diff-summary") { request, _ ->
            request.method == "GET" && request.withOptionalDirectory("revision")
        },
        route("${Endpoints.SESSION}/:id/activate", methodsNoQuery("POST")),
        route("${Endpoints.SESSION}/:id/pin") { request, _ ->
            request.method == "POST" && request.optionalDirectory()
        },
        route("${Endpoints.SESSION}/:id/reorder-pin") { request, _ ->
            request.method == "POST" && request.optionalDirectory()
        },
        route("${Endpoints.SESSION}/:id/permission-mode") { request, _ ->
            request.method == "POST" && request.optionalDirectory()
        },
        route("${Endpoints.SESSION}/:id/rename-if-untitled") { request, _ ->
            request.method == "POST" && request.optionalDirectory()
        },
        route("${Endpoints.SESSION}/:id/delete") { request, _ ->
            request.method == "DELETE" && request.optionalDirectory()
        },
        route(Endpoints.SESSION_TRASH, methodsNoQuery("GET", "DELETE")),
        route(Endpoints.PLAN_OPEN, methodsNoQuery("POST")),
        route("${Endpoints.SESSION_TRASH}/:id/:action") { request, params ->
            !request.hasQuery && (
                (request.method == "POST" && params["action"] == "restore") ||
                    (request.method == "DELETE" && params["action"] == "delete")
                )
        },

        // --- OpenCode, continued ---------------------------------------------
        route("/question/:id/:action") { request, params ->
            request.method == "POST" && !request.hasQuery &&
                (params["action"] == "reply" || params["action"] == "reject")
        },
        route("/mcp/:id/auth/authenticate", methodsNoQuery("POST")),
        route("/mcp/:id/auth", methodsNoQuery("POST", "DELETE")),
        route("/mcp/:id/auth/callback", methodsNoQuery("POST")),
        route("/mcp/:id/:action") { request, params ->
            request.method == "POST" && !request.hasQuery &&
                (params["action"] == "connect" || params["action"] == "disconnect")
        },
        route("/provider/:id/oauth/:action") { request, params ->
            request.method == "POST" && !request.hasQuery &&
                (params["action"] == "authorize" || params["action"] == "callback")
        },
        route("/experimental/workspace/warp", methodsNoQuery("POST")),
        route("/session/:id/diff") { request, _ ->
            request.method == "GET" && request.withOptionalDirectory("messageID")
        },
        route("/session/:id/message") { request, _ ->
            request.method == "GET" &&
                request.withOptionalDirectory("limit", "before") &&
                (!request.query.containsKey("before") || request.required("limit"))
        },
        route("/session/:id/message/:messageId") { request, _ ->
            request.method == "DELETE" && request.optionalDirectory()
        },
        route("/session/:id/todo") { request, _ ->
            request.method == "GET" && request.optionalDirectory()
        },
        route("/session/:id/share") { request, _ ->
            (request.method == "POST" || request.method == "DELETE") && request.optionalDirectory()
        },
        route("/session/:id/:action") { request, params ->
            request.method == "POST" &&
                params["action"] in SESSION_ACTIONS &&
                request.optionalDirectory()
        },
        route("/session/:id") { request, _ ->
            request.method in setOf("GET", "PATCH", "DELETE") && request.optionalDirectory()
        },
    )
}
