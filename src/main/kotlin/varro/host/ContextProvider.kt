package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.lang.annotation.HighlightSeverity
import varro.protocol.Json
import varro.server.WorkspacePaths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks what the user is looking at, so the composer can offer it as context.
 *
 * Port of `src/extension/context-provider.ts`. It produces upstream's
 * `EditorContext` shape: the workspace roots, the active file and its language,
 * the current selection range, and the diagnostics the IDE has computed.
 *
 * Updates are coalesced. Caret and selection listeners fire on every keystroke,
 * and pushing a context snapshot per keystroke would flood the JS bridge for a
 * payload the composer only reads when the user sends a message.
 */
class ContextProvider(private val project: Project) : Disposable {

    private val current = AtomicReference(JsonObject())
    private val refreshRequested = AtomicBoolean(false)
    private var lastTextEditor: java.lang.ref.WeakReference<Editor>? = null

    /**
     * Listeners are attached after construction, never passed in.
     *
     * Handing a callback to the constructor let this class notify its owner from
     * inside the owner's own constructor, reaching properties that had not been
     * initialized yet. Attaching afterwards makes that impossible rather than
     * merely discouraged.
     */
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()

    val context: JsonObject get() = current.get()

    fun addListener(listener: (JsonObject) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners(snapshot: JsonObject) =
        listeners.forEach { runCatching { it(snapshot) } }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = scheduleRefresh()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = scheduleRefresh()
            },
        )

        // Selection and caret listeners are attached per editor, so newly opened
        // editors have to be picked up as they are created.
        val multicaster = com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
        multicaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(event: SelectionEvent) = scheduleRefresh()
            },
            this,
        )
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = scheduleRefresh()
            },
            this,
        )
        multicaster.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = scheduleRefresh()
        }, this)

        com.intellij.openapi.editor.EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) = scheduleRefresh()
            },
            this,
        )

        // Seed the snapshot so the boot payload has something real to carry.
        // No listener can exist yet, so nothing is notified.
        current.set(runCatching { buildContext() }.getOrElse { JsonObject() })
    }

    /**
     * Coalesces bursts of editor events into one snapshot. The flag is the
     * de-duplication: a refresh already queued absorbs everything that arrives
     * before it runs.
     */
    fun scheduleRefresh() {
        if (!refreshRequested.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            refreshRequested.set(false)
            refresh()
        }, project.disposed)
    }

    /** Recomputes the snapshot and notifies when it actually changed. */
    fun refresh() {
        val next = runCatching { buildContext() }.getOrElse { JsonObject() }
        val previous = current.getAndSet(next)
        if (previous.toString() == next.toString()) return
        notifyListeners(next)
    }

    /** Re-sends the current snapshot without recomputing, for `context/request`. */
    fun replay() = notifyListeners(current.get())

    // --- Snapshot construction ------------------------------------------------

    private fun buildContext(): JsonObject = ApplicationManager.getApplication().runReadAction<JsonObject> {
        val roots = workspaceFolders()
        val workspacePath = project.guessProjectDir()?.path ?: project.basePath

        val editor = selectedTextEditor()
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }

        val context = JsonObject().apply {
            addProperty("workspacePath", workspacePath)
            addProperty("workspaceDirectory", workspacePath)
            add("workspaceFolders", roots)
            addProperty("activeWorkspacePath", workspacePath)
            add("activeFile", activeFile(file, workspacePath))
            add("selection", selection(editor))
            add("editorText", editorText(editor, file, workspacePath))
        }

        val diagnostics = diagnostics(file)
        context.add("diagnostics", diagnostics.entries)
        context.addProperty("diagnosticsTotal", diagnostics.total)
        context
    }

    private fun workspaceFolders(): JsonArray {
        val folders = JsonArray()
        val roots = ProjectRootManager.getInstance(project).contentRoots
        if (roots.isEmpty()) {
            val base = project.basePath ?: return folders
            folders.add(Json.obj("name" to project.name, "path" to base))
            return folders
        }
        // Content roots include module and generated-source roots. Only the
        // top-level ones are workspace folders in the VS Code sense; a nested root
        // would make the session list offer the same project twice.
        val topLevel = roots.filter { root ->
            roots.none { other -> other != root && WorkspacePaths.relativeWithin(root.path, other.path) != null }
        }
        topLevel.forEach { root ->
            folders.add(Json.obj("name" to root.name, "path" to root.path))
        }
        return folders
    }

    private fun selectedTextEditor(): Editor? {
        val manager = FileEditorManager.getInstance(project)
        val selected = (manager.selectedEditor as? TextEditor)?.editor ?: manager.selectedTextEditor
        if (selected != null && !selected.isDisposed && selected.project == project &&
            FileDocumentManager.getInstance().getFile(selected.document)?.isInLocalFileSystem == true
        ) {
            lastTextEditor = java.lang.ref.WeakReference(selected)
            return selected
        }
        // A chat or report tab should not replace the source file in auto-context.
        return lastTextEditor?.get()?.takeIf { !it.isDisposed }
    }

    private fun activeFile(file: VirtualFile?, workspacePath: String?): JsonObject? {
        if (file == null || !file.isInLocalFileSystem) return null
        return Json.obj(
            "path" to file.path,
            "relativePath" to (WorkspacePaths.relativeWithin(file.path, workspacePath) ?: file.name),
            "language" to languageId(file),
        )
    }

    private fun selection(editor: Editor?): JsonObject? {
        val model = editor?.selectionModel ?: return null
        if (!model.hasSelection()) return null
        val document = editor.document
        // The webview's line references are 1-based, like the editor gutter.
        val startLine = document.getLineNumber(model.selectionStart) + 1
        var endLine = document.getLineNumber(model.selectionEnd) + 1
        // A selection ending at column 0 visually stops on the previous line;
        // including the empty line would attach one line more than the user
        // highlighted.
        if (endLine > startLine && model.selectionEnd == document.getLineStartOffset(endLine - 1)) {
            endLine -= 1
        }
        return Json.obj("startLine" to startLine, "endLine" to endLine)
    }

    /**
     * The selected text itself, or the whole buffer when it is unsaved. An unsaved
     * buffer is the one case where reading the file from disk would give the model
     * something different from what the user is looking at.
     */
    private fun editorText(editor: Editor?, file: VirtualFile?, workspacePath: String?): JsonObject? {
        if (editor == null) return null
        val model = editor.selectionModel
        val document = editor.document
        val unsaved = file != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)

        val (kind, text, startLine, endLine) = when {
            model.hasSelection() -> {
                val start = document.getLineNumber(model.selectionStart) + 1
                val end = document.getLineNumber(model.selectionEnd) + 1
                TextSlice("selection", model.selectedText.orEmpty(), start, end)
            }
            unsaved -> TextSlice("dirty-buffer", document.text, 1, document.lineCount.coerceAtLeast(1))
            else -> return null
        }
        if (text.isEmpty()) return null

        val truncated = text.length > MAX_EDITOR_TEXT
        return Json.obj(
            "kind" to kind,
            "path" to file?.path,
            "relativePath" to (file?.let { WorkspacePaths.relativeWithin(it.path, workspacePath) ?: it.name } ?: ""),
            "language" to (file?.let { languageId(it) } ?: "plaintext"),
            "range" to Json.obj("startLine" to startLine, "endLine" to endLine),
            "text" to if (truncated) text.take(MAX_EDITOR_TEXT) else text,
            "truncated" to truncated,
        )
    }

    private data class TextSlice(val kind: String, val text: String, val startLine: Int, val endLine: Int)

    private data class Diagnostics(val entries: JsonArray, val total: Int)

    /**
     * Diagnostics for the active file, capped so a file with thousands of
     * warnings cannot dominate the prompt. `total` still reports the real count
     * so the composer can say how many were left out.
     */
    private fun diagnostics(file: VirtualFile?): Diagnostics {
        val entries = JsonArray()
        if (file == null) return Diagnostics(entries, 0)

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return Diagnostics(entries, 0)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            ?: return Diagnostics(entries, 0)

        var total = 0
        runCatching {
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.INFORMATION,
                0,
                document.textLength,
            ) { info ->
                val severity = when {
                    info.severity >= HighlightSeverity.ERROR -> "error"
                    info.severity >= HighlightSeverity.WARNING -> "warning"
                    else -> "info"
                }
                val description = info.description
                if (!description.isNullOrBlank()) {
                    total += 1
                    if (entries.size() < MAX_DIAGNOSTICS) {
                        entries.add(
                            Json.obj(
                                "path" to file.path,
                                "severity" to severity,
                                "message" to description,
                                "line" to document.getLineNumber(info.startOffset) + 1,
                            ),
                        )
                    }
                }
                true
            }
        }.onFailure {
            // Highlighting may not have run yet for a freshly opened file; an empty
            // diagnostics list is correct in that case, not an error.
            return Diagnostics(entries, entries.size())
        }
        // Referenced so the analyzer keeps the file alive for the call above.
        psiFile.virtualFile
        return Diagnostics(entries, total)
    }

    private fun languageId(file: VirtualFile): String =
        file.fileType.name.lowercase().ifBlank { file.extension ?: "plaintext" }

    override fun dispose() = Unit

    companion object {
        private const val MAX_DIAGNOSTICS = 50
        private const val MAX_EDITOR_TEXT = 100_000
    }
}
