package varro.database

import com.intellij.database.datagrid.CoreGrid
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.datagrid.DatabaseGridDataHookUp
import com.intellij.database.datagrid.GridDataHookUp
import com.intellij.database.datagrid.GridHelper
import com.intellij.database.datagrid.GridUtil
import com.intellij.database.console.JdbcConsole
import com.intellij.database.util.SearchPath
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object GridCompatibility {
    // GridHelper became Kotlin in 261. A direct Kotlin call binds to Companion,
    // which does not exist on 252/253; the public static JVM method exists on both.
    private val getHelper = GridHelper::class.java.getMethod("get", CoreGrid::class.java)

    fun helper(grid: DataGrid): GridHelper = getHelper.invoke(null, grid) as GridHelper

    // In 263.6800 DataGridUtil moved to database.grid.impl. The old class remains
    // as a shim, but no longer exposes console lookup. Keep only that lookup
    // reflective; hookup, column and search-path access use unchanged APIs.
    private data class ConsoleLookup(val method: Method?)
    private val consoleLookups = ConcurrentHashMap<Class<*>, ConsoleLookup>()

    internal fun resolveConsoleLookup(loader: ClassLoader): Method? {
        for (name in listOf(
            "com.intellij.database.grid.impl.DataGridUtil",
            "com.intellij.database.datagrid.DataGridUtil",
        )) {
            try {
                return loader.loadClass(name).getMethod("findGridRelatedConsole", Project::class.java, DataGrid::class.java)
            } catch (_: ClassNotFoundException) {
                // Try the other package on older IDEs.
            } catch (_: NoSuchMethodException) {
                // A compatibility shim may exist without this method.
            }
        }
        return null
    }

    fun relatedConsole(project: Project, grid: DataGrid): JdbcConsole? {
        // DbGridHelper belongs to the database grid implementation module. Use
        // its loader because the relocated utility is not exported to plugins.
        val helperClass = helper(grid).javaClass
        val lookup = consoleLookups.computeIfAbsent(helperClass) {
            ConsoleLookup(resolveConsoleLookup(it.classLoader))
        }
        return lookup.method?.let { invokeConsoleLookup(it, project, grid) }
    }

    internal fun invokeConsoleLookup(method: Method, project: Project, grid: DataGrid): JdbcConsole? = try {
        method.invoke(null, project, grid) as JdbcConsole?
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }

    fun databaseHookUp(grid: DataGrid): DatabaseGridDataHookUp? =
        GridUtil.getHookUp(grid, DatabaseGridDataHookUp::class.java)

    fun searchPath(hookUp: GridDataHookUp<*, *>?): SearchPath? =
        (hookUp as? DataRequest.CustomSearchPathOwner)?.searchPath
}
