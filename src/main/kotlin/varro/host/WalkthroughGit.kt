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
    private const val GIT_TIMEOUT_SECONDS = 30L

    data class Changes(val staged: String, val unstaged: String)

    /** @param check throws to cancel; called between and during Git commands. */
    fun capture(root: String, check: () -> Unit): Changes {
        val staged = git(root, check, "diff", "--cached", "--no-color", "--no-ext-diff")
            ?: error("Git could not read the staged changes in $root.")
        val tracked = git(root, check, "diff", "--no-color", "--no-ext-diff")
            ?: error("Git could not read the unstaged changes in $root.")
        val untracked = git(root, check, "ls-files", "--others", "--exclude-standard", "-z")
            ?.split('\u0000')?.filter { it.isNotEmpty() }
            .orEmpty()
        val patches = untrackedPatches(Path.of(root), untracked, check)
        return Changes(staged, (listOf(tracked) + patches).filter { it.isNotBlank() }.joinToString("\n"))
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
                val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_UNTRACKED_FILE_BYTES) }
                val availableBytes = maxOf(0, MAX_UNTRACKED_TOTAL_BYTES - includedBytes)
                if (availableBytes == 0) {
                    patches += "$header\nnew untracked file (${bytes.size} bytes; content omitted)"
                    continue
                }
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

    /** Runs Git in [root]; null when it exits non-zero. */
    fun git(root: String, check: () -> Unit, vararg parameters: String): String? {
        check()
        val process = ProcessBuilder(listOf("git", "-c", "core.quotePath=false") + parameters)
            .directory(File(root))
            // Nothing reads stderr, and an undrained pipe blocks git until the timeout.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val reader = Executors.newSingleThreadExecutor()
        val output = reader.submit<String> { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIT_TIMEOUT_SECONDS)
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check()
                if (System.nanoTime() >= deadline) error("Git timed out while reading the changes.")
            }
            check()
            return if (process.exitValue() == 0) output.get(5, TimeUnit.SECONDS) else null
        } finally {
            process.destroyForcibly()
            output.cancel(true)
            reader.shutdownNow()
        }
    }
}
