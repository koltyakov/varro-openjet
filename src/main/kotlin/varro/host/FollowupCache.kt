package varro.host

import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.NativeFileLock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Admission never expires. Evicting a presentation must not bill the same turn again. */
internal class FollowupCache(
    internal val path: Path = defaultPath(),
    private val windows: Boolean = System.getProperty("os.name").lowercase().contains("windows"),
    private val replace: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    fun get(key: String, digest: String): JsonObject? {
        val entry = read().obj(key) ?: return null
        return if (entry.str("digest") == digest && entry.bool("dismissed") != true) entry.obj("suggestions")!!.deepCopy() else Followups.empty()
    }

    fun claim(key: String, digest: String, sessionKey: String): Boolean {
        var claimed = false
        change { entries ->
            if (entries.has(key)) false else {
                entries.add(key, Json.obj("sessionKey" to sessionKey, "digest" to digest, "dismissed" to false, "suggestions" to Followups.empty()))
                claimed = true
                true
            }
        }
        return claimed
    }

    fun complete(key: String, digest: String, suggestions: JsonObject) = change { entries ->
        val entry = entries.obj(key)
        if (entry == null || entry.str("digest") != digest || entry.bool("dismissed") == true) false
        else { entry.add("suggestions", suggestions.deepCopy()); true }
    }

    fun dismiss(key: String, digest: String) = change { entries ->
        val entry = entries.obj(key)
        if (entry == null || entry.str("digest") != digest || entry.bool("dismissed") == true) false
        else { retire(entry); true }
    }

    fun clearSession(sessionKey: String, exceptKey: String?) = change { entries ->
        var changed = false
        entries.entrySet().forEach { (key, value) ->
            val entry = value.asJsonObject
            if (entry.str("sessionKey") == sessionKey && key != exceptKey && entry.bool("dismissed") != true) {
                retire(entry); changed = true
            }
        }
        changed
    }

    private fun change(update: (JsonObject) -> Boolean) {
        val release = NativeFileLock.acquire(path.resolveSibling("${path.fileName}.lock"))
        try {
            val entries = read()
            if (update(entries)) write(entries)
        } finally { release() }
    }

    private fun read(): JsonObject {
        val text = try { Files.readString(path) } catch (_: NoSuchFileException) { return Json.obj() }
        val stored = Json.parse(text).asObjectOrNull()
        require(stored.int("version") == 1 && stored.obj("entries") != null) { "Invalid follow-up cache format" }
        return stored.obj("entries")!!.apply {
            entrySet().forEach { (key, value) ->
                val entry = value.asObjectOrNull()
                require(hash.matches(key) && hash.matches(entry.str("digest").orEmpty()) &&
                    hash.matches(entry.str("sessionKey").orEmpty())) { "Invalid follow-up admission record" }
                try {
                    val suggestions = Followups.suggestions(entry!!.get("suggestions").toString())
                    entry.add("suggestions", if (entry.bool("dismissed") == true) Followups.empty() else suggestions)
                } catch (_: Exception) {
                    retire(entry!!)
                }
            }
        }
    }

    private fun write(entries: JsonObject) {
        entries.entrySet().toList().dropLast(512).forEach { retire(it.value.asJsonObject) }
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        val permissions = if (path.fileSystem.supportedFileAttributeViews().contains("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        try {
            Files.newByteChannel(temporary, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *permissions).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(Json.obj("version" to 1, "entries" to entries).toString().toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                (channel as? java.nio.channels.FileChannel)?.force(true)
            }
            var attempt = 0
            while (true) {
                try { replace(temporary, path); break }
                catch (failure: java.nio.file.FileSystemException) {
                    // Keep the committed file and lock intact during Windows sharing violations.
                    val retryable = failure is java.nio.file.AccessDeniedException ||
                        failure.reason.orEmpty().lowercase().let { "used by another process" in it || "sharing violation" in it || "busy" in it || "access is denied" in it }
                    if (!windows || !retryable || attempt >= 7) throw failure
                    Thread.sleep(minOf(10L shl attempt++, 200L))
                }
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun retire(entry: JsonObject) {
        entry.addProperty("dismissed", true)
        entry.add("suggestions", Followups.empty())
    }

    companion object {
        private val hash = Regex("[a-f0-9]{64}")

        internal fun defaultPath(environment: Map<String, String> = System.getenv(), home: Path = Path.of(System.getProperty("user.home")),
                                 os: String = System.getProperty("os.name")): Path {
            environment["VARRO_TEST_STATE_ROOT"]?.takeIf { it.isNotBlank() }?.let {
                val root = Path.of(it)
                require(root.isAbsolute) { "VARRO_TEST_STATE_ROOT must be absolute" }
                return root.resolve("followups/cache.json")
            }
            fun absolute(name: String) = environment[name]?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute }
            val root = when {
                os.lowercase().contains("mac") -> home.resolve("Library/Application Support/Varro")
                os.lowercase().contains("windows") -> (absolute("LOCALAPPDATA") ?: home.resolve("AppData/Local")).resolve("Varro")
                else -> (absolute("XDG_STATE_HOME") ?: home.resolve(".local/state")).resolve("varro")
            }
            return root.resolve("followups/cache.json")
        }
    }
}
