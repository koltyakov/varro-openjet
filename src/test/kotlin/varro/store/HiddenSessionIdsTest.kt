package varro.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenSessionIdsTest {
    @Test fun `hidden helper ids are cached pruned and bounded`() {
        val store = VarroStore()
        store.hideSession("judge_1")
        store.hideSession("judge_1")
        store.hideSession("judge_2")
        assertEquals(listOf("judge_1", "judge_2"), store.hiddenSessionIds.toList())
        assertSame(store.hiddenSessionIds, store.hiddenSessionIds)

        assertTrue(store.forgetHiddenSession("judge_1"))
        assertFalse(store.forgetHiddenSession("judge_1"))
        assertEquals(setOf("judge_2"), store.hiddenSessionIds)

        store.hiddenSessionIds = (1..5_100).map { "s$it" }.toSet()
        assertEquals(5_000, store.hiddenSessionIds.size)
        assertTrue("s5100" in store.hiddenSessionIds)
        assertFalse("s1" in store.hiddenSessionIds)

        store.loadState(VarroStore.StoreState().apply { hiddenSessionIds = "[\"reloaded\"]" })
        assertEquals(setOf("reloaded"), store.hiddenSessionIds)
    }
}
