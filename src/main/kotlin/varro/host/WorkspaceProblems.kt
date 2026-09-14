package varro.host

import com.google.gson.JsonObject
import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import varro.protocol.Json
import varro.protocol.bool
import varro.protocol.int
import varro.protocol.str
import varro.settings.VarroSettings
import kotlin.math.abs

/** Reads diagnostics already computed by the IDE, without starting inspections. */
object WorkspaceProblems {
    fun highlights(project: Project, file: VirtualFile?, editor: Editor? = null): List<JsonObject> {
        if (file == null || !file.isValid || !file.isInLocalFileSystem) return emptyList()
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return emptyList()
        val entries = mutableListOf<JsonObject>()
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.INFORMATION, 0, document.textLength) { info ->
            diagnostic(file, document, info, editor)?.let(entries::add)
            true
        }
        return entries
    }

    fun diagnostic(file: VirtualFile, document: Document, info: HighlightInfo, editor: Editor? = null): JsonObject? {
        val message = info.description?.takeIf { it.isNotBlank() } ?: return null
        val start = info.startOffset.coerceIn(0, document.textLength)
        val end = info.endOffset.coerceIn(start, document.textLength)
        val line = document.getLineNumber(start)
        val endLine = document.getLineNumber(end)
        val selection = editor?.selectionModel
        return Json.obj(
            "path" to file.path,
            "severity" to severity(info.severity.myVal),
            "message" to message,
            "line" to line + 1,
            "column" to start - document.getLineStartOffset(line) + 1,
            "endLine" to endLine + 1,
            "endColumn" to end - document.getLineStartOffset(endLine) + 1,
            "intersectsSelection" to (selection?.hasSelection() == true && intersects(start, end, selection.selectionStart, selection.selectionEnd)),
        ).apply { info.inspectionToolId?.let { addProperty("code", it) } }
    }

    fun fromProblem(problem: FileProblem, highlights: List<JsonObject>, severityValue: Int? = null): JsonObject? {
        if (!problem.file.isValid || !problem.file.isInLocalFileSystem) return null
        highlights.firstOrNull {
            it.int("line") == problem.line + 1 && it.int("column") == problem.column + 1 && it.str("message") == problem.text
        }?.let { return it.deepCopy() }
        return Json.obj(
            "path" to problem.file.path, "message" to problem.text,
            "severity" to (severityValue?.let(::severity) ?: "info"), "line" to (problem.line + 1).coerceAtLeast(1),
            "column" to (problem.column + 1).coerceAtLeast(1),
        )
    }

    fun snapshot(project: Project): JsonObject = ApplicationManager.getApplication().runReadAction<JsonObject> {
        check(VarroSettings.getInstance().chatEnableProblemsContext) { "Problems context is disabled in settings" }
        val collector = ProblemsCollector.getInstance(project)
        val documents = FileDocumentManager.getInstance()
        val files = (collector.getProblemFiles() + com.intellij.openapi.editor.EditorFactory.getInstance().allEditors
            .filter { it.project == project && !it.isDisposed }
            .mapNotNull { documents.getFile(it.document) }).distinct()
        val index = ProjectFileIndex.getInstance(project)
        val entries = files.filter { it.isValid && it.isInLocalFileSystem && index.isInContent(it) }
            .flatMap { file ->
                val highlights = highlights(project, file)
                highlights + collector.getFileProblems(file).filterIsInstance<FileProblem>().mapNotNull { fromProblem(it, highlights) }
            }.filter { isIssue(it) }
            .distinctBy { listOf(it.str("path"), it.int("line"), it.int("column"), it.str("message"), it.str("severity")) }
            .sortedWith(compareBy<JsonObject> { severityRank(it) }.thenBy { it.str("path") }.thenBy { it.int("line") }.thenBy { it.int("column") })
        Json.obj("diagnostics" to Json.array(entries), "total" to entries.size)
    }

    internal fun intersects(start: Int, end: Int, selectionStart: Int, selectionEnd: Int): Boolean =
        selectionStart < selectionEnd && start < selectionEnd &&
            (end > selectionStart || (start == end && start >= selectionStart))

    internal fun isIssue(diagnostic: JsonObject): Boolean = diagnostic.str("severity") in setOf("error", "warning")

    internal fun ranked(entries: List<JsonObject>, caretLine: Int): List<JsonObject> = entries.sortedWith(
        compareBy<JsonObject> { severityRank(it) > 1 }
            .thenByDescending { it.bool("intersectsSelection") == true }
            .thenBy { severityRank(it) }
            .thenBy { abs((it.int("line") ?: 1) - caretLine) },
    ).take(20)

    internal fun counts(entries: List<JsonObject>): JsonObject = Json.obj(
        "errors" to entries.count { it.str("severity") == "error" },
        "warnings" to entries.count { it.str("severity") == "warning" },
    )

    private fun severityRank(entry: JsonObject) = when (entry.str("severity")) { "error" -> 0; "warning" -> 1; else -> 2 }
    private fun severity(value: Int) = when {
        value >= HighlightSeverity.ERROR.myVal -> "error"
        value >= HighlightSeverity.WARNING.myVal -> "warning"
        else -> "info"
    }
}
