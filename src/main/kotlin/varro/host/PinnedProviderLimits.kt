package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Project-owned polling also works with all chat surfaces closed. Widget ticks never wait on HTTP. */
internal class PinnedProviderLimits(
    private val catalog: (String?) -> JsonElement?,
    private val quota: (String, String?) -> JsonObject,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    data class Display(val text: String = "", val tooltip: String = "")
    private var connection: String? = null
    private var directory: String? = null
    private var preferences = JsonObject()
    private var providers = emptyList<JsonObject>()
    private val limits = mutableMapOf<String, JsonObject>()
    private var generation = 0L
    private var nextPoll = 0L
    private var inFlight = false
    private var closed = false
    private var rotationId: String? = null
    private var rotationDeadline = 0L
    private var displayMode = "rotating"
    private var refreshError: String? = null

    @Synchronized fun tick(connectionKey: String?, directory: String?, nextPreferences: JsonObject,
                           visibleProviders: Set<String>, mode: String): Display {
        if (closed) return Display()
        if (mode != displayMode) { displayMode = mode; rotationId = null }
        val relevant = JsonObject().apply {
            for (key in listOf("providerLimitPins", "providerOrder", "hiddenProviders", "hiddenModels", "addedModels", "removedModels"))
                nextPreferences.get(key)?.let { add(key, it) }
        }
        if (connection != connectionKey || this.directory != directory || preferences != relevant) {
            if (connection != connectionKey || this.directory != directory) { providers = emptyList(); limits.clear(); refreshError = null }
            connection = connectionKey
            this.directory = directory
            preferences = relevant.deepCopy()
            generation++
            inFlight = false
            nextPoll = 0
            rotationId = null
            limits.keys.retainAll(preferences.obj("providerLimitPins")?.keySet().orEmpty())
        }
        val pins = preferences.obj("providerLimitPins") ?: return Display()
        if (connection == null || pins.size() == 0) return Display()
        if (!inFlight && clock() >= nextPoll) {
            inFlight = true
            nextPoll = clock() + 120_000
            val epoch = generation
            val pinned = pins.keySet().toSet()
            executor.submit {
                try {
                    val entries = catalog(directory).asObjectOrNull().arr("providers")?.mapNotNull { it.asObjectOrNull() }
                        ?.filter { it.text("id") != null && it.text("name") != null }.orEmpty()
                    synchronized(this) {
                        if (closed || epoch != generation) return@submit
                        providers = entries.map { it.deepCopy() }
                        refreshError = null
                    }
                    val tasks = entries.filter { it.str("id") in pinned }.map { provider -> executor.submit {
                        val id = provider.str("id")!!
                        val status = quota(id, directory)
                        synchronized(this) { if (!closed && epoch == generation) limits[id] = status.deepCopy() }
                    } }
                    tasks.forEach { it.get() }
                } catch (_: Exception) {
                    synchronized(this) { if (!closed && epoch == generation) refreshError = "Could not refresh pinned provider limits." }
                } finally {
                    synchronized(this) { if (epoch == generation) inFlight = false }
                }
            }
        }
        return render(visibleProviders, mode)
    }

    @Synchronized fun invalidate() { generation++; inFlight = false; nextPoll = 0; limits.clear(); refreshError = null }

    @Synchronized fun acceptUpdate(update: JsonObject) {
        val status = update.obj("status") ?: return
        val id = status.str("providerID") ?: return
        if (!closed && connection != null && update.str("directory") == directory && status.text("modelID") == null &&
            preferences.obj("providerLimitPins")?.has(id) == true) limits[id] = status.deepCopy()
    }

    private fun render(visible: Set<String>, mode: String): Display {
        val pins = preferences.obj("providerLimitPins") ?: return Display()
        val order = preferences.arr("providerOrder")?.strings().orEmpty()
        val priority = listOf("openai", "anthropic", "github-copilot", "groq", "google")
        fun index(values: List<String>, id: String) = values.indexOf(id).takeIf { it >= 0 } ?: values.size
        fun sortedLast(provider: JsonObject): Boolean {
            val id = provider.str("id")!!
            val models = provider.obj("models")?.keySet().orEmpty()
            val listed = models.filter { model -> if (models.size >= 50) "$id:$model" in preferences.arr("addedModels")?.strings().orEmpty()
                else "$id:$model" !in preferences.arr("removedModels")?.strings().orEmpty() }
            return id in preferences.arr("hiddenProviders")?.strings().orEmpty() ||
                listed.all { "$id:$it" in preferences.arr("hiddenModels")?.strings().orEmpty() }
        }
        val entries = providers.filter { it.str("id") in pins.keySet() && it.str("id") !in visible && summary(limits[it.str("id")]).isNotEmpty() }
            .sortedWith(compareBy<JsonObject> { sortedLast(it) }.thenBy { index(order, it.str("id")!!) }
                .thenBy { index(priority, it.str("id")!!) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.str("name")!! }.thenBy { it.str("id") })
        if (entries.isEmpty()) { rotationId = null; return Display() }
        val ids = entries.map { it.str("id")!! }
        if (rotationId !in ids) { rotationId = ids.first(); rotationDeadline = clock() + 15_000 }
        else if (clock() >= rotationDeadline && mode != "all") {
            rotationId = ids[(ids.indexOf(rotationId) + 1) % ids.size]; rotationDeadline = clock() + 15_000
        }
        val shown = if (mode == "all") entries else entries.filter { it.str("id") == rotationId }
        return Display(shown.joinToString(" · ") {
            "${pins.str(it.str("id")!!)?.trim()?.takeIf(String::isNotEmpty) ?: it.str("name")}: ${summary(limits[it.str("id")])}"
        }, entries.joinToString("\n\n") {
            val status = limits[it.str("id")]
            "${it.str("name")}: ${summary(status)}" + (status.str("note")?.let { note -> "\n$note" } ?: "")
        } + (refreshError?.let { "\n\n$it" } ?: "") + "\n\nPercentages show remaining quota. Manage pins and names in the provider list.")
    }

    override fun close() { synchronized(this) { closed = true; generation++; limits.clear() }; executor.shutdownNow() }

    companion object {
        internal fun summary(status: JsonObject?): String {
            if (status.str("status") != "available") return ""
            val periods = listOf("5h", "D", "W", "M")
            return status.arr("windows")?.mapNotNull { it.asObjectOrNull() }?.sortedBy {
                periods.indexOf(period(it)).takeIf { index -> index >= 0 } ?: periods.size
            }?.joinToString(", ") { window ->
                val label = period(window).lowercase().ifEmpty { window.str("label").orEmpty() }
                val remaining = window.num("remaining") ?: 0.0
                val used = window.num("percent")?.takeIf(Double::isFinite) ?: window.num("limit")?.takeIf { it > 0 }?.let { (1 - remaining / it) * 100 }
                val value = if (used != null) "${kotlin.math.floor((100 - used).coerceIn(0.0, 100.0) + 0.5).toInt()}%"
                else "${NumberFormat.getNumberInstance().format(remaining)} ${window.str("unit").takeUnless { it == "unknown" } ?: "left"}"
                "$label $value"
            }.orEmpty()
        }

        private fun period(window: JsonObject): String {
            val id = window.str("id").orEmpty().lowercase()
            val label = window.str("label").orEmpty().lowercase()
            return when {
                "month" in id || "month" in label -> "M"
                "week" in id || "seven_day" in id || "week" in label -> "W"
                "five_hour" in id || id == "current-session" || "5-hour" in label || "5 hour" in label || label == "current session" -> "5h"
                "day" in id || "daily" in id || "day" in label || "daily" in label || id == "requests" -> "D"
                else -> ""
            }
        }
    }
}
