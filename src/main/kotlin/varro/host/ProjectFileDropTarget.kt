package varro.host

import com.google.gson.JsonObject
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.Disposable
import varro.protocol.Json
import javax.swing.JComponent

/** Project-view drags carry IDE objects that Chromium cannot decode as files. */
internal object ProjectFileDropTarget {
    fun install(component: JComponent, parent: Disposable, onDrop: (JsonObject) -> Unit) {
        DnDSupport.createBuilder(component)
            .disableAsSource()
            .enableAsNativeTarget()
            .setDisposableParent(parent)
            .setTargetChecker { event ->
                event.isDropPossible = paths(event.attachedObject).isNotEmpty()
                false
            }
            .setDropHandler { event ->
                message(event.attachedObject)?.let(onDrop)
            }
            .install()
    }

    private fun paths(attached: Any?): List<String> =
        FileCopyPasteUtil.getFileListFromAttachedObject(attached)
            .map { it.path }
            .filter { it.isNotBlank() }
            .distinct()

    internal fun message(attached: Any?): JsonObject? {
        val paths = paths(attached)
        return paths.takeIf { it.isNotEmpty() }?.let {
            Json.message("files/drop", Json.obj("paths" to it))
        }
    }
}
