package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class CitationPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `exact checks never discover basenames or escape via relative aliases`() {
        val root = temporary.root.toPath()
        val target = Files.createDirectories(root.resolve("src")).resolve("file.ts").also { Files.writeString(it, "text") }
        val service = CitationPaths()
        val results = service.validate(Json.obj("paths" to listOf("src/file.ts", "file.ts", "src", "../other", target.toString())), root.toString(), { _, _ -> true })
        assertEquals(listOf(target.toString(), null, root.resolve("src").toString(), null, target.toString()), results.map { if (it.isJsonNull) null else it.asString })
        assertTrue(FileReferences.resolve("file.ts", root.toString(), emptyList(), exactOnly = true).isEmpty())
        assertEquals(listOf(target), FileReferences.resolve("file.ts", root.toString(), emptyList()))
    }

    @Test fun `authorization and bounded strict input validation precede filesystem reads`() {
        val service = CitationPaths()
        val root = temporary.root.absolutePath
        val valid = Json.obj("paths" to listOf("file"), "sessionID" to "session", "directory" to root)
        assertThrows(IllegalArgumentException::class.java) { service.validate(valid, null, { _, _ -> false }) }
        for (invalid in listOf(Json.obj("paths" to listOf(1)), Json.obj("paths" to List(65) { "file" }), Json.obj("paths" to listOf("")),
            Json.obj("paths" to listOf("a".repeat(4097))), Json.obj("paths" to listOf("file"), "sessionID" to "../bad"),
            Json.obj("paths" to listOf("file"), "directory" to 5))) {
            assertThrows(IllegalArgumentException::class.java) { service.validate(invalid, root, { _, _ -> error("Must not authorize invalid input") }) }
        }
        assertTrue(ApiRoutes.isAllowed("POST", ApiRoutes.Endpoints.CITATION_PATHS))
        assertFalse(ApiRoutes.isAllowed("GET", ApiRoutes.Endpoints.CITATION_PATHS))
        assertFalse(ApiRoutes.isAllowed("POST", "${ApiRoutes.Endpoints.CITATION_PATHS}?directory=$root"))
    }

    @Test fun `positive and negative exact-path cache entries expire independently`() {
        var now = 0L
        val root = temporary.root.toPath()
        val file = root.resolve("file")
        val service = CitationPaths { now }
        fun read() = service.validate(Json.obj("paths" to listOf("file")), root.toString(), { _, _ -> true })[0]
        assertTrue(read().isJsonNull)
        Files.writeString(file, "text")
        assertTrue(read().isJsonNull)
        now = 1000
        assertEquals(file.toString(), read().asString)
        Files.delete(file)
        now += 14_999
        assertEquals(file.toString(), read().asString)
        now++
        assertTrue(read().isJsonNull)
    }

    @Test fun `excluded discovery entries stay hidden but exact paths remain available`() {
        val root = temporary.root.toPath()
        val hidden = Files.createDirectories(root.resolve("excluded")).resolve("file.ts").also { Files.writeString(it, "text") }
        assertTrue(FileReferences.resolve("file.ts", root.toString(), emptyList(), excluded = { it.startsWith(hidden.parent) }).isEmpty())
        assertEquals(listOf(hidden), FileReferences.resolve("excluded/file.ts", root.toString(), emptyList(), excluded = { true }))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            CitationPaths().validate(Json.obj("paths" to listOf("file")), root.toString(), { _, _ -> true }, { true })
        }
    }
}
