package varro.host

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import varro.server.OpenCodeServer
import varro.server.ServerStatus
import varro.store.NativeFileLock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Native entry points keep every destructive choice in explicit IDE confirmation dialogs. */
internal class HistoryMaintenance(private val project: Project, private val server: OpenCodeServer,
    private val notify: (String, Boolean) -> Unit) {
    private val pending = AtomicBoolean(false)
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(HistoryMaintenance::class.java)

    fun exportCsv() {
        if (server.isAttachOnly()) { notify("All-time usage CSV export requires the local database and statistics archive. Run it on the server host.", true); return }
        val destination = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export all-time OpenCode usage", "Daily usage by provider, model and reasoning setting. Dates are UTC.", "csv"), project)
            .save(project.basePath?.let(Path::of), "opencode-usage-all-time.csv")?.file?.toPath() ?: return
        background("Exporting all-time OpenCode usage") { indicator ->
            val path = LocalUsageDatabase.defaultPath()
            UsageCsv.validateDestination(destination, path)
            val result = UsageCsv.read(path, check = indicator::checkCanceled)
            indicator.checkCanceled()
            UsageCsv.write(destination, path, result, indicator::checkCanceled)
            notify("Exported ${result.rows} daily usage rows to $destination. Missing reasoning settings are marked unknown.", false)
        }
    }

    fun cleanup() {
        val input = Messages.showInputDialog(project, "How many calendar months should be kept? Uses last activity across ALL projects.",
            "Delete old OpenCode sessions", Messages.getQuestionIcon(), "6", object : com.intellij.openapi.ui.InputValidator {
                override fun checkInput(inputString: String) = Regex("[1-9][0-9]*").matches(inputString) && inputString.toIntOrNull() in 1..1200
                override fun canClose(inputString: String) = checkInput(inputString)
            }) ?: return
        val months = input.toIntOrNull()?.takeIf { it in 1..1200 } ?: return
        val preserve = Messages.showYesNoCancelDialog(project,
            "Preserve trimmed parent conversations before deletion?\n\nYes keeps prompts, final responses and pasted-text/annotation contents in a separate plaintext transcript database. Subagent transcripts, tool output, reasoning and attachment bytes are omitted.\n\nNo preserves usage statistics only. Cancel changes nothing.",
            "Preserve transcripts?", "Preserve transcripts", "Statistics only", "Cancel", Messages.getQuestionIcon())
        if (preserve == Messages.CANCEL) return
        background("OpenCode session cleanup") { indicator ->
            require(server.currentStatus() is ServerStatus.Running && !server.isAttachOnly() && server.isManaged()) {
                "Connect to a verified local managed OpenCode server before cleanup"
            }
            val archive = UsageArchive(LocalUsageDatabase.defaultPath())
            val started = System.nanoTime()
            var lastLog = 0L
            val service = SessionCleanup(archive, server.transport.apiVersion, { server.isManaged() && !server.isAttachOnly() },
                { method, path, options -> server.transport.request(method, path, options = options) },
                { operation -> server.withStoppedServer(operation) }, { phase ->
                    indicator.text = phase
                    indicator.text2 = "${(System.nanoTime() - started) / 1_000_000_000}s elapsed"
                    if (System.nanoTime() - lastLog >= 10_000_000_000) { log.info("Session cleanup: $phase"); lastLog = System.nanoTime() }
                })
            service.locked(indicator::checkCanceled) {
                val inventory = service.inventory(indicator::checkCanceled)
                val cutoff = SessionCleanup.retentionCutoff(months)
                val eligible = (inventory.sessions + inventory.legacy).count { it.updated < cutoff }
                var confirmed = false
                ApplicationManager.getApplication().invokeAndWait {
                    if (!project.isDisposed) confirmed = Messages.showYesNoDialog(project,
                        "Permanently delete up to $eligible session records last touched before ${java.time.Instant.ofEpochMilli(cutoff)} across ALL projects?\n\nUsage statistics are archived first to ${archive.archivePath}.\n" +
                            (if (preserve == Messages.YES) "Trimmed parent transcripts are saved in plaintext to ${archive.transcriptPath}.\n" else "Conversation content is not preserved.\n") +
                            "Active sessions and trees with newer children are kept. Only checked session-owned generated files are removed. Original attachments, source files and repositories are not deleted.\n\n" +
                            (if (inventory.legacy.any { it.updated < cutoff }) "Legacy V1 cleanup stops and restarts OpenCode. " else "") +
                            "Close other OpenCode clients and back up first. Do not resume candidates during cleanup. Cancellation never abandons an already-started deletion.",
                        "Confirm permanent session deletion", "Delete old sessions", "Cancel", Messages.getWarningIcon()) == Messages.YES
                }
                indicator.checkCanceled()
                if (confirmed) {
                    log.info("Session cleanup started: $eligible eligible records; usage archive ${archive.archivePath}; transcripts=${preserve == Messages.YES}")
                    val result = service.run(inventory, cutoff, preserve == Messages.YES, indicator::checkCanceled)
                    notify("Cleanup ${if (result.cancelled) "cancelled" else "complete"}. ${result.deleted} session records deleted. Archives are saved; unfinished file cleanup can be resumed. Details are in the IDE log.", false)
                }
            }
        }
    }

    fun compact() = background("OpenCode database compaction") { indicator ->
        require(server.currentStatus() is ServerStatus.Running && !server.isAttachOnly() && server.isManaged()) { "Database compaction requires a connected verified local managed server" }
        val path = LocalUsageDatabase.defaultPath()
        val release = NativeFileLock.acquire(Path.of("${HistoryDatabase.sidecar(path, "stats")}.cleanup.lock"), indicator::checkCanceled)
        try {
            val before = DatabaseMaintenance.inspect(path, indicator::checkCanceled)
            if (before.reusable == 0L && before.wal == 0L) { notify("No unused SQLite pages to reclaim. Stored history and attachments still occupy this space.", false); return@background }
            require(before.available >= before.required) { "Compaction requires at least ${before.required / 1_000_000} MB free; ${before.available / 1_000_000} MB is available" }
            var confirmed = false
            ApplicationManager.getApplication().invokeAndWait {
                if (!project.isDisposed) confirmed = Messages.showYesNoDialog(project,
                    "Compact $path?\n\n${before.reusable / 1_000_000} MB is reusable; the WAL occupies ${before.wal / 1_000_000} MB. At least ${before.required / 1_000_000} MB free is required.\n\nOpenCode will stop and restart. This can take several minutes. Close other OpenCode clients and back up first. Active work or pending decisions prevent maintenance. Session content and archives are not changed.",
                    "Compact OpenCode database", "Compact database", "Cancel", Messages.getWarningIcon()) == Messages.YES
            }
            indicator.checkCanceled()
            if (!confirmed) return@background
            indicator.text = "Preparing to stop OpenCode"
            val after = server.withStoppedServer {
                try { DatabaseMaintenance.compact(path, indicator::checkCanceled) { indicator.text = it } }
                finally { indicator.text = "Restoring OpenCode" }
            }
            notify("Database compacted. ${maxOf(0L, before.bytes - after.bytes) / 1_000_000} MB reclaimed. OpenCode restarted.", false)
        } finally { release() }
    }

    private fun background(title: String, operation: (ProgressIndicator) -> Unit) {
        if (!pending.compareAndSet(false, true)) { notify("Another history operation is already running in this project.", false); return }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try { operation(indicator) }
                catch (_: com.intellij.openapi.progress.ProcessCanceledException) { notify("$title cancelled. Check OpenCode connection status before retrying.", false) }
                catch (failure: Exception) { log.warn("$title stopped", failure); notify("$title stopped. ${failure.message}", true) }
                finally { pending.set(false) }
            }
        })
    }
}
