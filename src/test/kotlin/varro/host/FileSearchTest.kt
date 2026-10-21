package varro.host

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class FileSearchTest {
    private val files = listOf("src/main/App.kt", "src/main/app.ts", "Makefile", "docs/app-guide.md", "app/notes.txt", "other/no.txt")
    private fun search(query: String, limit: Int = 30) = FileSearch<String>(query, limit).also { search ->
        files.forEach { search.offer(it, it) }
    }.results()

    @Test fun `search finds Kotlin TypeScript extensionless and path matches`() {
        assertEquals(listOf("src/main/App.kt"), search("app.kt"))
        assertEquals(listOf("src/main/app.ts"), search("app.ts"))
        assertEquals(listOf("Makefile"), search("make"))
        assertEquals(listOf("src/main/App.kt", "src/main/app.ts"), search("src\\main"))
    }

    @Test fun `ranking is bounded deterministic and prefers filenames over path matches`() {
        assertEquals(listOf("docs/app-guide.md", "src/main/App.kt"), search("app", 2))
        assertEquals(1, search("app", -5).size)
        val rank = FileSearch<Int>("file", 10)
        repeat(100_000) { rank.offer(it, "file-$it.kt") }
        assertEquals(10, rank.results().size)
    }

    @Test fun `superseded searches stop scanning`() {
        val rank = FileSearch<String>("file", 10) { true }
        assertThrows(CancellationException::class.java) { rank.offer("file.kt", "file.kt") }
    }

    @Test fun `large context reads copy only the bounded range`() {
        val source = object : CharSequence {
            override val length = 10_000_000
            override fun get(index: Int): Char = 'x'
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                assertEquals(200, startIndex)
                assertEquals(100_200, endIndex)
                return "x".repeat(endIndex - startIndex)
            }
            override fun toString(): String = error("Full buffer must not be copied")
        }
        assertEquals(100_000, ContextProvider.boundedText(source, 200, source.length).length)
    }
}
