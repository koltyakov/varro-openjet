package varro.database

import com.intellij.database.model.basic.BasicElement
import com.intellij.database.psi.DbElement
import com.intellij.database.view.DataSourceNode
import com.intellij.openapi.actionSystem.DataKey

/**
 * Database Explorer data keys, resolved by name.
 *
 * 263.8311 moved these keys from DatabaseView to CoreDatabaseViewKeys, which
 * older IDEs do not have. Referencing either class's fields fails with
 * NoSuchFieldError on the other. The key names are unchanged, and
 * DataKey.create returns the registered key for a name, so these are the same
 * instances the Explorer provides on every supported IDE.
 */
internal object DatabaseViewKeys {
    val DATABASE_ELEMENTS: DataKey<Array<BasicElement>> = DataKey.create("DATABASE_ELEMENTS")
    val DB_ELEMENTS: DataKey<Array<DbElement>> = DataKey.create("DB_ELEMENTS")
    val DATABASE_RELATED_DATA_SOURCES: DataKey<Array<DataSourceNode>> = DataKey.create("DATABASE_RELATED_DATA_SOURCES")
    val DATABASE_TREE_IS_ORIGIN: DataKey<Boolean> = DataKey.create("DATABASE_TREE_IS_ORIGIN")
}
