package varro.database

import com.google.gson.JsonObject
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.util.DasUtil
import com.intellij.database.util.DbUtil
import com.intellij.openapi.project.Project
import varro.protocol.Json
import varro.protocol.str

/** Project-level context exists even when no table or SQL editor is open. */
internal object DatabaseEnvironment {
    data class Source(
        val id: String,
        val name: String,
        val dialect: String?,
        val connected: Boolean,
        val catalogs: List<String> = emptyList(),
        val schemas: List<String> = emptyList(),
    )

    fun capture(project: Project, context: JsonObject?, explorerSourceIds: List<String>): JsonObject? {
        val sessions = DatabaseSessionManager.getSessions(project)
        val connected = sessions.filter { it.isConnected }.map { it.connectionPoint.dataSource.uniqueId }.toSet()
        val sources = DbUtil.getDataSources(project).map { source ->
            val schemas = DasUtil.getSchemas(source).asSequence().take(17).toList()
            Source(source.uniqueId, source.name, source.queryLanguage.displayName, source.uniqueId in connected,
                schemas.map { DasUtil.getCatalog(it) }.filter(String::isNotBlank).distinct(),
                schemas.map { schema -> listOf(DasUtil.getCatalog(schema), schema.name)
                    .filter(String::isNotBlank).joinToString(".") })
        }.toList()
        val activeId = context?.get("connection")?.takeIf { it.isJsonObject }?.asJsonObject?.str("dataSourceId")
        // An explicit multi-object selection must not collapse to whichever source is connected.
        val selectedIds = context?.getAsJsonArray("selectedObjects")?.mapNotNull { it.asJsonObject.str("dataSourceId") }
        val preferred = activeId?.let(::listOf) ?: selectedIds ?: explorerSourceIds
        return snapshot(sources, preferred, context?.str("origin") ?: "explorer")
    }

    internal fun snapshot(sources: List<Source>, preferredIds: List<String>, origin: String): JsonObject? {
        if (sources.isEmpty()) return null
        val preferred = sources.filter { it.id in preferredIds }
        val connected = sources.filter { it.connected }
        val selected = when {
            preferred.isNotEmpty() -> preferred.singleOrNull()
            sources.size == 1 -> sources.single()
            connected.size == 1 -> connected.single()
            else -> null
        }
        val reason = when {
            preferred.size > 1 -> "multiple-selected"
            preferred.size == 1 -> origin
            sources.size == 1 -> "only-datasource"
            connected.size == 1 -> "only-connected-datasource"
            else -> "none"
        }
        var truncated = sources.size > 20
        fun bounded(value: String, max: Int): String {
            if (value.length > max) truncated = true
            return value.take(max)
        }
        fun namespaces(values: List<String>): List<String> {
            if (values.size > 16) truncated = true
            return values.take(16).map { bounded(it, 256) }
        }
        var remaining = 40_000
        val inventory = sources.sortedBy { if (it.id == selected?.id) 0 else 1 }.take(20).map { source ->
            Json.obj(
                "id" to bounded(source.id, 1_000), "name" to bounded(source.name, 1_000),
                "dialect" to source.dialect?.let { bounded(it, 1_000) }, "connected" to source.connected,
                "catalogs" to namespaces(source.catalogs), "schemas" to namespaces(source.schemas),
            )
        }.takeWhile { item ->
            remaining -= item.toString().length
            (remaining >= 0).also { if (!it) truncated = true }
        }
        return DatabaseSnapshot.capture(
            name = selected?.name ?: "${sources.size} datasources", dataSource = selected?.name, dialect = selected?.dialect,
            filter = "", columns = emptyList(), selectedRowCount = 0, rows = emptySequence(),
            pendingChanges = false, cellEditing = false, pageStart = 0,
        ).apply {
            addProperty("scope", "datasource")
            addProperty("origin", "workspace")
            addProperty("activeDataSourceId", selected?.id?.take(1_000))
            addProperty("activeDataSourceReason", reason)
            addProperty("dataSourceCount", sources.size)
            add("dataSources", Json.array(inventory))
            addProperty("truncated", truncated)
            add("connection", selected?.let { Json.obj(
                "dataSourceId" to it.id.take(1_000), "catalog" to null, "schema" to null,
                "searchPath" to emptyList<String>(), "connected" to it.connected,
            ) })
        }
    }
}
