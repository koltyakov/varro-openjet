package varro.actions

import org.junit.Assert.assertEquals
import org.junit.Test
import varro.actions.WalkThroughChangesAction.Scope
import varro.actions.WalkThroughChangesAction.ScopeChoice
import varro.host.WalkthroughGit

class WalkThroughChangesActionTest {
    private val patch = "diff --git a/a.txt b/a.txt\n@@ -0,0 +1 @@\n+a"
    private val tooLarge = Result.failure<String>(IllegalArgumentException("Over the limit."))
    private fun choose(staged: Result<String>, unstaged: Result<String>) =
        WalkThroughChangesAction.chooseScope(WalkthroughGit.Changes(staged, unstaged), "/repo")

    @Test fun `both readable scopes offer every option`() {
        assertEquals(ScopeChoice.Pick, choose(Result.success(patch), Result.success(patch)))
    }

    @Test fun `a single readable scope starts without asking`() {
        assertEquals(ScopeChoice.Start(Scope.STAGED), choose(Result.success(patch), Result.success("")))
        assertEquals(ScopeChoice.Start(Scope.UNSTAGED), choose(Result.success(" \n"), Result.success(patch)))
    }

    @Test fun `a scope over a capture limit is never offered and narrowing needs confirmation`() {
        assertEquals(ScopeChoice.Confirm(Scope.STAGED, "Over the limit."), choose(Result.success(patch), tooLarge))
        assertEquals(ScopeChoice.Confirm(Scope.UNSTAGED, "Over the limit."), choose(tooLarge, Result.success(patch)))
    }

    @Test fun `a failed scope alone reports its limit instead of offering it`() {
        assertEquals(ScopeChoice.Stop("Over the limit.", error = true), choose(tooLarge, Result.success("")))
        assertEquals(ScopeChoice.Stop("Over the limit.", error = true), choose(Result.success(""), tooLarge))
        assertEquals(ScopeChoice.Stop("Over the limit. Over the limit.", error = true), choose(tooLarge, tooLarge))
    }

    @Test fun `no changes is informational`() {
        assertEquals(ScopeChoice.Stop("There are no uncommitted changes in /repo.", error = false),
            choose(Result.success(""), Result.success("")))
    }
}
