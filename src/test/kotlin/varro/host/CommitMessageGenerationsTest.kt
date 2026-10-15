package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.settings.CommitMessageRules
import java.util.concurrent.CancellationException

class CommitMessageGenerationsTest {
    @Test fun `cancellation is repository scoped and stale completion cannot release a retry`() {
        val active = CommitMessageGenerations()
        val first = active.begin("/one")!!
        val other = active.begin("/two")!!
        assertNull(active.begin("/one"))
        active.cancel("/one")
        assertThrows(CancellationException::class.java, first::check)
        other.check()
        val retry = active.begin("/one")!!
        active.finish("/one", first)
        assertTrue(active.isActive("/one"))
        retry.check()
        active.dispose()
        assertThrows(CancellationException::class.java, retry::check)
        assertThrows(CancellationException::class.java, other::check)
    }

    @Test fun `rules follow the selected repository and survive workspace state restoration`() {
        val rules = CommitMessageRules()
        rules.set("/one", "  Use Conventional Commits.  ")
        assertEquals("Use Conventional Commits.", rules.resolve("/one", "default"))
        assertEquals("default", rules.resolve("/two", "default"))
        val restored = CommitMessageRules().apply { loadState(rules.state) }
        assertEquals("Use Conventional Commits.", restored.resolve("/one", "default"))
        restored.set("/one", "")
        assertEquals("new default", restored.resolve("/one", "new default"))
    }
}
