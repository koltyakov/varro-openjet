package varro.store

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionSelectionPersistenceTest {
    @Test
    fun `partial migrations preserve existing selections and never overwrite newer choices`() {
        val store = VarroStore()
        store.updateSessionModel("existing", model("chosen"))
        store.updateSessionPermissionMode("existing", JsonPrimitive("auto"))
        store.migrateSessionModels(Json.obj("existing" to model("stale"), "imported" to model("legacy")))
        store.migrateSessionPermissionModes(Json.obj("existing" to "full", "imported" to "default"))
        assertEquals(Json.obj("existing" to model("chosen"), "imported" to model("legacy")), store.sessionSelectedModels)
        assertEquals(Json.obj("existing" to "auto", "imported" to "default"), store.sessionPermissionModes)
    }

    @Test
    fun `browser-only selections migrate before boot and do not return after removal and restart`() {
        val store = VarroStore()
        store.updateSessionModel("current", model("chosen"))
        store.updateBrowserStorage("varro.sessionSelectedModels", Json.stringify(Json.obj(
            "current" to model("stale"), "legacy" to model("old"),
        )))
        store.updateBrowserStorage("varro.sessionPermissionModes", """{"legacy":"auto"}""")
        store.migrateBrowserSessionSelections()
        assertEquals(model("chosen"), store.sessionSelectedModels.get("current"))
        assertEquals(model("old"), store.sessionSelectedModels.get("legacy"))
        assertEquals(Json.obj("legacy" to "auto"), store.sessionPermissionModes)
        store.updateSessionModel("legacy", null)
        store.updateSessionPermissionMode("legacy", JsonNull.INSTANCE)

        val restored = restore(store)
        restored.migrateBrowserSessionSelections()
        assertFalse(restored.sessionSelectedModels.has("legacy"))
        assertEquals(JsonObject(), restored.sessionPermissionModes)
        assertEquals(model("chosen"), restored.sessionSelectedModels.get("current"))
    }

    @Test
    fun `every open view receives saved selections and disposed views stop receiving them`() {
        val store = VarroStore()
        var sidebarModels = JsonObject()
        var editorModes = JsonObject()
        val removeSidebar = store.addSelectionListener { sidebarModels = store.sessionSelectedModels }
        val removeEditor = store.addSelectionListener { editorModes = store.sessionPermissionModes }
        store.updateSessionModel("session", model("chosen"))
        store.updateSessionPermissionMode("session", JsonPrimitive("auto"))
        assertEquals(store.sessionSelectedModels, sidebarModels)
        assertEquals(store.sessionPermissionModes, editorModes)
        removeSidebar()
        removeEditor()
        store.updateSessionModel("session", model("changed"))
        store.updateSessionPermissionMode("session", JsonPrimitive("default"))
        assertEquals(model("chosen"), sidebarModels.get("session"))
        assertEquals(Json.obj("session" to "auto"), editorModes)
    }

    @Test
    fun `concurrent view updates preserve all session selections through restart`() {
        val store = VarroStore()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..100).map { id ->
                executor.submit {
                    start.await()
                    store.updateSessionModel("session$id", model("model$id"))
                    store.updateSessionPermissionMode("session$id", JsonPrimitive("auto"))
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val restored = restore(store)
        assertEquals(100, restored.sessionSelectedModels.size())
        assertEquals(100, restored.sessionPermissionModes.size())
        assertEquals(store.sessionSelectedModels, restored.sessionSelectedModels)
        assertEquals(store.sessionPermissionModes, restored.sessionPermissionModes)
    }

    private fun model(id: String) = Json.obj("providerID" to "openai", "modelID" to id, "variant" to "high")

    private fun restore(store: VarroStore) = VarroStore().apply {
        loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.state), VarroStore.StoreState::class.java))
    }
}
