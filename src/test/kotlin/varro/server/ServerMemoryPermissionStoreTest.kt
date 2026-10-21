package varro.server

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.store.VarroStore

class ServerMemoryPermissionStoreTest {
    private fun rule() = Json.obj("id" to "saved", "projectID" to "project", "permission" to "bash", "pattern" to "git *")

    @Test fun `native observations survive XML reload without legacy rules or cached capabilities`() {
        val workspace = VarroStore()
        val store = ServerMemoryPermissionStore(workspace.serverMemoryPermissions) { workspace.serverMemoryPermissions = it }
        val native = rule().apply { addProperty("editable", true) }
        val legacy = rule().apply { addProperty("id", "legacy:request:0"); addProperty("retractable", false) }
        store.remember("2:http://localhost:1234", listOf(native, legacy))
        assertEquals(2, store.get("2:http://localhost:1234", "project").size)
        val restoredWorkspace = VarroStore().apply {
            loadState(XmlSerializer.deserialize(XmlSerializer.serialize(workspace.state), VarroStore.StoreState::class.java))
        }
        val restored = ServerMemoryPermissionStore(restoredWorkspace.serverMemoryPermissions) {}
        assertEquals(listOf(rule()), restored.get("2:http://localhost:1234", "project"))
        assertTrue(restored.get("2:http://localhost:4321", "project").isEmpty())
        assertTrue(restored.get("1:http://localhost:1234", "project").isEmpty())
        assertTrue(restored.get("2:http://localhost:1234", "other").isEmpty())
        store.clearLegacy()
        assertEquals(listOf(native), store.get("2:http://localhost:1234", "project"))
    }

    @Test fun `native ID replaces edited scope and retraction persists`() {
        var stored = Json.array(emptyList<Any>())
        val store = ServerMemoryPermissionStore(stored) { stored = it }
        store.remember("server", listOf(rule()))
        val edited = rule().apply { addProperty("permission", "read"); addProperty("pattern", "README.md") }
        store.remember("server", listOf(edited))
        val restored = ServerMemoryPermissionStore(stored) { stored = it }
        assertEquals(listOf(edited), restored.get("server", "project"))
        restored.forget("server", rule())
        assertTrue(ServerMemoryPermissionStore(stored) {}.get("server", "project").isEmpty())
    }

    @Test fun `failed persistence retains acknowledged management and retries next write`() {
        var stored = Json.array(emptyList<Any>())
        var fail = true
        val store = ServerMemoryPermissionStore(stored) { if (fail) error("disk unavailable"); stored = it }
        store.remember("server", listOf(rule()))
        assertEquals(listOf(rule()), store.get("server", "project"))
        assertEquals(0, stored.size())
        fail = false
        store.remember("server", listOf(rule()))
        assertEquals(listOf(rule()), ServerMemoryPermissionStore(stored) {}.get("server", "project"))
    }

    @Test fun `malformed storage and process only allowances are never restored`() {
        for (value in listOf(null, Json.obj(), Json.array(listOf(null,
            Json.obj("server" to "server", "rule" to Json.obj()),
            Json.obj("server" to "server", "rule" to rule().apply { addProperty("id", "legacy:request:0") }),
            Json.obj("server" to "server", "rule" to rule().apply { addProperty("pattern", 123) }))))) {
            assertTrue(ServerMemoryPermissionStore(value) {}.get("server", "project").isEmpty())
        }
    }

    @Test fun `snapshots do not share mutable rule objects`() {
        val store = ServerMemoryPermissionStore(null) {}
        val input = rule()
        store.remember("server", listOf(input))
        input.addProperty("pattern", "mutated")
        store.get("server", "project").single().addProperty("pattern", "also mutated")
        assertEquals(listOf(rule()), store.get("server", "project"))
    }

    @Test fun `different native IDs with the same scope retain separate management links`() {
        var stored = Json.array(emptyList<Any>())
        val store = ServerMemoryPermissionStore(null) { stored = it }
        val other = rule().apply { addProperty("id", "other") }
        store.remember("server", listOf(rule(), other))
        val restored = ServerMemoryPermissionStore(stored) {}
        assertEquals(listOf(rule(), other), restored.get("server", "project"))
        restored.forget("server", rule())
        assertEquals(listOf(other), restored.get("server", "project"))
    }
}
