package varro.server

/**
 * Workspace path identity.
 *
 * Port of `src/shared/workspace-path.ts`. Two spellings of the same directory
 * have to compare equal - OpenCode, the IDE and the user all produce slightly
 * different forms (drive casing, separators, trailing slashes, Windows extended
 * `\\?\` prefixes) - while the *display* form keeps its original casing so paths
 * shown in the UI still look like what the user typed.
 */
object WorkspacePaths {

    private data class Normalized(
        val absolute: Boolean,
        val displaySegments: List<String>,
        val identity: String,
        val identitySegments: List<String>,
        val root: String,
    )

    fun normalizeIdentity(path: String?): String? = normalize(path)?.identity

    fun isSame(left: String?, right: String?): Boolean {
        val a = normalizeIdentity(left) ?: return false
        return a == normalizeIdentity(right)
    }

    fun isAbsolute(path: String?): Boolean = normalize(path)?.absolute ?: false

    /**
     * Relative path of [path] inside [workspacePath], or `null` when it lies
     * outside. Returns `"."` for the workspace root itself.
     */
    fun relativeWithin(path: String?, workspacePath: String?): String? {
        val target = normalize(path) ?: return null
        val workspace = normalize(workspacePath) ?: return null
        if (target.root != workspace.root) return null
        if (target.identitySegments.size < workspace.identitySegments.size) return null
        workspace.identitySegments.forEachIndexed { index, segment ->
            if (segment != target.identitySegments[index]) return null
        }
        if (target.identitySegments.size == workspace.identitySegments.size) return "."
        return target.displaySegments.drop(workspace.displaySegments.size).joinToString("/")
    }

    private fun normalize(path: String?): Normalized? {
        if (path.isNullOrEmpty()) return null

        // Windows extended-length prefix: `\\?\C:\…`, `\\?\Volume{…}\…`, `\\?\UNC\server\share\…`.
        EXTENDED_PREFIX.matchEntire(path)?.let { match ->
            val remainder = match.groupValues[1]
            windowsDrive(remainder)?.let { return it }
            windowsVolume(remainder)?.let { return it }
            val unc = UNC_NAMESPACE.matchEntire(remainder) ?: return null
            return windowsUnc(unc.groupValues[1], withoutNamespacePrefix = true)
        }

        // Device paths (`\\.\PIPE\…`) and NT object paths (`\??\…`) are not workspaces.
        if (DEVICE_PREFIX.containsMatchIn(path) || NT_OBJECT_PREFIX.containsMatchIn(path)) return null

        return windowsDrive(path) ?: windowsUnc(path) ?: posix(path)
    }

    private fun windowsVolume(path: String): Normalized? {
        val match = VOLUME_PATH.matchEntire(path) ?: return null
        val volume = match.groupValues[1].lowercase()
        val display = windowsSegments(match.groupValues[2])
        val identity = display.map(String::lowercase)
        return Normalized(
            absolute = true,
            displaySegments = display,
            identity = "//?/$volume" + if (identity.isNotEmpty()) "/${identity.joinToString("/")}" else "/",
            identitySegments = identity,
            root = "volume:$volume",
        )
    }

    private fun windowsDrive(path: String): Normalized? {
        val match = DRIVE_PATH.matchEntire(path) ?: return null
        val drive = match.groupValues[1].lowercase()
        val display = windowsSegments(match.groupValues[2])
        val identity = display.map(String::lowercase)
        return Normalized(
            absolute = true,
            displaySegments = display,
            identity = "$drive:/${identity.joinToString("/")}",
            identitySegments = identity,
            root = "drive:$drive",
        )
    }

    private fun windowsUnc(path: String, withoutNamespacePrefix: Boolean = false): Normalized? {
        val pattern = if (withoutNamespacePrefix) UNC_BODY else UNC_PATH
        val match = pattern.matchEntire(path) ?: return null
        val server = match.groupValues[1].lowercase()
        val share = match.groupValues[2].lowercase()
        val display = windowsSegments(match.groupValues.getOrElse(3) { "" })
        val identity = display.map(String::lowercase)
        return Normalized(
            absolute = true,
            displaySegments = display,
            identity = "//$server/$share" + if (identity.isNotEmpty()) "/${identity.joinToString("/")}" else "",
            identitySegments = identity,
            root = "unc:$server/$share",
        )
    }

    private fun posix(path: String): Normalized? {
        // A leading `//` is meaningful on POSIX, so only the rest is collapsed.
        var display = if (path.startsWith("//")) {
            "//" + path.substring(2).replace(MULTI_SLASH, "/")
        } else {
            path.replace(MULTI_SLASH, "/")
        }
        if (display != "/") display = display.trimEnd('/')
        if (display.isEmpty()) return null

        val segments = display.split('/').filter(String::isNotEmpty)
        return Normalized(
            absolute = display.startsWith("/"),
            displaySegments = segments,
            identity = display,
            identitySegments = segments,
            root = when {
                display.startsWith("//") -> "posix://"
                display.startsWith("/") -> "posix:/"
                else -> "relative"
            },
        )
    }

    private fun windowsSegments(path: String): List<String> =
        path.split(WINDOWS_SEPARATOR).filter(String::isNotEmpty)

    private val EXTENDED_PREFIX = Regex("""^(?:\\\\|//)\?[\\/](.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val DEVICE_PREFIX = Regex("""^(?:\\\\|//)\.[\\/]""")
    private val NT_OBJECT_PREFIX = Regex("""^[\\/]+\?\?[\\/]""")
    private val VOLUME_PATH = Regex(
        """^(Volume\{[^\\/]+})[\\/](.*)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val DRIVE_PATH = Regex("""^([A-Za-z]):[\\/](.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val UNC_PATH = Regex(
        """^(?:\\\\|//)([^\\/]+)[\\/]([^\\/]+)(?:[\\/](.*))?$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val UNC_BODY = Regex(
        """^([^\\/]+)[\\/]([^\\/]+)(?:[\\/](.*))?$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val UNC_NAMESPACE = Regex("""^UNC[\\/](.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val MULTI_SLASH = Regex("""/+""")
    private val WINDOWS_SEPARATOR = Regex("""[\\/]+""")
}
