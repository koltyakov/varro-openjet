package varro.database

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbElement
import com.intellij.database.util.DasUtil
import com.intellij.database.util.DbUtil
import com.intellij.database.vfs.DatabaseElementVirtualFileImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import varro.host.IdeDropObjects
import varro.protocol.Json
import java.security.MessageDigest

/** Database Explorer and @ attachments use introspected schema, independent of the active grid. */
internal class DatabaseTables(private val project: Project) {
    @Volatile private var preference: Pair<String?, String?> = null to null

    fun prefer(source: String?, schema: String?) {
        preference = source to schema
    }
    private fun droppedTables(attached: Any?): List<DbElement> = IdeDropObjects.objects(attached)
        .mapNotNull { value ->
            when (value) {
                is DbElement -> value
                is DatabaseElementVirtualFileImpl -> value.findElement(project)
                else -> null
            }
        }.filter { it.project == project && it.isValid && DbUtil.getDasObject(it) is DasTable }
        .distinctBy(::id).take(20)

    fun canDrop(attached: Any?): Boolean = ApplicationManager.getApplication().runReadAction<Boolean> {
        droppedTables(attached).isNotEmpty()
    }

    fun captureDrop(attached: Any?): List<JsonObject> = ApplicationManager.getApplication().runReadAction<List<JsonObject>> {
        droppedTables(attached).map(::snapshot)
    }

    private fun tables(): Sequence<DbElement> {
        val (preferredSource, preferredSchema) = preference
        return DbUtil.getDataSources(project).asSequence()
            .sortedBy { if (it.uniqueId == preferredSource) 0 else 1 }.flatMap { source ->
            val tables = DasUtil.getTables(source)
            val ordered = if (source.uniqueId == preferredSource && !preferredSchema.isNullOrBlank()) {
                sequenceOf(true, false).flatMap { preferred ->
                    tables.asSequence().filter {
                        ProgressManager.checkCanceled()
                        (DasUtil.getSchema(it) == preferredSchema) == preferred
                    }
                }
            } else tables.asSequence()
            ordered.mapNotNull { table ->
                ProgressManager.checkCanceled()
                source.findElement(table)?.takeIf { it.isValid }
            }
        }
    }

    private fun qualifiedName(element: DbElement): String = listOfNotNull(
        DasUtil.getCatalog(element).takeIf(String::isNotBlank),
        DasUtil.getSchema(element).takeIf(String::isNotBlank),
        element.name,
    ).joinToString(".")

    private fun id(element: DbElement): String {
        val identity = Json.stringify(listOf(element.dataSource.uniqueId, DasUtil.getCatalog(element), DasUtil.getSchema(element), element.name))
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun search(query: String, limit: Int): JsonArray = ApplicationManager.getApplication().runReadAction<JsonArray> {
        val normalized = query.trim().lowercase()
        Json.array(tables().filter { element ->
            qualifiedName(element).lowercase().contains(normalized) || element.dataSource.name.orEmpty().lowercase().contains(normalized)
        }.take(limit.coerceIn(1, 30)).map { element ->
            Json.obj("id" to id(element), "name" to qualifiedName(element).take(1_000), "dataSource" to element.dataSource.name.orEmpty().take(1_000))
        }.toList())
    }

    fun capture(id: String): JsonObject? = ApplicationManager.getApplication().runReadAction<JsonObject?> {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return@runReadAction null
        tables().firstOrNull { id(it) == id }?.let(::snapshot)
    }

    private fun snapshot(element: DbElement): JsonObject {
        val ddl = DatabaseElementVirtualFileImpl.findFile(element, true)?.let {
            FileDocumentManager.getInstance().getCachedDocument(it)
        }
        return DatabaseSnapshot.capture(
            name = qualifiedName(element), dataSource = element.dataSource.name,
            dialect = element.dataSource.queryLanguage.displayName,
            filter = "", columns = DasUtil.getColumns(element).asSequence()
                .take(DatabaseSnapshot.MAX_COLUMNS + 1).map { it.name to it.dasType.specification }.toList(),
            selectedRowCount = 0, rows = emptySequence(),
            pendingChanges = ddl != null && FileDocumentManager.getInstance().isDocumentUnsaved(ddl),
            cellEditing = false, pageStart = 0, ddl = ddl?.text,
        ).apply {
            addProperty("origin", "attachment")
            add("connection", DatabaseDetails.connection(element.dataSource))
            add("target", DatabaseDetails.target(element))
            DatabaseDetails.addObjects(this, listOf(element))
        }
    }
}
