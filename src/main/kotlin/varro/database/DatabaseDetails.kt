package varro.database

import com.google.gson.JsonObject
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.util.SearchPath
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbElement
import com.intellij.database.util.DasUtil
import com.intellij.database.util.DbUtil
import varro.protocol.Json

/** Only IDE metadata is read here. Connection URLs and credentials are never serialized. */
internal object DatabaseDetails {
    fun qualifiedName(element: DbElement): String =
        generateSequence(element) { it.parent?.takeUnless { parent -> parent is DbDataSource } }
            .map { it.name }.toList().asReversed().filter(String::isNotBlank).joinToString(".")

    fun connection(source: DbDataSource?, console: JdbcConsole? = null): JsonObject? =
        connection(source, console?.session, console?.searchPath)

    fun connection(source: DbDataSource?, session: DatabaseSession?, searchPath: SearchPath?): JsonObject? {
        if (source == null && session == null) return null
        val current = searchPath?.current
        return Json.obj(
            "dataSourceId" to (source?.uniqueId ?: session?.connectionPoint?.dataSource?.uniqueId)?.take(1_000),
            "catalog" to current?.findParent(ObjectKind.DATABASE, false)?.name?.take(1_000),
            "schema" to current?.findParent(ObjectKind.SCHEMA, false)?.name?.take(1_000),
            "searchPath" to searchPath?.elements?.take(16)?.map { it.displayName.take(1_000) }.orEmpty(),
            "connected" to session?.isConnected,
        ).apply {
            session?.connectionPoint?.let {
                addProperty("readOnly", it.isReadOnly)
                addProperty("autoCommit", it.isAutoCommit)
            }
        }
    }

    fun target(element: DbElement): JsonObject = Json.obj(
        "name" to qualifiedName(element).take(1_000),
        "kind" to element.kind.name().lowercase().take(256),
        "catalog" to DasUtil.getCatalog(element).take(1_000),
        "schema" to DasUtil.getSchema(element).take(1_000),
        "dataSourceId" to element.dataSource.uniqueId.take(1_000),
    )

    fun objectDetails(element: DbElement): JsonObject {
        val result = target(element)
        var truncated = false
        fun text(value: String?, max: Int = 1_000): String? {
            if ((value?.length ?: 0) > max) truncated = true
            return value?.take(max)
        }
        fun names(values: Iterable<String>): List<String> {
            val list = values.take(65)
            if (list.size > 64) truncated = true
            return list.take(64).map { text(it, 256)!! }
        }
        result.addProperty("comment", text(element.comment))
        val table = DbUtil.getDasObject(element) as? DasTable
        if (table != null) {
            val columns = DasUtil.getColumns(table).asSequence().take(65).toList()
            if (columns.size > 64) truncated = true
            result.add("columns", Json.array(columns.take(64).map { column ->
                Json.obj(
                    "name" to text(column.name, 256), "type" to text(column.dasType.specification, 256),
                    "nullable" to !column.isNotNull, "primaryKey" to DasUtil.isPrimary(column),
                    "default" to text(column.default), "comment" to text(column.comment),
                )
            }))
            result.add("primaryKey", Json.array(DasUtil.getPrimaryKey(table)?.columnsRef?.names()?.let(::names).orEmpty()))
            val keys = DasUtil.getForeignKeys(table).asSequence().take(17).toList()
            if (keys.size > 16) truncated = true
            result.add("foreignKeys", Json.array(keys.take(16).map { key ->
                Json.obj(
                    "name" to text(key.name, 256), "columns" to names(key.columnsRef.names()),
                    "referencedTable" to text(listOfNotNull(key.refTableCatalog, key.refTableSchema, key.refTableName)
                        .filter(String::isNotBlank).joinToString(".")),
                    "referencedColumns" to names(key.refColumns.names()),
                )
            }))
            val indices = DasUtil.getIndices(table).asSequence().take(17).toList()
            if (indices.size > 16) truncated = true
            result.add("indexes", Json.array(indices.take(16).map { index ->
                Json.obj("name" to text(index.name, 256), "unique" to index.isUnique, "columns" to names(index.columnsRef.names()))
            }))
        }
        result.addProperty("truncated", truncated)
        return result
    }

    /** Bound the total metadata too, since comments and wide tables can dominate context. */
    fun addObjects(snapshot: JsonObject, elements: List<DbElement>) {
        var remaining = 60_000
        val objects = mutableListOf<JsonObject>()
        if (elements.size > 8) snapshot.addProperty("truncated", true)
        val selected = elements.take(8)
        for ((index, element) in selected.withIndex()) {
            val detail = fitObject(objectDetails(element), remaining / (selected.size - index))
            val size = detail.toString().length
            remaining -= size
            objects.add(detail)
            if (detail["truncated"].asBoolean) snapshot.addProperty("truncated", true)
        }
        snapshot.add("objects", Json.array(objects))
    }

    /** Keep identity and some columns even when a wide table exceeds the budget. */
    internal fun fitObject(detail: JsonObject, budget: Int): JsonObject {
        if (detail.toString().length <= budget) return detail
        detail.addProperty("truncated", true)
        detail.addProperty("comment", null as String?)
        detail.getAsJsonArray("columns")?.forEach { column ->
            column.asJsonObject.addProperty("comment", null as String?)
            column.asJsonObject.addProperty("default", null as String?)
        }
        for (name in listOf("indexes", "foreignKeys", "columns", "primaryKey")) {
            val values = detail.getAsJsonArray(name) ?: continue
            while (!values.isEmpty && detail.toString().length > budget) values.remove(values.size() - 1)
        }
        return detail
    }
}
