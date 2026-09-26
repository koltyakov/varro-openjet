package varro.database

import com.google.gson.JsonObject
import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbElement
import com.intellij.database.util.DbUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlStatement
import varro.protocol.Json

internal object DatabaseSqlContext {
    const val MAX_SQL = 40_000

    data class Capture(val sql: JsonObject, val objects: List<DbElement>, val focused: DbElement?, val metadataTruncated: Boolean)

    fun capture(editor: Editor): Capture {
        val document = editor.document
        val project = editor.project!!
        val manager = PsiDocumentManager.getInstance(project)
        // An uncommitted PSI tree describes the previous buffer. Use the current text instead.
        val file = if (manager.isCommitted(document)) manager.getPsiFile(document) else null
        val caret = editor.caretModel.offset.coerceIn(0, document.textLength)
        val leaf = file?.findElementAt(caret.coerceAtMost((document.textLength - 1).coerceAtLeast(0)))
        val nearestStatement = PsiTreeUtil.getParentOfType(leaf, SqlStatement::class.java, false)
            ?: generateSequence(leaf) { PsiTreeUtil.prevLeaf(it) }
                .take(32).firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }
                ?.let { PsiTreeUtil.getParentOfType(it, SqlStatement::class.java, false) }
        val statement = generateSequence(nearestStatement) {
            PsiTreeUtil.getParentOfType(it, SqlStatement::class.java, true)
        }.lastOrNull()
        val selection = editor.selectionModel
        val selectedRange = if (selection.hasSelection()) TextRange(selection.selectionStart, selection.selectionEnd) else null
        val range = selectedRange ?: statement?.textRange ?: TextRange(0, document.textLength)
        val sql = editorSql(document, selectedRange, statement?.textRange, editor.caretModel.logicalPosition.line + 1)
        val objects = linkedMapOf<String, DbElement>()
        var focused: DbElement? = null
        var metadataTruncated = false
        if (file != null && !DumbService.isDumb(project)) {
            fun resolved(reference: SqlReferenceExpression): DbElement? = reference.resolve() as? DbElement
            fun add(element: DbElement) {
                val table = generateSequence(element) { it.parent }.firstOrNull { DbUtil.getDasObject(it) is DasTable }
                val target = table ?: element
                objects.putIfAbsent("${target.dataSource.uniqueId}:${DatabaseDetails.qualifiedName(target)}", target)
            }
            PsiTreeUtil.getParentOfType(leaf, SqlReferenceExpression::class.java, false)?.let { reference ->
                if (range.contains(reference.textRange)) {
                    focused = resolved(reference)
                    focused?.let(::add)
                }
            }
            var visited = 0
            var references = 0
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    com.intellij.openapi.progress.ProgressManager.checkCanceled()
                    if (++visited > 2_000 || objects.size >= 8 || references >= 32) {
                        metadataTruncated = true
                        stopWalking()
                        return
                    }
                    if (element.textRange.startOffset >= range.endOffset || element.textRange.endOffset <= range.startOffset) return
                    if (element is SqlReferenceExpression) {
                        references++
                        resolved(element)?.let(::add)
                    }
                    super.visitElement(element)
                }
            })
        }
        return Capture(sql, objects.values.toList(), focused, metadataTruncated)
    }

    internal fun editorSql(document: Document, selection: TextRange?, statement: TextRange?, caretLine: Int): JsonObject {
        val range = selection ?: statement ?: TextRange(0, document.textLength)
        val start = range.startOffset
        val end = range.endOffset
        val text = document.immutableCharSequence.subSequence(start, minOf(end, start + MAX_SQL)).toString()
        return sql(text, if (selection != null) "selection" else if (statement != null) "statement" else "buffer",
            end - start > MAX_SQL).apply {
            addProperty("startLine", document.getLineNumber(start) + 1)
            addProperty("endLine", document.getLineNumber(if (end > start) end - 1 else end) + 1)
            addProperty("caretLine", caretLine)
        }
    }

    fun sql(text: String, kind: String, truncated: Boolean = false): JsonObject = Json.obj(
        "text" to text.take(MAX_SQL), "kind" to kind, "truncated" to (truncated || text.length > MAX_SQL),
    )
}
