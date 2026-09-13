package varro.database

import com.intellij.database.datagrid.CoreGrid
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridHelper

internal object GridCompatibility {
    // GridHelper became Kotlin in 261. A direct Kotlin call binds to Companion,
    // which does not exist on 252/253; the public static JVM method exists on both.
    private val getHelper = GridHelper::class.java.getMethod("get", CoreGrid::class.java)

    fun helper(grid: DataGrid): GridHelper = getHelper.invoke(null, grid) as GridHelper
}
