package varro.database

import com.google.gson.JsonObject
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.DatabaseDataKeys
import com.intellij.database.datagrid.DataGridListener
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.GridUtil
import com.intellij.database.editor.TableEditorBase
import com.intellij.database.psi.DbElement
import com.intellij.database.run.ui.DataAccessType
import com.intellij.database.vfs.DatabaseElementVirtualFileImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiAwareObject
import varro.host.DatabaseContextSource
import java.lang.ref.WeakReference
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener

/** Reads loaded database grids and DDL documents. Never loads pages or executes SQL. */
class DatabaseContextProvider(private val project: Project) : DatabaseContextSource, Disposable {
    private val listeners = mutableListOf<() -> Unit>()
    private var active: WeakReference<DataGrid>? = null
    private var activeDdlEditor: WeakReference<Editor>? = null
    private var gridSubscription: Disposable? = null
    private var gridDisposalListener: Disposable? = null

    init {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusListener = PropertyChangeListener { event ->
            val component = event.newValue as? Component ?: return@PropertyChangeListener
            val context = DataManager.getInstance().getDataContext(component)
            val grid = DatabaseDataKeys.DATA_GRID_KEY.getData(context)
            if (grid?.project == project) activate(grid)
            else {
                val editor = CommonDataKeys.EDITOR.getData(context)
                if (editor?.project == project) activateEditor(editor)
            }
        }
        focusManager.addPropertyChangeListener("focusOwner", focusListener)
        Disposer.register(this, Disposable { focusManager.removePropertyChangeListener("focusOwner", focusListener) })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            DataGrid.ACTIVE_GRID_CHANGED_TOPIC,
            object : DataGrid.ActiveGridListener {
                override fun changed(grid: DataGrid) {
                    if (grid.project == project) activate(grid)
                }
                // closed() has no project or grid identity. Disposal and editor events
                // below clear the actual tracked grid rather than another project's grid.
            },
        )
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val editor = event.newEditor
                    if (editor is TableEditorBase) activate(editor.dataGrid)
                    else if (editor is TextEditor) activateEditor(editor.editor)
                    else if (editor == null) activateEditor(null)
                    // Chat tabs preserve the last source, like text editor context.
                }
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val editor = activeDdlEditor?.get() ?: return
                    if (FileDocumentManager.getInstance().getFile(editor.document) == file) activateEditor(null)
                }
            },
        )
    }

    override fun addListener(listener: () -> Unit) { listeners.add(listener) }

    private fun activate(grid: DataGrid?) {
        if (grid != null) activeDdlEditor = null
        if (active?.get() === grid) return
        active = grid?.let(::WeakReference)
        gridSubscription?.let(Disposer::dispose)
        gridDisposalListener?.let(Disposer::dispose)
        gridSubscription = null
        gridDisposalListener = null
        if (grid != null) {
            val subscription = Disposer.newDisposable("Varro database context")
            gridSubscription = subscription
            Disposer.register(this, subscription)
            grid.addDataGridListener(object : DataGridListener {
                override fun onSelectionChanged(dataGrid: DataGrid) = changed()
                override fun onContentChanged(dataGrid: DataGrid, place: GridRequestSource.RequestPlace?) = changed()
                override fun onValueEdited(dataGrid: DataGrid, value: Any?) = changed()
            }, subscription)
            val disposalListener = Disposable {
                if (active?.get() === grid) activate(null)
            }
            gridDisposalListener = disposalListener
            Disposer.register(grid, disposalListener)
        }
        changed()
    }

    private fun changed() = listeners.forEach { it() }

    private fun ddlFile(editor: Editor?): DatabaseElementVirtualFileImpl? {
        if (editor == null || editor.isDisposed || editor.project != project) return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) as? DatabaseElementVirtualFileImpl
        return file?.takeIf { it.isValid && it.isSource }
    }

    private fun activateEditor(editor: Editor?) {
        activate(null)
        activeDdlEditor = editor?.takeIf { ddlFile(it) != null }?.let(::WeakReference)
        changed()
    }

    override fun isAvailable(context: DataContext): Boolean =
        ddlFile(CommonDataKeys.EDITOR.getData(context)) != null ||
            GridUtil.getDataGrid(context)?.let { it.project == project && GridCompatibility.helper(it).isDatabaseHookUp(it) } == true

    override fun capture(context: DataContext): JsonObject? {
        val editor = CommonDataKeys.EDITOR.getData(context)
        if (ddlFile(editor) != null) {
            activateEditor(editor)
            return readDdl(editor!!)
        }
        val grid = GridUtil.getDataGrid(context) ?: return null
        if (grid.project != project || !GridCompatibility.helper(grid).isDatabaseHookUp(grid)) return null
        activate(grid)
        return read(grid)
    }

    override fun snapshot(): JsonObject? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val ddlEditor = activeDdlEditor?.get()
            ?: (FileEditorManager.getInstance(project).selectedEditor as? TextEditor)?.editor?.takeIf { active?.get() == null }
        if (ddlFile(ddlEditor) != null) return readDdl(ddlEditor!!)
        val grid = active?.get() ?: (FileEditorManager.getInstance(project).selectedEditor as? TableEditorBase)?.dataGrid
        if (grid == null) return null
        if (!GridCompatibility.helper(grid).isDatabaseHookUp(grid)) return null
        if (active?.get() !== grid) activate(grid)
        return read(grid)
    }

    private fun readDdl(editor: Editor): JsonObject? {
        val file = ddlFile(editor) ?: return null
        return ApplicationManager.getApplication().runReadAction<JsonObject> {
            val element = file.findElement(project)
            val dataSource = element?.dataSource ?: file.findDataSource(project)
            DatabaseSnapshot.capture(
                name = element?.name ?: file.nameWithoutExtension,
                dataSource = dataSource?.name,
                dialect = dataSource?.queryLanguage?.displayName,
                filter = "", columns = emptyList(), selectedRowCount = 0, rows = emptySequence(),
                pendingChanges = FileDocumentManager.getInstance().isDocumentUnsaved(editor.document),
                cellEditing = false, pageStart = 0,
                ddl = editor.document.text, ddlEditor = true,
            )
        }
    }

    private fun loadedDdl(element: DbElement?) = element?.let {
        DatabaseElementVirtualFileImpl.findFile(it, true)
    }?.let { FileDocumentManager.getInstance().getCachedDocument(it) }

    private fun read(grid: DataGrid): JsonObject? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!grid.isReady) return null
        return try {
            ApplicationManager.getApplication().runReadAction<JsonObject> {
                val helper = GridCompatibility.helper(grid)
                val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
                val columns = grid.visibleColumns.asList().filter { model.isValidColumnIdx(it) }
                    .sortedBy { it.toView(grid).asInteger() }
                val selected = grid.selectionModel.selectedRows.asList()
                val rows = selected.asSequence().filter { model.isValidRowIdx(it) }
                    .sortedBy { it.toView(grid).asInteger() }
                val element = (helper.getVirtualFile(grid) as? PsiAwareObject)?.findElement(project) as? DbElement
                val ddl = loadedDdl(element)
                DatabaseSnapshot.capture(
                    name = helper.getTableName(grid)?.takeIf { it.isNotBlank() } ?: grid.displayName,
                    dataSource = element?.dataSource?.name,
                    dialect = helper.getDatabaseSystemName(grid),
                    filter = grid.filterText,
                    columns = columns.map { index -> model.getColumn(index).let { (it?.name ?: "") to it?.typeName.orEmpty() } },
                    selectedRowCount = selected.size,
                    rows = rows.map { row -> columns.take(DatabaseSnapshot.MAX_COLUMNS).map { model.getValueAt(row, it) } },
                    pendingChanges = grid.dataSupport.hasPendingChanges() ||
                        (ddl != null && FileDocumentManager.getInstance().isDocumentUnsaved(ddl)),
                    cellEditing = grid.isEditing,
                    pageStart = grid.dataHookup.pageModel.pageStart,
                    ddl = ddl?.text,
                )
            }
        } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.getInstance(DatabaseContextProvider::class.java).warn("Could not capture database grid context", failure)
            null
        }
    }

    override fun dispose() {
        active = null
        activeDdlEditor = null
        gridDisposalListener?.let(Disposer::dispose)
        listeners.clear()
    }
}
