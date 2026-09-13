package varro.host

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.DataContext

/** Optional integration boundary. Core host classes never load Database Tools classes. */
interface DatabaseContextSource {
    fun addListener(listener: () -> Unit)
    fun snapshot(): JsonObject?
    fun capture(context: DataContext): JsonObject?
    fun isAvailable(context: DataContext): Boolean
}
