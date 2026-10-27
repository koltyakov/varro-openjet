package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
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
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
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
class ContextProvider(private val project: Project, private val hasConsumers: () -> Boolean = { true }) : Disposable {

    private val current = AtomicReference(JsonObject())
    private val refreshRequested = AtomicBoolean(false)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastTextEditor: java.lang.ref.WeakReference<Editor>? = null
    private var cachedRootPaths: List<String>? = null
    private var cachedFolders = JsonArray()
    private val diagnosticRevision = java.util.concurrent.atomic.AtomicLong()
    private data class DiagnosticKey(val file: String?, val stamp: Long?, val start: Int?, val end: Int?, val revision: Long)
    private var diagnosticKey: DiagnosticKey? = null
    private var cachedDiagnostics = emptyList<JsonObject>()

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
        project.getService(DatabaseContextSource::class.java)?.addListener(::scheduleRefresh)
        val connection = project.messageBus.connect(this)
        connection.subscribe(com.intellij.openapi.roots.ModuleRootListener.TOPIC, object : com.intellij.openapi.roots.ModuleRootListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) {
                ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
            }
        })
        connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() {
                diagnosticRevision.incrementAndGet()
                scheduleRefresh()
            }
        })
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
                override fun selectionChanged(event: SelectionEvent) {
                    if (event.editor.project == project) scheduleRefresh()
                }
            },
            this,
        )
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    if (event.editor.project == project) scheduleRefresh()
                }
            },
            this,
        )
        multicaster.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (event.document === lastTextEditor?.get()?.document) scheduleRefresh()
            }
        }, this)

        com.intellij.openapi.editor.EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.editor.project == project) scheduleRefresh()
                }
            },
            this,
        )

        // Services can be constructed under a background read lock. Seed workspace identity
        // without waiting for the EDT; createPanel refreshes editor state before building the page.
        current.set(ApplicationManager.getApplication().runReadAction<JsonObject> {
            val path = project.guessProjectDir()?.path ?: project.basePath
            Json.obj("workspacePath" to path, "workspaceDirectory" to path,
                "activeWorkspacePath" to path, "workspaceFolders" to workspaceFolders())
        })
    }

    /**
     * Coalesces editor events across EDT turns. A fixed window bounds the work
     * while still updating context during continuous typing or caret movement.
     */
    fun scheduleRefresh() {
        if (project.isDisposed || !hasConsumers()) return
        if (!refreshRequested.compareAndSet(false, true)) return
        refreshAlarm.addRequest({
            refreshRequested.set(false)
            if (!project.isDisposed && hasConsumers()) refresh()
        }, 75)
    }

    /** Recomputes the snapshot and notifies when it actually changed. */
    fun refresh() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeAndWait { refresh() }
            return
        }
        if (project.isDisposed) return
        val next = runCatching { buildContext() }.getOrElse { JsonObject() }
        val previous = current.getAndSet(next)
        if (previous == next) return
        notifyListeners(next)
    }

    /** Refreshes on demand, including when the project previously had no consumers. */
    fun replay() {
        refresh()
        notifyListeners(current.get())
    }

    /** Match existing editor state without changing the clipboard or saving a buffer. */
    fun matchCopiedSelection(text: String, plainTextOnly: Boolean): JsonObject? {
        var result: JsonObject? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            val editor = selectedTextEditor()
            val documents = FileDocumentManager.getInstance()
            val selected = editor?.selectionModel?.selectedText
            if (editor != null && selected != null && selected.replace("\r\n", "\n") == text.replace("\r\n", "\n")) {
                if (documents.isDocumentUnsaved(editor.document)) return@invokeAndWait
                val file = documents.getFile(editor.document)
                val root = workspaceFolders().mapNotNull { it.asJsonObject.get("path")?.asString }
                    .firstOrNull { WorkspacePaths.relativeWithin(file?.path, it) != null }
                if (file?.isInLocalFileSystem == true && root != null) {
                    result = Json.obj("type" to "file", "file" to Json.obj(
                        "path" to file.path, "relativePath" to WorkspacePaths.relativeWithin(file.path, root),
                        "type" to "file", "lineRanges" to listOf(selection(editor)),
                    ))
                    return@invokeAndWait
                }
            }
            if (plainTextOnly) result = TerminalService(project).matchCopiedText(text)
        }
        return result
    }

    // --- Snapshot construction ------------------------------------------------

    private fun buildContext(): JsonObject = ApplicationManager.getApplication().runReadAction<JsonObject> {
        val roots = workspaceFolders()
        val workspacePath = project.guessProjectDir()?.path ?: project.basePath

        val databaseSource = project.getService(DatabaseContextSource::class.java)
        val database = databaseSource?.snapshot()
        val databaseEnvironment = databaseSource?.environment(database)
        val editor = if (database == null) selectedTextEditor() else null
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }

        val context = JsonObject().apply {
            addProperty("workspacePath", workspacePath)
            addProperty("workspaceDirectory", workspacePath)
            add("workspaceFolders", roots)
            addProperty("activeWorkspacePath", workspacePath)
            add("activeFile", activeFile(file, workspacePath))
            add("selection", selection(editor))
            add("editorText", editorText(editor, file, workspacePath))
            add("databaseContext", database)
            add("databaseEnvironment", databaseEnvironment)
        }

        val selected = editor?.selectionModel?.takeIf { it.hasSelection() }
        val key = DiagnosticKey(file?.path, editor?.document?.modificationStamp,
            selected?.selectionStart, selected?.selectionEnd, diagnosticRevision.get())
        val diagnostics = if (!varro.settings.VarroSettings.getInstance().chatEnableProblemsContext) emptyList() else {
            if (diagnosticKey != key) {
                cachedDiagnostics = WorkspaceProblems.highlights(project, file, editor)
                diagnosticKey = key
            }
            cachedDiagnostics
        }
        context.add("diagnostics", Json.array(WorkspaceProblems.ranked(diagnostics, (editor?.caretModel?.logicalPosition?.line ?: 0) + 1)))
        context.addProperty("diagnosticsTotal", diagnostics.size)
        context.add("diagnosticCounts", WorkspaceProblems.counts(diagnostics))
        context
    }

    private fun workspaceFolders(): JsonArray {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        val paths = roots.map { it.path }.ifEmpty { listOfNotNull(project.basePath) }
        if (paths == cachedRootPaths) return cachedFolders
        val folders = JsonArray()
        if (roots.isEmpty()) {
            val base = project.basePath ?: return folders
            folders.add(Json.obj("name" to project.name, "path" to base))
            cachedRootPaths = paths
            cachedFolders = folders
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
        cachedRootPaths = paths
        cachedFolders = folders
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
     * Only unsaved text needs an inline snapshot. Saved selections use file line
     * references, which the composer can deduplicate against pinned selections.
     */
    private fun editorText(editor: Editor?, file: VirtualFile?, workspacePath: String?): JsonObject? {
        if (editor == null) return null
        val model = editor.selectionModel
        val document = editor.document
        val unsaved = file != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)

        val selected = if (model.hasSelection()) TextRange(model.selectionStart, model.selectionEnd) else null
        return editorTextSlice(document, selected, unsaved)?.apply {
            addProperty("path", file?.path)
            addProperty("relativePath", file?.let { WorkspacePaths.relativeWithin(it.path, workspacePath) ?: it.name } ?: "")
            addProperty("language", file?.let { languageId(it) } ?: "plaintext")
        }
    }

    private fun languageId(file: VirtualFile): String =
        file.fileType.name.lowercase().ifBlank { file.extension ?: "plaintext" }

    override fun dispose() = Unit

    companion object {
        private const val MAX_EDITOR_TEXT = 100_000

        internal fun editorTextSlice(document: Document, selection: TextRange?, unsaved: Boolean): JsonObject? {
            if (!unsaved) return null
            val selected = selection?.takeUnless { it.isEmpty }
            val start = selected?.startOffset ?: 0
            val end = selected?.endOffset ?: document.textLength
            val text = boundedText(document.immutableCharSequence, start, end)
            if (text.isEmpty()) return null
            return Json.obj(
                "kind" to if (selected != null) "selection" else "dirty-buffer",
                "range" to Json.obj(
                    "startLine" to (document.getLineNumber(start) + 1),
                    "endLine" to if (selected != null) document.getLineNumber(end - 1) + 1 else document.lineCount.coerceAtLeast(1),
                ),
                "text" to text,
                "truncated" to (end - start > MAX_EDITOR_TEXT),
            )
        }

        internal fun boundedText(text: CharSequence, start: Int, end: Int): String =
            text.subSequence(start, minOf(end, start + MAX_EDITOR_TEXT)).toString()
    }
}
