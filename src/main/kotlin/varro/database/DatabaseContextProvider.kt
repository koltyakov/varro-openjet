package varro.database

import com.google.gson.JsonObject
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.DatabaseDataKeys
import com.intellij.database.datagrid.DataGridListener
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.GridUtil
import com.intellij.database.datagrid.DataGridUtil
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.VirtualFileDataSourceProvider
import com.intellij.database.util.DbUtil
import com.intellij.database.util.DasUtil
import varro.protocol.Json
import com.intellij.database.model.DasTable
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
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeSelectionListener
import com.intellij.util.Alarm

/** Tracks the last database surface the user worked in, including while chat has focus. */
class DatabaseContextProvider(private val project: Project) : DatabaseContextSource, Disposable {
    private val tables = DatabaseTables(project)
    private val explorerSelection = DatabaseExplorerSelection(project)
    private val listeners = mutableListOf<() -> Unit>()
    private var active: WeakReference<DataGrid>? = null
    private var activeEditor: WeakReference<Editor>? = null
    private var explorer: List<WeakReference<DbElement>> = emptyList()
    private var initialized = false
    private var focusTree: JTree? = null
    private val treeListener = TreeSelectionListener {
        ApplicationManager.getApplication().invokeLater {
            val tree = focusTree ?: return@invokeLater
            val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@invokeLater
            if (!project.isDisposed && (focus === tree || SwingUtilities.isDescendingFrom(focus, tree))) followFocus(focus)
        }
    }
    private val connectionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastConnection: String? = null
    private var gridSubscription: Disposable? = null
    private var gridDisposalListener: Disposable? = null

