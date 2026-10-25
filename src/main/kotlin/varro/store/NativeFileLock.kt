package varro.store

import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Varro's owner-directory format also coordinates with the TypeScript host. */
internal object NativeFileLock {
    fun acquire(lock: Path, checkCancelled: () -> Unit = {}, message: String = "Timed out waiting for Varro state lock"): () -> Unit {
        val privateDirectory = if (lock.fileSystem.supportedFileAttributeViews().contains("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else emptyArray()
        Files.createDirectories(lock.parent, *privateDirectory)
        val owner = "${ProcessHandle.current().pid()}-${UUID.randomUUID()}"
        val candidate = lock.resolveSibling("${lock.fileName}.$owner")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            checkCancelled()
            var publishing = false
            try {
                Files.createDirectory(candidate, *privateDirectory)
                Files.createFile(candidate.resolve(owner))
                publishing = true
                Files.move(candidate, lock, StandardCopyOption.ATOMIC_MOVE)
                return {
                    Files.deleteIfExists(lock.resolve(owner))
                    removeEmpty(lock)
                }
            } catch (_: FileAlreadyExistsException) {
                // Another editor owns the lock.
            } catch (_: DirectoryNotEmptyException) {
                // Atomic rename reports a nonempty destination on Unix.
            } catch (failure: java.nio.file.FileSystemException) {
                // macOS can use the generic exception for ENOTEMPTY. The owner may
                // release the lock before isDirectory runs, so retain errno evidence.
                val contention = failure.reason in setOf("Directory not empty", "File exists") ||
                    (failure is java.nio.file.AccessDeniedException && System.getProperty("os.name").lowercase().contains("windows"))
                if (!publishing || (!contention && !Files.isDirectory(lock))) throw failure
            } finally {
                Files.deleteIfExists(candidate.resolve(owner))
                Files.deleteIfExists(candidate)
            }
            try {
                val owners = Files.list(lock).use { it.toList() }
                if (owners.isEmpty()) removeEmpty(lock)
                else if (owners.size == 1 && Regex("[0-9]+-[a-f0-9-]{36}").matches(owners[0].fileName.toString())) {
                    val pid = owners[0].fileName.toString().substringBefore('-').toLongOrNull()
                    if (pid != null && pid > 0 && ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) {
                        Files.deleteIfExists(owners[0])
                        removeEmpty(lock)
                    }
                }
            } catch (_: NoSuchFileException) { /* The owner released the lock. */ }
            check(System.nanoTime() < deadline) { message }
            Thread.sleep(25)
        }
    }

    private fun removeEmpty(path: Path) {
        try { Files.deleteIfExists(path) }
        catch (_: DirectoryNotEmptyException) { /* Another owner published its lock. */ }
        catch (failure: java.nio.file.AccessDeniedException) {
            // Windows may report a replacement nonempty directory as access denied.
            try { if (Files.list(path).use { it.findAny().isPresent }) return }
            catch (_: NoSuchFileException) { return }
            throw failure
        }
    }
}
