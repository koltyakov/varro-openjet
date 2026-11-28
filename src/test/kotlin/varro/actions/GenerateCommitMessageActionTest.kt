package varro.actions

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenerateCommitMessageActionTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `verification reselects the index even if the working patch did not change`() {
        val root = temporary.root
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
        }
        val action = GenerateCommitMessageAction()
        fun snapshot() = action.readCommitPatch(root.path) {}!!
        git("init")
        root.resolve("first.txt").writeText("first\n")
        git("add", "-N", "first.txt")
        val original = snapshot()
        assertFalse(original.staged)
        assertEquals(original, snapshot())
        root.resolve("other.txt").writeText("unrelated\n")
        git("add", "other.txt")
        val unrelated = snapshot()
        assertTrue(unrelated.staged)
        assertNotEquals(original.diff, unrelated.diff)
        git("rm", "--cached", "other.txt")
        git("add", "first.txt")
        val identicalDiff = snapshot()
        assertEquals(original.diff, identicalDiff.diff)
        assertNotEquals(original, identicalDiff)
    }

    @Test fun `a Git failure becomes a user notice instead of an IDE error`() {
        val root = temporary.newFolder("not-a-repository")
        val action = GenerateCommitMessageAction()
        // GIT_CEILING_DIRECTORIES cannot be set for the child process, so guard against a repository above the temp folder.
        val enclosing = ProcessBuilder("git", "rev-parse", "--git-dir").directory(root).redirectErrorStream(true).start()
        enclosing.inputStream.readAllBytes()
        org.junit.Assume.assumeTrue("Temporary folder is inside a Git repository", enclosing.waitFor() != 0)
        val failure = assertThrows(IllegalStateException::class.java) { action.readCommitPatch(root.path) {} }
        assertEquals("Git could not read the staged changes in ${root.path}.", failure.message)
        assertEquals("Could not generate the commit message: Git could not read the staged changes in ${root.path}.",
            action.failureNotice(failure))
        assertEquals("Could not generate the commit message: IOException", action.failureNotice(java.io.IOException()))
    }
}
