package varro.host

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.JsonObject
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Stable VFS URLs let IntelliJ save and restore chat tabs with the editor layout. */
class VarroChatFile(
    private val projectId: String,
    val viewId: String,
    var route: JsonObject,
    private var title: String,
) : VirtualFile() {
    override fun getFileSystem() = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
        .getFileSystem(VarroChatFileSystem.PROTOCOL)
    override fun getPath() = "/$projectId/$viewId"
    override fun getName() = title
    override fun isWritable() = false
    override fun isDirectory() = false
    override fun isValid() = true
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile> = emptyArray()
    override fun getTimeStamp() = 0L
    override fun getLength() = 0L
    override fun getModificationStamp() = 0L
    override fun contentsToByteArray() = byteArrayOf()
    override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): java.io.OutputStream =
        throw java.io.IOException("Chat tabs do not contain editable file content")
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) { postRunnable?.run() }
    override fun rename(requestor: Any?, newName: String) { title = newName }
}

class VarroChatFileSystem : com.intellij.openapi.vfs.DeprecatedVirtualFileSystem(), com.intellij.openapi.vfs.NonPhysicalFileSystem {
    private val files = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<VarroChatFile>>()
    override fun getProtocol() = PROTOCOL
    override fun isReadOnly() = true
    override fun refresh(asynchronous: Boolean) = Unit
    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)
    @Synchronized
    override fun findFileByPath(path: String): VarroChatFile? {
        files[path]?.get()?.let { return it }
        val parts = path.trimStart('/').split('/', limit = 2)
        if (parts.size != 2) return null
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed && it.locationHash == parts[0] } ?: return null
        val route = varro.store.VarroStore.getInstance(project).editorRoutes.get(parts[1])
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val title = route.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "Varro Chat"
        val file = VarroChatFile(parts[0], parts[1], route, title)
        files[path] = java.lang.ref.WeakReference(file)
        return file
    }

    companion object { const val PROTOCOL = "varro-chat" }
}

class VarroChatEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is VarroChatFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = VarroChatEditor(project, file as VarroChatFile)
    override fun getEditorTypeId() = "varro-chat"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class VarroChatEditor(project: Project, private val chatFile: VarroChatFile) : UserDataHolderBase(), FileEditor {
    private val host = VarroProjectService.getInstance(project).createPanel(
        WebviewHost.Surface.EDITOR, chatFile.viewId, chatFile.route,
    )

    init { Disposer.register(this, host) }
    override fun getComponent(): JComponent = host.component
    override fun getPreferredFocusedComponent(): JComponent = host.component
    override fun getName() = "Varro"
    override fun getFile(): VirtualFile = chatFile
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun selectNotify() { host.requestFocus() }
    override fun dispose() = Unit
}
