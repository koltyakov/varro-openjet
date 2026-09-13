package varro.database

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import varro.protocol.Json

/** Bounded, detached values. Column arrays preserve duplicate SQL result labels. */
internal object DatabaseSnapshot {
    const val MAX_COLUMNS = 64
    private const val MAX_ROWS = 200
    private const val MAX_CELL = 4_000
    private const val MAX_ROW_TEXT = 80_000
    const val MAX_DDL = 40_000

    fun capture(
        name: String,
        dataSource: String?,
        dialect: String?,
        filter: String,
        columns: List<Pair<String, String>>,
        selectedRowCount: Int,
        rows: Sequence<List<Any?>>,
        pendingChanges: Boolean,
        cellEditing: Boolean,
        pageStart: Int,
        ddl: String? = null,
        ddlEditor: Boolean = false,
    ): JsonObject {
        var truncated = columns.size > MAX_COLUMNS || selectedRowCount > MAX_ROWS
        val captured = JsonArray()
        var remaining = MAX_ROW_TEXT
        for (values in rows.take(minOf(MAX_ROWS, selectedRowCount))) {
            val row = JsonArray()
            for (value in values.take(MAX_COLUMNS)) {
                if (value == null) {
                    row.add(JsonNull.INSTANCE)
                    continue
                }
                // Do not invoke formatters on LOBs or lazy driver objects: that can
                // fetch data. Decimal/integer strings also avoid JavaScript precision loss.
                val text = when (value) {
                    is CharSequence, is Number, is Boolean, is java.util.UUID,
                    is java.time.temporal.TemporalAccessor, is java.util.Date -> value.toString()
                    is ByteArray -> "[binary: ${value.size} bytes]"
                    else -> "[value not materialized]"
                }
                if (text.length > MAX_CELL) truncated = true
                row.add(JsonPrimitive(text.take(MAX_CELL)))
            }
            val size = row.toString().length
            if (size > remaining) { truncated = true; break }
            remaining -= size
            captured.add(row)
        }
        return Json.obj(
            "name" to name.take(1_000),
            "dataSource" to dataSource?.take(1_000),
            "dialect" to dialect?.take(1_000),
            "filter" to filter.take(4_000),
            "columns" to Json.array(columns.take(MAX_COLUMNS).map { (name, type) ->
                Json.obj("name" to name.take(256), "type" to type.take(256))
            }),
            "rows" to captured,
            "selectedRowCount" to selectedRowCount,
            "scope" to if (ddlEditor) "ddl" else if (selectedRowCount == 0) "table" else "selected-rows",
            "pendingChanges" to pendingChanges,
            "cellEditing" to cellEditing,
            "pageStart" to pageStart,
            "truncated" to (truncated || captured.size() < selectedRowCount || filter.length > 4_000 || (ddl?.length ?: 0) > MAX_DDL),
        ).apply {
            if (ddl != null) addProperty("ddl", ddl.take(MAX_DDL))
        }
    }
}
