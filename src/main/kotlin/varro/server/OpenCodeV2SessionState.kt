package varro.server

import com.google.gson.JsonObject
import varro.protocol.*
import varro.store.JsonJournal
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Same per-session annotation files as Varro's v2 adapter. */
internal class OpenCodeV2SessionState(val directory: Path = Path.of(
    System.getenv("XDG_STATE_HOME") ?: Path.of(System.getProperty("user.home"), ".local", "state").toString(), "varro", "opencode-v2",
)) {
    private fun path(id: String): Path {
        require(Regex("[A-Za-z0-9_-]{1,256}").matches(id)) { "Invalid session ID" }
        return directory.resolve("$id.json")
    }
    private fun lock(id: String) = locks.computeIfAbsent(directory.resolve(id).toString()) { Any() }
    fun read(id: String): JsonObject = synchronized(lock(id)) { JsonJournal(path(id)).read(Json.obj()) }
    fun update(id: String, patch: JsonObject, onlyIfMissing: String? = null, checkCancelled: () -> Unit = {}) = synchronized(lock(id)) {
        checkCancelled()
        val release = acquireLock(id, checkCancelled)
        try {
            val value = read(id)
            if (onlyIfMissing == null || !value.has(onlyIfMissing)) {
                val time = (value.obj("time") ?: Json.obj()).deepCopy()
                patch.obj("time")?.entrySet()?.forEach { time.add(it.key, it.value) }
                patch.entrySet().forEach { value.add(it.key, it.value) }
                value.add("time", time)
                checkCancelled()
                JsonJournal(path(id)).write(value)
            }
        } finally { release() }
    }
    fun remove(id: String) = synchronized(lock(id)) {
        val release = acquireLock(id) {}
        try { Files.deleteIfExists(path(id)) } finally { release() }
    }

    /** Same owner-directory lock as Varro, so manual choices win across editors. */
    private fun acquireLock(id: String, checkCancelled: () -> Unit): () -> Unit {
        val lock = path(id).resolveSibling("$id.json.lock")
        Files.createDirectories(directory)
        val owner = "${ProcessHandle.current().pid()}-${UUID.randomUUID()}"
        val candidate = lock.resolveSibling("${lock.fileName}.$owner")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            checkCancelled()
            try {
                Files.createDirectory(candidate)
                Files.createFile(candidate.resolve(owner))
                Files.move(candidate, lock, StandardCopyOption.ATOMIC_MOVE)
                return {
                    Files.deleteIfExists(lock.resolve(owner))
                    removeEmptyLock(lock)
                }
            } catch (_: FileAlreadyExistsException) {
                // Another editor owns the lock.
            } catch (_: DirectoryNotEmptyException) {
                // Atomic rename reports a nonempty destination on Unix.
            } catch (failure: java.nio.file.FileSystemException) {
                if (!Files.isDirectory(lock)) throw failure
            } finally {
                Files.deleteIfExists(candidate.resolve(owner))
                Files.deleteIfExists(candidate)
            }
            try {
                val owners = Files.list(lock).use { it.toList() }
                if (owners.isEmpty()) removeEmptyLock(lock)
                else if (owners.size == 1 && Regex("[0-9]+-[a-f0-9-]{36}").matches(owners[0].fileName.toString())) {
                    val pid = owners[0].fileName.toString().substringBefore('-').toLongOrNull()
                    if (pid != null && pid > 0 && ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) {
                        Files.deleteIfExists(owners[0])
                        removeEmptyLock(lock)
                    }
                }
            } catch (_: NoSuchFileException) { /* The owner released the lock. */ }
            check(System.nanoTime() < deadline) { "Timed out waiting to update Varro session annotations" }
            Thread.sleep(25)
        }
    }

    private fun removeEmptyLock(path: Path) {
        try { Files.deleteIfExists(path) } catch (_: DirectoryNotEmptyException) { /* A new owner published its lock. */ }
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
