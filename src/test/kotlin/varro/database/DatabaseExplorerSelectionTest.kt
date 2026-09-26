package varro.database

import com.intellij.database.model.basic.BasicElement
import com.intellij.database.psi.DbElement
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseExplorerSelectionTest {
    private inline fun <reified T> proxy(crossinline value: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            if (method.name == "equals") self === args?.get(0) else value(method.name)
        } as T

    @Test fun `Explorer BasicElements work without generic PSI keys and stay project scoped`() {
        val project = proxy<Project> { error("Unexpected project access: $it") }
        val node = proxy<BasicElement> { error("Unexpected node access: $it") }
        val objectInProject = proxy<DbElement> {
            when (it) { "getProject" -> project; "isValid" -> true; else -> error("Unexpected PSI access: $it") }
        }
        val context = DataContext { id -> when (id) {
            CommonDataKeys.PROJECT.name -> project
            DatabaseView.DATABASE_ELEMENTS.name -> arrayOf(node)
            else -> null
        } }
        val selection = DatabaseExplorerSelection(project) { assertSame(node, it); objectInProject }
        assertSame(objectInProject, selection.from(context).single())
        assertTrue(selection.from(DataContext { null }).isEmpty())
    }
}
