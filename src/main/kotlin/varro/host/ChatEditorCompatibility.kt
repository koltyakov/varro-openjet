package varro.host

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.swing.SwingUtilities

internal object ChatEditorCompatibility {
    private data class DetachedOpener(val open: Method, val request: Any)

    // These public APIs were added in 261 and are marked experimental. Resolve
    // them at runtime so the same plugin can still load on 252/253.
    private val detachedOpener: DetachedOpener? by lazy {
        try {
            val loader = FileEditorManagerEx::class.java.classLoader
            val requestClass = loader.loadClass("com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest")
            val modeClass = loader.loadClass("com.intellij.openapi.fileEditor.ex.FileEditorOpenMode")
            var request = requestClass.getConstructor().newInstance()
            request = requestClass.getMethod("withOpenMode", modeClass)
                .invoke(request, modeClass.getField("NEW_WINDOW").get(null))
            for (option in listOf("withReuseOpen", "withRequestFocus", "withSelectAsCurrent")) {
                request = requestClass.getMethod(option, Boolean::class.javaPrimitiveType).invoke(request, true)
            }
            DetachedOpener(
                FileEditorManagerEx::class.java.getMethod("openFile", VirtualFile::class.java, requestClass),
                request,
            )
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: NoSuchFieldException) {
            null
        }
    }

    /** Returns false when this IDE only supports opening a regular editor tab. */
    fun openDetached(manager: FileEditorManager, file: VirtualFile): Boolean {
        val opener = detachedOpener ?: return false
        if (manager !is FileEditorManagerEx) return false

        // Close a main-frame chat before detaching to avoid two browsers sharing
        // its view ID. Leave an already detached chat open for reuseOpen to focus.
        val mainFrame = WindowManager.getInstance().getFrame(manager.project)
        if (mainFrame != null && manager.getAllEditors(file).any {
                SwingUtilities.getWindowAncestor(it.component) === mainFrame
            }) {
            manager.closeFile(file)
        }
        try {
            opener.open.invoke(manager, file, opener.request)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
        return true
    }
}
