package varro.host

import varro.server.WorkspacePaths
import java.util.concurrent.ConcurrentHashMap

/**
 * Authorizes a `directory` the webview names on a forwarded request.
 *
 * OpenCode scopes every request to the directory it is given, so a page-supplied
 * value would otherwise create sessions under, or read history from, any folder
 * on disk. Upstream requires an open workspace root or an authorized session
 * directory (`requireOpenWorkspaceRoot` / `requireAuthorizedSessionDirectory`).
 * The JetBrains host accepts:
 *  - an open root or a folder inside one (nested-folder sessions and the
 *    project's own subdirectories);
 *  - a directory the host itself observed a session in, or a retained transfer;
 *  - for a session route, a directory whose fresh server record matches the
 *    current history scope ([FileReferences.authorizeDirectory]), which covers
 *    project worktrees outside the root.
 */
class RequestDirectories(
    private val roots: () -> List<String>,
    private val observed: () -> Map<String, String>,
    private val authorizedTransfer: (String, String) -> Boolean,
    private val authorizeSession: (String, String) -> Boolean,
    private val cacheKey: () -> String = { "" },
) {
    private val authorized = ConcurrentHashMap<String, Boolean>()

    fun require(sessionId: String?, directory: String?) {
        if (!isAuthorized(sessionId, directory)) {
            throw IllegalArgumentException("403 Workspace directory is not an open workspace folder")
        }
    }

    fun isAuthorized(sessionId: String?, directory: String?): Boolean {
        val requested = directory?.takeIf { it.isNotBlank() } ?: return true
        if (!WorkspacePaths.isAbsolute(requested)) return false
        // Containment below is lexical, so dot segments must not reach it.
        if (requested.replace('\\', '/').split('/').any { it == ".." || it == "." }) return false
        if (roots().any { WorkspacePaths.relativeWithin(requested, it) != null }) return true
        val seen = observed()
        if (sessionId != null && WorkspacePaths.isSame(seen[sessionId], requested)) return true
        if (sessionId == null && seen.values.any { WorkspacePaths.isSame(it, requested) }) return true
        if (sessionId == null) return false
        if (authorizedTransfer(sessionId, requested)) return true
        val key = "${cacheKey()}\u0000$sessionId\u0000${WorkspacePaths.normalizeIdentity(requested)}"
        if (authorized[key] == true) return true
        val allowed = authorizeSession(sessionId, requested)
        if (allowed) {
            if (authorized.size >= MAX_CACHED) authorized.clear()
            authorized[key] = true
        }
        return allowed
    }

    companion object {
        private const val MAX_CACHED = 2_000
    }
}
