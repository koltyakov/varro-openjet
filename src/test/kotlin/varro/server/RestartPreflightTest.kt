package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import java.nio.file.Files

class RestartPreflightTest {
    @Test fun `deleted v2 directory skips location reads but retains busy and attention blockers`() {
        val root = Files.createTempDirectory("restart-preflight-")
        try {
            val directory = root.resolve("deleted").toString()
            for (state in listOf("idle", "busy", "attention")) {
                val result = RestartPreflight(true, emptyMap(), { if (state == "attention") setOf("old") else emptySet() }) { path, options ->
                    assertNull(options.directory)
                    OpenCodeResponse(when {
                        path.startsWith("/experimental/session") -> Json.array(listOf(Json.obj("id" to "old", "directory" to directory)))
                        path == "/session/status" -> if (state == "busy") Json.obj("old" to Json.obj("type" to "busy")) else Json.obj()
                        else -> Json.array(emptyList<Any>())
                    })
                }.read()
                assertEquals(if (state == "idle") 0 else 1, result.get("totalSessionCount").asInt)
                if (state != "idle") assertEquals(directory, result.getAsJsonArray("directories")[0].asJsonObject.get("directory").asString)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `existing directory attention errors prevent restart`() {
        val root = Files.createTempDirectory("restart-preflight-")
        try {
            assertThrows(IllegalStateException::class.java) {
                RestartPreflight(true, mapOf("session" to root.toString()), { emptySet() }) { path, options ->
                    if (options.directory != null && path == "/question") error("500 Internal Server Error")
                    OpenCodeResponse(if (path == "/session/status") Json.obj() else Json.array(emptyList<Any>()))
                }.read()
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
