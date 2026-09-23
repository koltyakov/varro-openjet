package varro.store

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

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

    @Test fun `stale views preserve explicit removals while changing other model preferences`() {
        val base = Json.obj("addedModels" to listOf("provider:one", "provider:two"),
            "removedModels" to emptyList<String>(), "pinnedModels" to emptyList<String>())
        val store = VarroModelStore().apply { modelPreferences = base }
        store.update(base, base.deepCopy().apply {
            add("addedModels", Json.array(listOf("provider:two")))
            add("removedModels", Json.array(listOf("provider:one")))
        })
        store.update(base, base.deepCopy().apply { add("pinnedModels", Json.array(listOf("provider:two"))) })
        val restored = VarroModelStore().apply { loadState(store.getState()) }.modelPreferences
        assertEquals(listOf("provider:one"), restored.arr("removedModels")!!.strings())
        assertEquals(listOf("provider:two"), restored.arr("addedModels")!!.strings())
        assertEquals(listOf("provider:two"), restored.arr("pinnedModels")!!.strings())
        store.update(restored, restored.deepCopy().apply {
            add("removedModels", Json.array(emptyList<String>()))
            add("addedModels", base.get("addedModels"))
        })
        assertTrue(store.modelPreferences.arr("removedModels")!!.isEmpty)
        assertEquals(setOf("provider:one", "provider:two"), store.modelPreferences.arr("addedModels")!!.strings().toSet())
    }

    private fun restore(store: VarroModelStore): VarroModelStore = VarroModelStore().apply {
        loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.state), VarroModelStore.StoreState::class.java))
    }
}
