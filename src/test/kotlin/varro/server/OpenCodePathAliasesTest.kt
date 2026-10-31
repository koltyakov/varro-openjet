package varro.server

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class OpenCodePathAliasesTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    private fun workspace(): Pair<String, String> {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
        val root = temporary.newFolder().toPath().toRealPath()
        val physical = Files.createDirectory(root.resolve("physical")).toString()
        val logical = Files.createSymbolicLink(root.resolve("logical"), java.nio.file.Path.of(physical)).toString()
        return logical to physical
    }

    @Test fun `physical scopes restore editor paths without changing siblings or multiline output`() {
        val (logical, physical) = workspace()
        val aliases = OpenCodePathAliases()
        assertEquals(physical, aliases.toServerDirectory("$logical/"))
        assertEquals("$physical/missing", aliases.toServerDirectory("$logical/missing"))
        val input = Json.obj("sessions" to listOf(Json.obj("directory" to physical, "files" to listOf("$physical/src/a.kt"))),
            "sibling" to "${physical}-other", "output" to "$physical\n$physical/src", "title" to "unchanged")
        val mapped = aliases.fromServer(input).asObjectOrNull()!!
        assertEquals(logical, mapped.arr("sessions")!![0].asObjectOrNull().str("directory"))
        assertEquals("$logical/src/a.kt", mapped.arr("sessions")!![0].asObjectOrNull().arr("files")!![0].asString)
        assertEquals("${physical}-other", mapped.str("sibling"))
        assertEquals("$physical\n$physical/src", mapped.str("output"))
        assertEquals(physical, input.arr("sessions")!![0].asObjectOrNull().str("directory"))
    }

    @Test fun `query and body paths resolve without mutating the request`() {
        val (logical, physical) = workspace()
        val aliases = OpenCodePathAliases()
        for (key in listOf("directory", "location%5Bdirectory%5D")) {
            val path = aliases.toServerRequestPath("/api/session?$key=$logical&limit=5&cursor=a%2Bb")
            assertTrue(path.contains(java.net.URLEncoder.encode(physical, Charsets.UTF_8).replace("+", "%20")))
            assertTrue(path.endsWith("&limit=5&cursor=a%2Bb"))
        }
        val input = Json.obj("location" to Json.obj("directory" to logical), "path" to "$logical/README.md")
        val mapped = aliases.toServer(input).asObjectOrNull()
        assertEquals(physical, mapped.obj("location").str("directory"))
        assertEquals("$physical/README.md", mapped.str("path"))
        assertEquals(logical, input.obj("location").str("directory"))
        assertEquals("/api/session?limit=5", aliases.toServerRequestPath("/api/session?limit=5"))
        assertThrows(IllegalArgumentException::class.java) {
            OpenCodeRequestScope.scope("http://localhost", aliases.toServerRequestPath("//foreign/api/session?directory=$logical"), null)
        }
    }

    @Test fun `unchanged responses retain identity and unknown paths stay unchanged`() {
        val (_, physical) = workspace()
        val aliases = OpenCodePathAliases()
        assertEquals(physical, aliases.toServerDirectory(physical))
        assertEquals("/missing/varro/workspace", aliases.toServerDirectory("/missing/varro/workspace"))
        assertEquals("relative/path", aliases.toServerDirectory("relative/path"))
        val input = Json.obj("directory" to physical)
        assertSame(input, aliases.fromServer(input))
        assertSame(input, aliases.toServer(input))
    }

    @Test fun `attach-only transitions suppress learned aliases in both directions`() {
        val (logical, physical) = workspace()
        var enabled = false
        val aliases = OpenCodePathAliases({ enabled })
        assertEquals(logical, aliases.toServerDirectory(logical))
        enabled = true
        assertEquals(physical, aliases.toServerDirectory(logical))
        enabled = false
        val input = Json.obj("directory" to physical)
        assertSame(input, aliases.fromServer(input))
        assertEquals(logical, aliases.toServerDirectory(logical))
        val body = Json.obj("directory" to logical)
        assertSame(body, aliases.toServer(body))
        assertEquals("/api/session?directory=$logical", aliases.toServerRequestPath("/api/session?directory=$logical"))
    }

    @Test fun `Windows preserves exact paths even when aliasing is requested`() {
        val aliases = OpenCodePathAliases(isWindows = true)
        for (directory in listOf("c:\\Work\\Project\\", "/var/workspace/")) {
            assertEquals(directory, aliases.toServerDirectory(directory))
            val input = Json.obj("directory" to directory)
            assertSame(input, aliases.toServer(input))
            assertSame(input, aliases.fromServer(input))
        }
    }

    @Test fun `nested aliases use the longest matching physical root`() {
        val (logical, physical) = workspace()
        val nested = Files.createDirectory(java.nio.file.Path.of(physical, "nested"))
        val nestedLogical = Files.createSymbolicLink(nested.parent.parent.resolve("nested-alias"), nested).toString()
        val aliases = OpenCodePathAliases()
        aliases.toServerDirectory(logical)
        aliases.toServerDirectory(nestedLogical)
        assertEquals(nestedLogical, aliases.fromServer(Json.obj("directory" to nested.toString())).asObjectOrNull().str("directory"))
        assertEquals(logical, aliases.fromServer(Json.obj("directory" to physical)).asObjectOrNull().str("directory"))
    }
}
