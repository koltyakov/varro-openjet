package varro.host

import com.google.gson.JsonObject
import varro.protocol.*

/** Pause annotations share the metadata format used by Varro's V2 adapter. */
internal object SessionPauses {
    fun read(metadata: JsonObject?): Map<String, Long> = metadata.obj("varro").arr("pauses")
        ?.mapNotNull { entry ->
            val pause = entry.asObjectOrNull()
            val id = pause.str("messageId") ?: return@mapNotNull null
            val time = pause.long("pausedAt") ?: return@mapNotNull null
            id to time
        }.orEmpty().toMap()

    fun completedAt(created: Long, completed: Long?, pausedAt: Long): Long =
        maxOf(created, minOf(completed ?: pausedAt, pausedAt))

    fun capUsage(info: JsonObject, pauses: Map<String, Long>): JsonObject {
        val pausedAt = pauses[info.str("id")] ?: return info
        val time = info.obj("time") ?: return info
        val created = time.long("created") ?: return info
        return info.deepCopy().apply {
            obj("time")!!.addProperty("completed", completedAt(created, time.long("completed"), pausedAt))
        }
    }
}
