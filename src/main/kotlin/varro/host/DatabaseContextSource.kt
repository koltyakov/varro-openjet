package varro.host

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.intellij.openapi.actionSystem.DataContext

/** Optional integration boundary. Core host classes never load Database Tools classes. */
interface DatabaseContextSource {
    fun addListener(listener: () -> Unit)
    fun snapshot(): JsonObject?
    fun environment(activeContext: JsonObject?): JsonObject?
    fun capture(context: DataContext): JsonObject?
    fun isAvailable(context: DataContext): Boolean
    fun canDrop(attached: Any?): Boolean
    fun captureDrop(attached: Any?): List<JsonObject>
    fun searchTables(query: String, limit: Int): JsonArray
    fun captureTable(id: String): JsonObject?
}
