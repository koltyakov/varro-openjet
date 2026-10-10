package varro.server

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import varro.protocol.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Saved choices and cancellable reviews. Classification never delays status reads. */
internal class OpenCodeV2BackgroundServices(
    annotations: OpenCodeV2SessionState,
    private val work: OpenCodeV2BackgroundWork,
    private val classify: ((JsonObject, String?, () -> Boolean) -> Boolean?)?,
    private val changed: ((String, String?) -> Unit)?,
) {
    private val log = logger<OpenCodeV2BackgroundServices>()
    private val state = OpenCodeV2SessionState(annotations.directory.resolve("background-services"))
    private val judgments = mutableMapOf<String, AtomicBoolean>()
    private val timers = mutableMapOf<String, ScheduledFuture<*>>()
    private val manual = mutableSetOf<String>()
    private var worker: ScheduledThreadPoolExecutor? = null

    private fun executor(): ScheduledThreadPoolExecutor = worker ?: ScheduledThreadPoolExecutor(4) { runnable ->
        Thread(runnable, "varro-background-process-review").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; worker = this }

    fun read(sessionID: String) = state.read(sessionID)

    fun review(shell: JsonObject, directory: String?, saved: JsonObject) {
        val id = shell.str("id") ?: return
        val sessionID = shell.obj("metadata").str("sessionID") ?: return
        if (shell.str("status") != "running") return
        if (saved.bool("manual:$id") == true || (saved.bool(id) != null && saved.obj("review:$id") == null)) {
            cancel(id)
            return
        }
        schedule(shell, sessionID, directory, saved.obj("review:$id").long("next"))
    }

    @Synchronized private fun schedule(shell: JsonObject, sessionID: String, directory: String?, next: Long?) {
        val classifier = classify ?: return
        val id = shell.str("id") ?: return
        if (id in judgments || id in manual) return
        val now = System.currentTimeMillis()
        if (next != null && next > now) { queue(id, sessionID, directory, next); return }
        if (judgments.size >= 4) { queue(id, sessionID, directory, now + 20_000); return }
        timers.remove(id)?.cancel(false)
        val cancelled = AtomicBoolean(false)
        judgments[id] = cancelled
        executor().execute {
            try {
                val service = try { classifier(shell, directory, cancelled::get) } catch (failure: Exception) {
                    if (!cancelled.get()) log.warn("Background process classification failed; preserving its wait setting", failure)
                    null
                }
                val reviewAt = nextReviewAt(shell.obj("time").long("started") ?: now, System.currentTimeMillis())
                var notify = false
                synchronized(this) {
                    if (!cancelled.get() && id !in manual && work.hasShell(id)) {
                        val patch = Json.obj("review:$id" to Json.obj("next" to reviewAt))
                        if (service != null) patch.addProperty(id, service)
                        state.update(sessionID, patch, onlyIfMissing = "manual:$id") {
                            if (cancelled.get()) throw java.util.concurrent.CancellationException()
                        }
                        val saved = state.read(sessionID)
                        if (!cancelled.get() && saved.bool("manual:$id") != true) {
                            val previous = work.serviceCount(sessionID)
                            saved.bool(id)?.let { work.setService(id, it) }
                            queue(id, sessionID, directory, reviewAt)
                            notify = previous != work.serviceCount(sessionID)
                        }
                    }
                }
                if (notify) changed?.invoke(sessionID, directory)
            } catch (failure: Exception) {
                if (!cancelled.get()) {
                    log.warn("Could not save background process judgment", failure)
                    queue(id, sessionID, directory, System.currentTimeMillis() + 60_000)
                }
            } finally {
                synchronized(this) { if (judgments[id] === cancelled) judgments.remove(id) }
            }
        }
    }

    @Synchronized fun setService(shell: JsonObject, sessionID: String, directory: String?, service: Boolean, checkCancelled: () -> Unit) {
        val id = shell.str("id") ?: error("Invalid background process ID")
        manual.add(id)
        cancel(id)
        work.observe("shell.created", Json.obj("info" to shell), directory)
        try {
            state.update(sessionID, Json.obj(id to service, "manual:$id" to true), checkCancelled = checkCancelled)
        } catch (failure: Exception) {
            manual.remove(id)
            queue(id, sessionID, directory, System.currentTimeMillis() + 60_000)
            throw failure
        }
        work.setService(id, service)
    }

    @Synchronized private fun queue(id: String, sessionID: String, directory: String?, next: Long) {
        if (classify == null || id in timers || id in manual) return
        timers[id] = executor().schedule({
            synchronized(this) { timers.remove(id) }
            try { changed?.invoke(sessionID, directory) } catch (failure: Exception) {
                log.warn("Could not refresh background processes for review", failure)
                if (work.hasShell(id)) queue(id, sessionID, directory, System.currentTimeMillis() + 60_000)
            }
        }, maxOf(0L, next - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
    }

    @Synchronized fun cancel(id: String) {
        judgments.remove(id)?.set(true)
        timers.remove(id)?.cancel(false)
    }

    @Synchronized fun reset() {
        judgments.values.forEach { it.set(true) }; judgments.clear()
        timers.values.forEach { it.cancel(false) }; timers.clear(); manual.clear()
        worker?.shutdownNow(); worker = null
    }

    companion object {
        internal fun nextReviewAt(started: Long, now: Long): Long {
            val minutes = maxOf(0L, (now - started) / 60_000)
            val next = listOf(5L, 10L, 20L, 30L).firstOrNull { it > minutes } ?: (minutes / 30 + 1) * 30
            return started + next * 60_000
        }
    }
}