    init {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusListener = PropertyChangeListener { event ->
            val component = event.newValue as? Component ?: return@PropertyChangeListener
            followFocus(component)
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
                    val editor = activeEditor?.get() ?: return
                    if (FileDocumentManager.getInstance().getFile(editor.document) == file) activateEditor(null)
                }
            },
        )
        watchConnection()
    }

    private fun followFocus(component: Component) {
        val tree = (component as? JTree) ?: SwingUtilities.getAncestorOfClass(JTree::class.java, component) as? JTree
        if (tree !== focusTree) {
            focusTree?.removeTreeSelectionListener(treeListener)
            focusTree = tree
            tree?.addTreeSelectionListener(treeListener)
        }
        val context = DataManager.getInstance().getDataContext(component)
        // Explorer data contexts can also expose the selected editor. The focused
        // tree is the user's explicit target and must take precedence over that editor.
        if (com.intellij.database.view.DatabaseView.DATABASE_TREE_IS_ORIGIN.getData(context) == true) {
            val elements = selectedObjects(context)
            if (elements.isNotEmpty()) { activateExplorer(elements); return }
        }
        val grid = DatabaseDataKeys.DATA_GRID_KEY.getData(context)
        if (grid?.project == project && GridCompatibility.helper(grid).isDatabaseHookUp(grid)) activate(grid)
        else {
            val editor = CommonDataKeys.EDITOR.getData(context)
            if (editor?.project == project) activateEditor(editor)
            else {
                val elements = selectedObjects(context)
                if (elements.isNotEmpty()) activateExplorer(elements)
            }
        }
    }

    private fun selectedObjects(context: DataContext): List<DbElement> = explorerSelection.from(context)

    private fun activateExplorer(elements: List<DbElement>) {
        activate(null)
        activeEditor = null
        explorer = elements.map(::WeakReference)
        initialized = true
        changed()
    }

    // Schema and connection selectors can change without a caret or grid event. Poll only
    // their small state, not rows or metadata, and let the host coalesce actual changes.
    private fun watchConnection() {
        connectionAlarm.addRequest({
            if (!project.isDisposed) {
                val editor = activeEditor?.get()?.takeUnless { it.isDisposed }
                val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
                val console = file?.let { JdbcConsoleProvider.getValidConsole(project, it) }
                    ?: active?.get()?.let { DataGridUtil.findGridRelatedConsole(project, it) }
                val gridHookUp = active?.get()?.let { DataGridUtil.getDatabaseHookUp(it) }
                val session = gridHookUp?.session ?: console?.session
                val source = session?.connectionPoint?.dataSource
                val state = session?.let {
                    "${source?.uniqueId}:${source?.name}:${console?.searchPath}:${it.isConnected}:${it.connectionPoint.isReadOnly}:${it.connectionPoint.isAutoCommit}"
                }
                val selection = explorerSelection.current()
                val environment = DatabaseEnvironment.capture(project, null, selection.map { it.dataSource.uniqueId })
                val fingerprint = "$state:$environment:${selection.map { DatabaseDetails.target(it) }}"
                if (fingerprint != lastConnection) { lastConnection = fingerprint; changed() }
                watchConnection()
            }
        }, 1_000)
    }

    override fun addListener(listener: () -> Unit) { listeners.add(listener) }

    override fun canDrop(attached: Any?) = tables.canDrop(attached)
    override fun captureDrop(attached: Any?) = tables.captureDrop(attached)
    override fun searchTables(query: String, limit: Int) = tables.search(query, limit)
    override fun captureTable(id: String) = tables.capture(id)

    override fun environment(activeContext: JsonObject?): JsonObject? = DatabaseEnvironment.capture(
        project, activeContext, explorerSelection.current().map { it.dataSource.uniqueId },
    )

    private fun activate(grid: DataGrid?) {
        initialized = true
        if (grid != null) {
            activeEditor = null
            explorer = emptyList()
        }
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
        explorer = emptyList()
        activeEditor = editor?.takeIf { !it.isDisposed && it.project == project }?.let(::WeakReference)
        if (editor == null || !databaseEditor(editor)) tables.prefer(null, null)
        changed()
    }

    override fun isAvailable(context: DataContext): Boolean =
        databaseEditor(CommonDataKeys.EDITOR.getData(context)) || selectedObjects(context).isNotEmpty() ||
            GridUtil.getDataGrid(context)?.let { it.project == project && GridCompatibility.helper(it).isDatabaseHookUp(it) } == true

    override fun capture(context: DataContext): JsonObject? {
        val editor = CommonDataKeys.EDITOR.getData(context)
        if (databaseEditor(editor)) {
            activateEditor(editor)
            return readEditor(editor!!)
        }
        val elements = selectedObjects(context)
        if (GridUtil.getDataGrid(context) == null && elements.isNotEmpty()) {
            activateExplorer(elements)
            return readExplorer(elements)
        }
        val grid = GridUtil.getDataGrid(context) ?: return null
        if (grid.project != project || !GridCompatibility.helper(grid).isDatabaseHookUp(grid)) return null
        activate(grid)
        return read(grid)
    }

    override fun snapshot(): JsonObject? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!initialized) {
            when (val selected = FileEditorManager.getInstance(project).selectedEditor) {
                is TableEditorBase -> activate(selected.dataGrid)
                is TextEditor -> activateEditor(selected.editor)
            }
        }
        val elements = if (explorer.isNotEmpty()) explorerSelection.current().ifEmpty {
            explorer.mapNotNull { it.get()?.takeIf { element -> element.isValid } }
        } else emptyList()
        if (elements.isNotEmpty()) return readExplorer(elements)
        activeEditor?.get()?.takeUnless { it.isDisposed }?.let { return readEditor(it) }
        val grid = active?.get()
        if (grid == null) {
            val selected = explorerSelection.current()
            if (selected.isEmpty()) return null
            activateExplorer(selected)
            return readExplorer(selected)
        }
        if (!GridCompatibility.helper(grid).isDatabaseHookUp(grid)) return null
        return read(grid)
    }

    private fun databaseEditor(editor: Editor?): Boolean {
        if (editor == null || editor.isDisposed || editor.project != project) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return ddlFile(editor) != null || JdbcConsoleProvider.getValidConsole(project, file) != null ||
            VirtualFileDataSourceProvider.findDataSource(project, file) != null
    }

    private fun readEditor(editor: Editor): JsonObject? {
        if (ddlFile(editor) != null) return readDdl(editor)
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val console = JdbcConsoleProvider.getValidConsole(project, file)
        val source = console?.dataSource?.uniqueId?.let { DbPsiFacade.getInstance(project).findDataSource(it) }
            ?: VirtualFileDataSourceProvider.findDataSource(project, file)
        if (console == null && source == null) return null
        val capture = DatabaseSqlContext.capture(editor)
        return DatabaseSnapshot.capture(
            name = console?.title ?: file.name, dataSource = source?.name ?: console?.dataSource?.name,
            dialect = source?.queryLanguage?.displayName ?: console?.language?.displayName,
            filter = "", columns = emptyList(), selectedRowCount = 0, rows = emptySequence(),
            pendingChanges = FileDocumentManager.getInstance().isDocumentUnsaved(editor.document),
            cellEditing = false, pageStart = 0,
        ).apply {
            addProperty("scope", "console")
            addProperty("origin", "console")
            add("connection", DatabaseDetails.connection(source, console))
            add("sql", capture.sql)
            if (capture.sql["truncated"].asBoolean || capture.metadataTruncated) addProperty("truncated", true)
            (capture.focused ?: capture.objects.singleOrNull())?.let { add("target", DatabaseDetails.target(it)) }
            DatabaseDetails.addObjects(this, capture.objects)
            tables.prefer(source?.uniqueId, getAsJsonObject("connection")?.get("schema")?.takeUnless { it.isJsonNull }?.asString)
        }
    }

    private fun readExplorer(elements: List<DbElement>): JsonObject {
        val source = elements.map { it.dataSource }.distinctBy { it.uniqueId }.singleOrNull()
        val single = elements.singleOrNull()
        val metadata = elements.map { element ->
            generateSequence(element) { it.parent }.firstOrNull { DbUtil.getDasObject(it) is DasTable } ?: element
        }.distinctBy { "${it.dataSource.uniqueId}:${DatabaseDetails.qualifiedName(it)}" }
        return DatabaseSnapshot.capture(
            name = single?.let(DatabaseDetails::qualifiedName) ?: "${elements.size} database objects",
            dataSource = source?.name, dialect = source?.queryLanguage?.displayName,
            filter = "", columns = emptyList(), selectedRowCount = 0, rows = emptySequence(),
            pendingChanges = false, cellEditing = false, pageStart = 0,
        ).apply {
            addProperty("scope", "object")
            addProperty("origin", "explorer")
            add("connection", DatabaseDetails.connection(source))
            single?.let { add("target", DatabaseDetails.target(it)) }
            add("selectedObjects", Json.array(elements.take(8).map(DatabaseDetails::target)))
            if (elements.size > 8) addProperty("truncated", true)
            DatabaseDetails.addObjects(this, metadata)
            tables.prefer(source?.uniqueId, single?.let { DasUtil.getSchema(it) })
        }
    }

    private fun readDdl(editor: Editor): JsonObject? {
        val file = ddlFile(editor) ?: return null
        return ApplicationManager.getApplication().runReadAction<JsonObject> {
            val element = file.findElement(project)
            val dataSource = element?.dataSource ?: file.findDataSource(project)
            DatabaseSnapshot.capture(
                name = element?.let(DatabaseDetails::qualifiedName) ?: file.nameWithoutExtension,
                dataSource = dataSource?.name,
                dialect = dataSource?.queryLanguage?.displayName,
                filter = "", columns = emptyList(), selectedRowCount = 0, rows = emptySequence(),
                pendingChanges = FileDocumentManager.getInstance().isDocumentUnsaved(editor.document),
                cellEditing = false, pageStart = 0,
                ddl = editor.document.text, ddlEditor = true,
            ).apply {
                addProperty("origin", "ddl")
                add("connection", DatabaseDetails.connection(dataSource))
                element?.let {
                    add("target", DatabaseDetails.target(it))
                    DatabaseDetails.addObjects(this, listOf(it))
                }
                tables.prefer(dataSource?.uniqueId, element?.let { DasUtil.getSchema(it) })
            }
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
                val console = DataGridUtil.findGridRelatedConsole(project, grid)
                val hookUp = DataGridUtil.getDatabaseHookUp(grid)
                val element = (helper.getVirtualFile(grid) as? PsiAwareObject)?.findElement(project) as? DbElement
                    ?: DataGridUtil.getDatabaseTable(grid)?.let { hookUp?.dataSource?.findElement(it) }
                val source = element?.dataSource
                    ?: hookUp?.dataSource
                    ?: console?.dataSource?.uniqueId?.let { DbPsiFacade.getInstance(project).findDataSource(it) }
                val session = hookUp?.session ?: console?.session
                val searchPath = DataGridUtil.getDataGridClient(grid)?.searchPath
                    ?: console?.takeIf { it.session === session }?.searchPath
                val related = if (element != null) listOf(element) else columns.asSequence()
                    .take(DatabaseSnapshot.MAX_COLUMNS).mapNotNull { index ->
                        DataGridUtil.getDatabaseColumn(grid, index)?.let { column ->
                            DasUtil.getParentOfClass(column, DasTable::class.java, true)?.let { source?.findElement(it) }
                        }
                    }.distinctBy(DatabaseDetails::qualifiedName).take(9).toList()
                val ddl = loadedDdl(element)
                DatabaseSnapshot.capture(
                    name = element?.let(DatabaseDetails::qualifiedName) ?: helper.getTableName(grid)?.takeIf { it.isNotBlank() } ?: grid.displayName,
                    dataSource = source?.name ?: console?.dataSource?.name,
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
                ).apply {
                    addProperty("origin", "grid")
                    add("connection", DatabaseDetails.connection(source, session, searchPath))
                    element?.let {
                        add("target", DatabaseDetails.target(it))
                    }
                    DatabaseDetails.addObjects(this, related)
                    helper.getQueryText(grid)?.takeIf(String::isNotBlank)?.let {
                        add("sql", DatabaseSqlContext.sql(it, "executed"))
                        if (it.length > DatabaseSqlContext.MAX_SQL) addProperty("truncated", true)
                    }
                    add("selectedColumns", Json.array(grid.selectionModel.selectedColumns.asList()
                        .filter { model.isValidColumnIdx(it) }.take(DatabaseSnapshot.MAX_COLUMNS)
                        .map { model.getColumn(it)?.name.orEmpty().take(256) }))
                    tables.prefer(source?.uniqueId, element?.let { DasUtil.getSchema(it) })
                }
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
        activeEditor = null
        explorer = emptyList()
        focusTree?.removeTreeSelectionListener(treeListener)
        focusTree = null
        gridDisposalListener?.let(Disposer::dispose)
        listeners.clear()
    }
}
