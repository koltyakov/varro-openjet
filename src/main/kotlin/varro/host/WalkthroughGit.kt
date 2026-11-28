package varro.host

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads the uncommitted changes a walkthrough explains, straight from `git`.
 *
 * Mirrors upstream's `repository.diff(true)` for staged changes and
 * `captureRepositoryChanges(repository, 'unstaged')` for the working tree, which
 * appends a synthetic new-file patch for each untracked file (bounded per file
 * and in total). Like `GenerateCommitMessageAction`, it shells out to Git rather
 * than depending on Git4Idea's repository API.
 */
internal object WalkthroughGit {
    const val MAX_UNTRACKED_FILE_BYTES = 20_000
    const val MAX_UNTRACKED_TOTAL_BYTES = 40_000
    const val MAX_DIFF_BYTES = 4 * 1024 * 1024
    const val MAX_UNTRACKED_PATHS = 4096
    /** Room for [MAX_UNTRACKED_PATHS] paths of up to 1 KiB each, so the path count is the binding limit. */
    private const val MAX_PATH_LIST_BYTES = MAX_UNTRACKED_PATHS * 1024
    private const val GIT_TIMEOUT_SECONDS = 30L

    private val DIFF_LIMIT = "${MAX_DIFF_BYTES / (1024 * 1024)} MB"
    val STAGED_TOO_LARGE = "The staged changes exceed the $DIFF_LIMIT walkthrough limit. " +
        "Unstage some files to walk through a smaller set."
    val UNSTAGED_TOO_LARGE = "The unstaged changes exceed the $DIFF_LIMIT walkthrough limit. " +
        "Stage the files you want explained and walk through the staged changes."
    val TOO_MANY_UNTRACKED = "There are more than ${String.format(java.util.Locale.US, "%,d", MAX_UNTRACKED_PATHS)} untracked files. " +
        "Add generated files to .gitignore, or stage the files you want explained and walk through the staged changes."

    /** A failed section exceeded a capture limit. The other scope is still selectable. */
    data class Changes(val staged: Result<String>, val unstaged: Result<String>)

    private class CaptureLimit(message: String) : IllegalArgumentException(message)

    /** Raised by [git] when its output passes the caller's byte limit; mapped to a scope-specific [CaptureLimit]. */
    private class OutputLimitExceeded : RuntimeException()

    private fun captureSection(read: () -> String): Result<String> = try {
        Result.success(read())
    } catch (limit: CaptureLimit) {
        Result.failure(limit)
    }

    private inline fun <T> limited(message: String, read: () -> T): T = try {
        read()
    } catch (_: OutputLimitExceeded) {
        throw CaptureLimit(message)
    }

    /** @param check throws to cancel; called between and during Git commands. */
    fun capture(root: String, check: () -> Unit): Changes {
        val staged = captureSection {
            limited(STAGED_TOO_LARGE) { git(root, check, MAX_DIFF_BYTES, "diff", "--cached", "--no-color", "--no-ext-diff") }
        }
        val unstaged = captureSection {
            val tracked = limited(UNSTAGED_TOO_LARGE) { git(root, check, MAX_DIFF_BYTES, "diff", "--no-color", "--no-ext-diff") }
            val names = limited(TOO_MANY_UNTRACKED) {
                git(root, check, MAX_PATH_LIST_BYTES, "ls-files", "--others", "--exclude-standard", "-z")
            }
            val paths = names.splitToSequence('\u0000').filter(String::isNotEmpty).take(MAX_UNTRACKED_PATHS + 1).toList()
            if (paths.size > MAX_UNTRACKED_PATHS) throw CaptureLimit(TOO_MANY_UNTRACKED)
            val patches = untrackedPatches(Path.of(root), paths, check)
            (listOf(tracked) + patches).filter(String::isNotEmpty).joinToString("\n")
        }
        return Changes(staged, unstaged)
    }

    /** Synthetic patches for untracked files, matching upstream's text exactly. */
    fun untrackedPatches(root: Path, paths: List<String>, check: () -> Unit = {}): List<String> {
        val realRoot = runCatching { root.toRealPath() }.getOrNull()
        var includedBytes = 0
        val patches = mutableListOf<String>()
        for (path in paths) {
            check()
            val displayPath = path.replace(Regex("[\r\n]"), " ")
            val header = "diff --git a/$displayPath b/$displayPath"
            try {
                val file = root.resolve(path).normalize()
                if (Files.isSymbolicLink(file)) {
                    patches += "$header\nnew file mode 120000\nsymbolic link; content omitted"
                    continue
                }
                if (realRoot == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(realRoot)) {
                    error("Untracked file is not a regular file inside the repository")
                }
                val byteLength = Files.size(file)
                if (byteLength > MAX_UNTRACKED_FILE_BYTES) {
                    patches += "$header\nnew untracked file ($byteLength bytes; content omitted)"
                    continue
                }
                val availableBytes = maxOf(0, MAX_UNTRACKED_TOTAL_BYTES - includedBytes)
                if (availableBytes == 0) {
                    patches += "$header\nnew untracked file ($byteLength bytes; content omitted)"
                    continue
                }
                val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_UNTRACKED_FILE_BYTES) }
                val included = bytes.copyOf(minOf(bytes.size, availableBytes))
                includedBytes += included.size
                val content = if (included.contains(0)) "Binary untracked file (${bytes.size} bytes)"
                    else prefixLines(String(included, Charsets.UTF_8))
                val truncation = if (included.size < bytes.size) "\n+... content truncated ..." else ""
                patches += "$header\nnew file mode 100644\n--- /dev/null\n+++ b/$displayPath\n@@ -0,0 +1 @@\n$content$truncation"
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                patches += "$header\nnew untracked file (content unavailable)"
            }
        }
        return patches
    }

    /**
     * Prefixes each line with `+`. Unlike upstream's `replace(/^/gm, '+')`, a
     * trailing newline does not add an empty `+` line, which would count as an
     * extra addition.
     */
    private fun prefixLines(text: String): String =
        text.removeSuffix("\n").split('\n').joinToString("\n") { "+$it" }

    private fun git(root: String, check: () -> Unit, maxBytes: Int, vararg parameters: String): String {
        check()
        val process = ProcessBuilder(listOf("git", "-c", "core.quotePath=false") + parameters)
            .directory(File(root))
            // Nothing reads stderr, and an undrained pipe blocks git until the timeout.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val reader = Executors.newSingleThreadExecutor()
        val output = reader.submit<ByteArray> {
            process.inputStream.use { stream ->
                stream.readNBytes(maxBytes + 1).also { if (it.size > maxBytes) process.destroyForcibly() }
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIT_TIMEOUT_SECONDS)
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check()
                if (System.nanoTime() >= deadline) error("Git timed out while reading the changes.")
            }
            check()
            val bytes = output.get(5, TimeUnit.SECONDS)
            if (bytes.size > maxBytes) throw OutputLimitExceeded()
            check(process.exitValue() == 0) { "Git ${parameters.first()} failed while reading changes in $root." }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            process.destroyForcibly()
            output.cancel(true)
            reader.shutdownNow()
        }
    }
}
