package varro.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequestDirectoriesTest {
    private fun directories(
        observed: Map<String, String> = emptyMap(),
        transfers: Set<Pair<String, String>> = emptySet(),
        sessions: Set<Pair<String, String>> = emptySet(),
        lookups: MutableList<Pair<String, String>> = mutableListOf(),
    ) = RequestDirectories(
        roots = { listOf("/work/app", "/work/lib") },
        observed = { observed },
        authorizedTransfer = { id, directory -> id to directory in transfers },
        authorizeSession = { id, directory -> lookups += id to directory; id to directory in sessions },
    )

    @Test fun `open roots and folders inside them are accepted`() {
        val directories = directories()
        assertTrue(directories.isAuthorized(null, null))
        assertTrue(directories.isAuthorized(null, "/work/app"))
        assertTrue(directories.isAuthorized(null, "/work/app/"))
        assertTrue(directories.isAuthorized(null, "/work/lib/packages/core"))
        assertTrue(directories.isAuthorized("ses_1", "/work/app/nested"))
    }

    @Test fun `unrelated folders traversal and relative paths are refused without a session`() {
        val directories = directories()
        assertFalse(directories.isAuthorized(null, "/work/other"))
        assertFalse(directories.isAuthorized(null, "/work/application"))
        assertFalse(directories.isAuthorized(null, "/work/app/../other"))
        assertFalse(directories.isAuthorized(null, "/work/app/./x"))
        assertFalse(directories.isAuthorized(null, "work/app"))
        try {
            directories.require(null, "/Users/someone/.ssh")
            fail("Expected an authorization failure")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message!!.startsWith("403 "))
        }
    }

    @Test fun `host observed session directories and transfers are accepted`() {
        val directories = directories(
            observed = mapOf("ses_obs" to "/elsewhere/tree"),
            transfers = setOf("ses_moved" to "/moved/here"),
        )
        assertTrue(directories.isAuthorized("ses_obs", "/elsewhere/tree"))
        assertTrue(directories.isAuthorized(null, "/elsewhere/tree"))
        assertTrue(directories.isAuthorized("ses_moved", "/moved/here"))
        assertFalse(directories.isAuthorized(null, "/moved/here"))
    }

    @Test fun `session routes fall back to a fresh server record once and cache approval`() {
        val lookups = mutableListOf<Pair<String, String>>()
        val directories = directories(sessions = setOf("ses_wt" to "/worktrees/feature"), lookups = lookups)
        assertTrue(directories.isAuthorized("ses_wt", "/worktrees/feature"))
        assertTrue(directories.isAuthorized("ses_wt", "/worktrees/feature"))
        assertEquals(1, lookups.size)
        assertFalse(directories.isAuthorized("ses_other", "/worktrees/feature"))
        assertFalse(directories.isAuthorized("ses_other", "/worktrees/feature"))
        assertEquals(3, lookups.size)
    }
}
