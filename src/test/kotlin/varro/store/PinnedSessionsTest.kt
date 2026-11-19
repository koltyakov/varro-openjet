package varro.store

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PinnedSessionsTest {
    @Test fun `committed pins notify every listener independently of an RPC response and serialize concurrent mutations`() {
        val store = VarroStore()
        val snapshots = mutableListOf<List<String>>()
        val otherView = mutableListOf<List<String>>()
        val remove = store.addPinListener { snapshots += it; assertEquals(it, store.pinnedSessionIds) }
        store.addPinListener { otherView += it }
        Executors.newFixedThreadPool(4).use { executor ->
            val writes = (1..100).map { id -> executor.submit { store.updatePins { it + "ses_$id" } } }
            writes.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        assertEquals(100, store.pinnedSessionIds.size)
        assertEquals((1..100).toList(), snapshots.map { it.size })
        assertEquals(snapshots, otherView)
        store.updatePins { it.reversed() }
        assertEquals(store.pinnedSessionIds, snapshots.last())
        remove()
        store.pinnedSessionIds = emptyList()
        assertEquals(101, snapshots.size)
        assertTrue(otherView.last().isEmpty())
    }
}
