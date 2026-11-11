package varro.host

import com.google.gson.JsonElement
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded failure inventory. Never forwards addon errors or command lines to the browser. */
internal class StartupDiagnostics(
    private val apiVersion: () -> Int,
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
) {
    fun read(directory: String?, isCancelled: () -> Boolean): JsonElement {
        if (apiVersion() != 2) return Json.array(emptyList<Any>())
        val options = RequestOptions(directory = directory, maxResponseBytes = 1024 * 1024,
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1), isCancelled = isCancelled)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val tasks = listOf("plugin", "mcp").map { kind -> executor.submit<List<JsonElement>> {
                runCatching { request("GET", "/api/$kind", null, options).data.asObjectOrNull().arr("data")?.take(200).orEmpty() }.getOrDefault(emptyList())
            } }
            val failures = tasks.flatMapIndexed { index, task ->
                runCatching { task.get(maxOf(1, options.deadlineNanos!! - System.nanoTime()), TimeUnit.NANOSECONDS) }.getOrDefault(emptyList()).mapNotNull {
                    val item = it.asObjectOrNull()
                    val state = item.obj(if (index == 0) "state" else "status")
                    val name = item.str(if (index == 0) "id" else "name")
                    if (state.str("status") != "failed" || name.isNullOrBlank()) null else Json.obj(
                        "kind" to if (index == 0) "plugin" else "mcp",
                        "name" to name.replace(Regex("[\r\n\t\"]"), " ").take(120),
                        "timedOut" to Regex("timed? ?out|timeout", RegexOption.IGNORE_CASE).containsMatchIn(state.str("error").orEmpty()),
                    )
                }
            }.take(5)
            return Json.array(failures)
        } finally { executor.shutdownNow() }
    }
}
