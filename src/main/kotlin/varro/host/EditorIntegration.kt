package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.diff.DiffContentFactory as DiffFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import varro.protocol.Json
import varro.server.WorkspacePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * IDE-side actions the webview asks for: opening files, diffs and read-only tool
 * output, picking files, and searching the project.
 *
 * Port of the editor-facing parts of `sidebar-provider-actions.ts`,
 * `file-search-service.ts`, `session-diff-document-provider.ts` and
 * `tool-output-document-provider.ts`.
 */
class EditorIntegration(private val project: Project) {

    private val log = logger<EditorIntegration>()

    /** Cache for file search, mirroring upstream's short-lived ranking cache. */
    private val searchCache = AtomicReference<Pair<Long, List<VirtualFile>>?>(null)

    private fun projectRoot(): String? = project.guessProjectDir()?.path ?: project.basePath

    // --- Opening --------------------------------------------------------------

    /**
     * Opens a path in the editor.
     *
     * @return `"opened"` or `"unavailable"`, which is what the webview's
     *   `openPathWithResult` promise resolves to.
     */
    fun openPath(path: String, line: Int?, kind: String?): String {
        val file = resolve(path) ?: return "unavailable"
        var result = "unavailable"
        ApplicationManager.getApplication().invokeAndWait {
            result = when {
                file.isDirectory -> {
                    // A directory has no editor; revealing it in the Project view is
                    // the closest equivalent to VS Code's reveal-in-explorer.
                    com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, file, true)
                    "opened"
                }
                kind == "directory" -> "unavailable"
                else -> {
                    val descriptor = if (line != null && line > 0) {
                        OpenFileDescriptor(project, file, line - 1, 0)
                    } else {
                        OpenFileDescriptor(project, file)
                    }
                    val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                    editor?.scrollingModel?.scrollToCaret(ScrollType.CENTER)
                    if (editor != null || FileEditorManager.getInstance(project).isFileOpen(file)) {
                        "opened"
                    } else {
                        "unavailable"
                    }
                }
            }
        }
        return result
    }

    /**
     * Opens arbitrary tool text in a read-only tab. The webview clamps long tool
     * output rather than scrolling it in place, so this is the escape hatch to the
     * full text.
     */
    fun openText(content: String, title: String, language: String?) {
        ApplicationManager.getApplication().invokeLater {
            val fileType = when (language) {
                "json" -> FileTypeManager.getInstance().getFileTypeByExtension("json")
                "markdown" -> FileTypeManager.getInstance().getFileTypeByExtension("md")
                "shellscript" -> FileTypeManager.getInstance().getFileTypeByExtension("sh")
                "xml" -> FileTypeManager.getInstance().getFileTypeByExtension("xml")
                else -> PlainTextFileType.INSTANCE
            }
            val file = LightVirtualFile(sanitizeTitle(title), fileType, content).apply {
                isWritable = false
            }
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    /** Opens a two-sided diff for a file change reported by a session. */
    fun openDiff(path: String, before: String, after: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            val factory = DiffFactory.getInstance()
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(Paths.get(path).fileName.toString())
            val request = SimpleDiffRequest(
                title,
                factory.create(project, before, fileType),
                factory.create(project, after, fileType),
                "Before",
                "After",
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    /**
     * Writes a completed plan into the project's OpenCode plans directory and
     * opens it, so the user can keep or edit it independently of the session.
     */
    fun openPlanDocument(content: String, title: String?): String? {
        val root = projectRoot() ?: return null
        val name = buildString {
            append(java.time.LocalDate.now())
            append('-')
            append(sanitizeTitle(title ?: "plan").replace(Regex("""[^A-Za-z0-9._-]+"""), "-").trim('-').lowercase())
            if (!endsWith(".md")) append(".md")
        }
        val target = Paths.get(root, ".opencode", "plans", name)
        return runCatching {
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
            VfsUtil.markDirtyAndRefresh(false, false, false, target.toFile())
            openPath(target.toString(), null, "file")
            target.toString()
        }.onFailure { log.warn("Failed to write plan document", it) }.getOrNull()
    }

    // --- Picking and reading --------------------------------------------------

    fun pickFile(): JsonObject? {
        val result = AtomicReference<JsonObject?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                .withTitle("Add to Varro Context")
            val chosen = FileChooser.chooseFile(descriptor, project, project.guessProjectDir())
            if (chosen != null) {
                result.set(
                    Json.obj(
                        "path" to chosen.path,
                        "workspaceDirectory" to projectRoot(),
                    ),
                )
            }
        }
        return result.get()
    }

    fun readWorkspaceFile(path: String): JsonObject? {
        val file = resolve(path) ?: return null
        if (file.isDirectory) return null
        if (file.length > MAX_READ_BYTES) {
            return Json.obj("path" to file.path, "truncated" to true, "content" to "")
        }
        val content = runCatching { VfsUtil.loadText(file) }.getOrNull() ?: return null
        return Json.obj(
            "path" to file.path,
            "relativePath" to (WorkspacePaths.relativeWithin(file.path, projectRoot()) ?: file.name),
            "content" to content,
            "truncated" to false,
        )
    }

    fun resolveWorkspacePath(path: String): JsonObject? {
        val file = resolve(path)
        return Json.obj(
            "path" to (file?.path ?: path),
            "exists" to (file != null),
            "type" to when {
                file == null -> "missing"
                file.isDirectory -> "directory"
                else -> "file"
            },
            "relativePath" to (file?.let { WorkspacePaths.relativeWithin(it.path, projectRoot()) }),
        )
    }

    /**
     * Resolves a possibly relative path against the project root. The webview
     * sends both absolute paths (from tool output) and workspace-relative ones
     * (from user typing), so both have to work.
     */
    fun resolve(path: String): VirtualFile? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val candidate = Paths.get(trimmed).let { parsed ->
            if (parsed.isAbsolute) parsed else projectRoot()?.let { Paths.get(it).resolve(parsed) } ?: parsed
        }.normalize()
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(candidate.toString().replace('\\', '/'))
            ?: VirtualFileManager.getInstance().findFileByUrl("file://$candidate")
    }

    // --- Search ---------------------------------------------------------------

    /**
     * Ranked file search for the composer's `@` picker.
     *
     * Upstream uses `vscode.workspace.findFiles` with a short-lived cache and a
     * ranking heuristic; the JetBrains equivalent is the filename index, which is
     * already maintained and avoids walking the tree per keystroke.
     */
    fun searchFiles(query: String, limit: Int): JsonArray {
        val root = projectRoot()
        val normalized = query.trim().lowercase()
        val results = JsonArray()

        val files = runCatching {
            ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
                if (normalized.isEmpty()) {
                    recentFiles()
                } else {
                    FilenameIndex.getAllFilesByExt(project, "", GlobalSearchScope.projectScope(project))
                        .asSequence()
                        .filter { !it.isDirectory }
                        .filter { matches(it, normalized, root) }
                        .sortedBy { rank(it, normalized) }
                        .take(limit)
                        .toList()
                }
            }
        }.getOrElse { failure ->
            log.warn("File search failed", failure)
            emptyList()
        }

        files.take(limit).forEach { file ->
            results.add(
                Json.obj(
                    "path" to file.path,
                    "relativePath" to (WorkspacePaths.relativeWithin(file.path, root) ?: file.name),
                    "type" to if (file.isDirectory) "directory" else "file",
                ),
            )
        }
        return results
    }

    private fun recentFiles(): List<VirtualFile> =
        com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project)
            .fileList
            .filter { it.isValid && !it.isDirectory }

    private fun matches(file: VirtualFile, query: String, root: String?): Boolean {
        val relative = WorkspacePaths.relativeWithin(file.path, root) ?: file.name
        return relative.lowercase().contains(query) || file.name.lowercase().contains(query)
    }

    /**
     * Lower ranks sort first: an exact filename prefix beats a filename match,
     * which beats a path-only match. Shallower paths win ties, matching how the
     * VS Code picker feels.
     */
    private fun rank(file: VirtualFile, query: String): Int {
        val name = file.name.lowercase()
        val depth = file.path.count { it == '/' || it == '\\' }
        return when {
            name == query -> 0
            name.startsWith(query) -> 1_000 + depth
            name.contains(query) -> 10_000 + depth
            else -> 100_000 + depth
        }
    }

    private fun sanitizeTitle(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|]"""), "-").take(120).ifBlank { "varro" }

    companion object {
        private const val MAX_READ_BYTES = 2L * 1024 * 1024
    }
}
