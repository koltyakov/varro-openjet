package varro.host

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import java.nio.file.Path

/** Uses language plugins' symbol indexes, never a filename guess or content scan. */
internal object IndexedCitationSymbols {
    fun lookup(project: Project, name: String): List<CitationSymbols.Definition> {
        val future = ReadAction.nonBlocking<List<CitationSymbols.Definition>> {
            if (project.isDisposed || DumbService.isDumb(project)) return@nonBlocking emptyList()
            val results = mutableListOf<CitationSymbols.Definition>()
            for (provider in ChooseByNameContributor.SYMBOL_EP_NAME.extensionList) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                for (item in provider.getItemsByName(name, name, project, false)) {
                    if (item.name != name) continue
                    val element = (item as? PsiElement)?.navigationElement ?: continue
                    if (!element.isValid) continue
                    val file = element.containingFile ?: continue
                    val virtual = file.virtualFile ?: continue
                    if (virtual.fileSystem.protocol != "file" || virtual.isDirectory) continue
                    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: continue
                    val offset = element.textOffset
                    if (offset !in 0..document.textLength) continue
                    val line = document.getLineNumber(offset)
                    results.add(CitationSymbols.Definition(name, Path.of(virtual.path), line, offset - document.getLineStartOffset(line)))
                    if (results.size > 1_000) return@nonBlocking results
                }
            }
            results
        }.expireWith(project).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
        return try { future.get() } catch (failure: InterruptedException) {
            future.cancel()
            Thread.currentThread().interrupt()
            throw failure
        }
    }
}
