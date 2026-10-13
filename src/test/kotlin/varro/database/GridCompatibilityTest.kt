package varro.database

import com.intellij.database.console.JdbcConsole
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.datagrid.GridDataHookUp
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class GridCompatibilityTest {
    private val modern = "com.intellij.database.grid.impl.DataGridUtil"
    private val legacy = "com.intellij.database.datagrid.DataGridUtil"

    private fun loader(classes: Map<String, Class<*>>) = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> =
            if (name == modern || name == legacy) classes[name] ?: throw ClassNotFoundException(name)
            else super.loadClass(name)
    }

    @Test fun `console lookup resolves the actual target platform API`() {
        val method = GridCompatibility.resolveConsoleLookup(javaClass.classLoader)!!
        assertTrue(method.declaringClass.name in listOf(modern, legacy))
        assertEquals(JdbcConsole::class.java, method.returnType)
    }

    @Test fun `console lookup uses relocated utility even when legacy shim still exists`() {
        val method = GridCompatibility.resolveConsoleLookup(loader(mapOf(
            modern to ConsoleLookup::class.java, legacy to EmptyShim::class.java,
        )))!!
        assertEquals(ConsoleLookup::class.java, method.declaringClass)
    }

    @Test fun `console lookup falls back to legacy package on older IDEs`() {
        val method = GridCompatibility.resolveConsoleLookup(loader(mapOf(legacy to ConsoleLookup::class.java)))!!
        assertEquals(ConsoleLookup::class.java, method.declaringClass)
    }

    @Test fun `console lookup skips a utility shim without the required method`() {
        val method = GridCompatibility.resolveConsoleLookup(loader(mapOf(
            modern to EmptyShim::class.java, legacy to ConsoleLookup::class.java,
        )))!!
        assertEquals(ConsoleLookup::class.java, method.declaringClass)
    }

    @Test fun `missing console lookup leaves grid context available`() {
        assertNull(GridCompatibility.resolveConsoleLookup(loader(mapOf(legacy to EmptyShim::class.java))))
    }

    @Test fun `console lookup preserves a null result`() {
        val method = GridCompatibility.resolveConsoleLookup(loader(mapOf(modern to ConsoleLookup::class.java)))!!
        assertNull(GridCompatibility.invokeConsoleLookup(method, proxy(Project::class.java), proxy(DataGrid::class.java)))
    }

    @Test fun `console lookup does not swallow platform cancellation`() {
        val method = GridCompatibility.resolveConsoleLookup(loader(mapOf(modern to CancelledLookup::class.java)))!!
        try {
            GridCompatibility.invokeConsoleLookup(method, proxy(Project::class.java), proxy(DataGrid::class.java))
            fail("Expected cancellation")
        } catch (_: ProcessCanceledException) {
            // Reflection must not turn cancellation into a capture failure.
        }
    }

    @Test fun `grid search path uses the stable owner interface rather than relocated session client`() {
        val path = SearchPath.of(ObjectPath.ROOT)
        val hookUp = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(
            GridDataHookUp::class.java, DataRequest.CustomSearchPathOwner::class.java,
        )) { _, method, _ ->
            if (method.name == "getSearchPath") path else error("Unexpected hookup access: ${method.name}")
        } as GridDataHookUp<*, *>
        assertSame(path, GridCompatibility.searchPath(hookUp))
        assertNull(GridCompatibility.searchPath(proxy(GridDataHookUp::class.java)))
        assertNull(GridCompatibility.searchPath(null))
    }

    private fun <T> proxy(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, _ ->
            error("Unexpected access: ${method.name}")
        },
    )

    class EmptyShim

    class ConsoleLookup {
        companion object {
            @JvmStatic fun findGridRelatedConsole(project: Project, grid: DataGrid): JdbcConsole? = null
        }
    }

    class CancelledLookup {
        companion object {
            @JvmStatic fun findGridRelatedConsole(project: Project, grid: DataGrid): JdbcConsole? =
                throw ProcessCanceledException()
        }
    }
}
