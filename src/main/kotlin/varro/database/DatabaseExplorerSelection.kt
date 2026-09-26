package varro.database

import com.intellij.database.DbModelRegistry
import com.intellij.database.psi.DbElement
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.Project

/** Explorer nodes are BasicElements on newer IDEs, not necessarily PSI data keys. */
internal class DatabaseExplorerSelection(
    private val project: Project,
    private val resolveElement: (BasicElement) -> DbElement? = { project.getService(DbModelRegistry::class.java).findDbElement(it) },
) {
    fun current(): List<DbElement> {
        // Read an existing Explorer without creating or opening its tool window.
        // getDatabaseViewIfVisible is unavailable on the oldest supported IDE branch.
        val view = project.getServiceIfCreated(DatabaseView::class.java) ?: return emptyList()
        val elements = view.selectedElements.asSequence().take(9).mapNotNull(resolveElement).toList()
        if (elements.isNotEmpty()) return valid(elements)
        return valid(view.selectedNodes.asSequence().take(9).mapNotNull { node ->
            (node as? com.intellij.database.view.DataSourceNode)?.let { project.getService(DbModelRegistry::class.java).findDbDataSource(it) }
        }.toList())
    }

    fun from(context: DataContext): List<DbElement> {
        if (CommonDataKeys.PROJECT.getData(context) != project) return emptyList()
        val elements = DatabaseView.DATABASE_ELEMENTS.getData(context)?.asSequence()?.take(9)
            ?.mapNotNull(resolveElement)?.toList().orEmpty()
        if (elements.isNotEmpty()) return valid(elements)
        val psi = DatabaseView.DB_ELEMENTS.getData(context)?.toList()
            ?: LangDataKeys.PSI_ELEMENT_ARRAY.getData(context)?.filterIsInstance<DbElement>()
            ?: listOfNotNull(CommonDataKeys.PSI_ELEMENT.getData(context) as? DbElement)
        if (psi.isNotEmpty()) return valid(psi)
        return valid(DatabaseView.DATABASE_RELATED_DATA_SOURCES.getData(context)?.take(9)
            ?.mapNotNull { project.getService(DbModelRegistry::class.java).findDbDataSource(it) }.orEmpty())
    }

    private fun valid(elements: List<DbElement>) = elements.filter { it.project == project && it.isValid }.take(9)
}
