package varro.host

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDNativeTarget
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/** Unwraps local IDE objects without interpreting plain text as a table or path. */
internal object IdeDropObjects {
    fun objects(attached: Any?): List<Any> = unwrap(attached, 0).take(100)

    private fun unwrap(value: Any?, depth: Int): List<Any> {
        if (value == null || depth > 4) return emptyList()
        return when (value) {
            is DnDNativeTarget.EventInfo -> unwrap(value.transferable, depth + 1)
            is DnDEvent -> unwrap(value.attachedObject, depth + 1)
            is Transferable -> value.transferDataFlavors.asSequence()
                .filter { it.isMimeTypeEqual(DataFlavor.javaJVMLocalObjectMimeType) }
                .take(4)
                .flatMap { flavor ->
                    val data = try { value.getTransferData(flavor) }
                    catch (_: java.awt.datatransfer.UnsupportedFlavorException) { null }
                    catch (_: java.io.IOException) { null }
                    unwrap(data, depth + 1).asSequence()
                }.take(100).toList()
            is Array<*> -> value.take(100).flatMap { unwrap(it, depth + 1) }.take(100)
            is Collection<*> -> value.take(100).flatMap { unwrap(it, depth + 1) }.take(100)
            else -> listOf(value)
        }
    }
}
