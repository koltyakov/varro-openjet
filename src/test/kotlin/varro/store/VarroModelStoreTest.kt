package varro.store

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class VarroModelStoreTest {
    @Test
    fun `model preferences are application scoped`() {
        assertArrayEquals(arrayOf(Service.Level.APP), VarroModelStore::class.java.getAnnotation(Service::class.java).value)
    }

    @Test
    fun `first project preferences migrate and later projects cannot overwrite them`() {
        val first = VarroStore()
        first.loadState(VarroStore.StoreState().apply {
            modelPreferences = """{"pinned":["provider/first"],"customNames":{"provider/first":"My model"}}"""
        })
        val second = VarroStore()
        second.loadState(VarroStore.StoreState().apply {
            modelPreferences = """{"pinned":["provider/second"]}"""
        })
        val global = VarroModelStore()
        assertFalse(global.migrate(JsonObject()))
        assertTrue(global.migrate(first.legacyModelPreferences))
        assertFalse(global.migrate(second.legacyModelPreferences))
        assertEquals(first.legacyModelPreferences, global.modelPreferences)

        val updated = Json.parse("""{"hidden":["provider/first"],"order":["provider/second"]}""").asJsonObject
        global.modelPreferences = updated
        val restored = restore(global)
        assertEquals(updated, restored.modelPreferences)
        assertFalse(restored.migrate(first.legacyModelPreferences))
    }

    @Test
    fun `clearing preferences remains authoritative after restart`() {
        val global = VarroModelStore()
        global.modelPreferences = JsonObject()
        val restored = restore(global)
        assertFalse(restored.migrate(Json.obj("pinned" to listOf("provider/old"))))
        assertEquals(JsonObject(), restored.modelPreferences)
    }

    @Test
    fun `reading and writing preferences does not retain mutable JSON references`() {
        val global = VarroModelStore()
        val preferences = Json.obj("custom" to "saved")
        global.modelPreferences = preferences
        preferences.addProperty("custom", "changed")
        global.modelPreferences.addProperty("custom", "also changed")
        assertEquals(Json.obj("custom" to "saved"), global.modelPreferences)
    }

    private fun restore(store: VarroModelStore): VarroModelStore = VarroModelStore().apply {
        loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.state), VarroModelStore.StoreState::class.java))
    }
}
