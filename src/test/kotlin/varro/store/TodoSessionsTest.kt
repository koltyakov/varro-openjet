package varro.store

import org.junit.Assert.assertEquals
import org.junit.Test
import varro.protocol.Json

class TodoSessionsTest {
    @Test fun `todo marks persist until a later prompt or deletion and notify every change`() {
        val store = VarroStore()
        val published = mutableListOf<List<String>>()
        val remove = store.addTodoListener { published.add(it) }

        assertEquals(listOf("ses_a"), store.setTodoSession("ses_a", true, now = 1_000))
        assertEquals(listOf("ses_a", "ses_b"), store.setTodoSession("ses_b", true, now = 2_000))
        // A prompt created before the mark was set leaves it in place.
        assertEquals(listOf("ses_a", "ses_b"), store.clearTodoSessionForPrompt("ses_a", 999))
        assertEquals(listOf("ses_b"), store.clearTodoSessionForPrompt("ses_a", 1_000))
        assertEquals(listOf("ses_b"), store.clearTodoSessionForPrompt("ses_unknown", 5_000))
        assertEquals(emptyList<String>(), store.removeTodoSession("ses_b"))
        assertEquals(emptyList<String>(), store.setTodoSession("ses_missing", false))
        assertEquals(listOf(listOf("ses_a"), listOf("ses_a", "ses_b"), listOf("ses_b"), emptyList()), published)

        // Re-marking a session restarts its marker, so an older prompt no longer clears it.
        store.setTodoSession("ses_c", true, now = 10)
        store.setTodoSession("ses_c", true, now = 20)
        assertEquals(listOf("ses_c"), store.clearTodoSessionForPrompt("ses_c", 15))
        remove()
    }

    @Test fun `malformed persisted marks are ignored`() {
        val store = VarroStore()
        store.loadState(VarroStore.StoreState().apply {
            todoSessions = Json.stringify(Json.obj("ses_ok" to 5, "" to 1, "ses_text" to "later", "ses_obj" to Json.obj()))
        })
        assertEquals(listOf("ses_ok"), store.todoSessionIds)
    }
}
