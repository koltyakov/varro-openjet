package varro.host

import varro.protocol.Json
import varro.protocol.str
import varro.server.OpenCodeResponse
import varro.store.VarroStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64

class PortedFeaturesTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun `restores drafts without envelopes and keeps editor views separate`() {
        val store = VarroStore()
        store.setViewState("sidebar", Json.obj("state" to Json.obj("varro.inputDraft" to "legacy draft")))
        store.setViewState("editor-a", Json.obj("varro.inputDraft" to "first editor"))
        store.setViewState("editor-b", Json.obj("varro.inputDraft" to "second editor"))
        store.updateBrowserStorage("varro.projectCurrentDocumentEnabled", "{\"/project\":false}")
        store.updateBrowserStorage("removed", "value")
        store.updateBrowserStorage("removed", null)
        val restored = VarroStore().apply { loadState(store.getState()) }
        assertEquals("legacy draft", restored.viewState("sidebar").str("varro.inputDraft"))
        assertEquals("first editor", restored.viewState("editor-a").str("varro.inputDraft"))
        assertEquals("second editor", restored.viewState("editor-b").str("varro.inputDraft"))
        assertFalse(restored.browserStorage().has("removed"))
        assertEquals("{\"/project\":false}", restored.browserStorage().str("varro.projectCurrentDocumentEnabled"))
        assertEquals(0, VarroStore().browserStorage().size())
    }

    @Test fun `malformed stored view does not prevent startup`() {
        val store = VarroStore().apply { loadState(VarroStore.StoreState().apply {
            viewStates = "{\"sidebar\":42,\"editor\":null}"
        }) }
        assertEquals(0, store.viewState("sidebar").size())
        assertEquals(0, store.viewState("editor").size())
    }

    @Test fun `stores duplicate attachment names separately without path traversal`() {
        val root = temporary.newFolder("attachments").toPath()
        val workspace = temporary.newFolder("workspace").toPath()
        val store = AttachmentStore(root) { workspace.toString() }
        val bytes = "unsaved dropped content".toByteArray()
        val payload = Json.obj("name" to "../../notes.txt", "size" to bytes.size,
            "content" to Base64.getEncoder().encodeToString(bytes))
        val first = store.store(payload)
        val second = store.store(payload)
        val path = java.nio.file.Path.of(first.str("path")!!)
        assertTrue(path.startsWith(root))
        assertNotEquals(first.str("path"), second.str("path"))
        assertArrayEquals(bytes, Files.readAllBytes(path))
        assertEquals("notes.txt", first.str("relativePath"))
        val directory = Files.createDirectory(workspace.resolve("source files"))
        assertEquals("directory", store.describe(directory.toUri().toString()).str("type"))
        assertEquals("source files", store.describe("source files").str("relativePath"))
    }

    @Test fun `rejects mismatched attachment lengths before writing`() {
        val root = temporary.newFolder("attachments").toPath()
        val store = AttachmentStore(root) { null }
        assertThrows(IllegalArgumentException::class.java) {
            store.store(Json.obj("name" to "test", "size" to 100, "content" to "YQ=="))
        }
        Files.list(root).use { assertEquals(0L, it.count()) }
    }

    @Test fun `usage pages across projects and deduplicates assistant rows and prompts`() {
        val now = 1_800_000_000_000L
        val requests = mutableListOf<String>()
        val report = UsageReport { path, options ->
            requests.add(path)
            when {
                path.startsWith("/experimental/session") -> {
                    assertTrue(options.unscoped)
                    assertTrue(options.captureNextCursor)
                    if (path.contains("cursor=")) OpenCodeResponse(Json.array(listOf(Json.obj("id" to "b", "directory" to "/second"))))
                    else OpenCodeResponse(Json.array(listOf(Json.obj("id" to "a", "directory" to "/first"))), "next page")
                }
                else -> {
                    assertTrue(options.directory in listOf("/first", "/second"))
                    val info = Json.obj("id" to "message", "role" to "assistant", "parentID" to "prompt",
                        "providerID" to "provider", "modelID" to "model", "time" to Json.obj("created" to now - 1000),
                        "tokens" to Json.obj("input" to 100, "output" to 10, "reasoning" to 5,
                            "cache" to Json.obj("read" to 20, "write" to 30)), "cost" to 0.25)
                    OpenCodeResponse(Json.array(listOf(Json.obj("info" to info), Json.obj("info" to info))))
                }
            }
        }.build(true, now)
        assertTrue(requests.any { it.contains("cursor=next+page") })
        assertTrue(report.contains("| provider/model | 2 | 200 | 20 | 10 | 40 | 60 | 0.5000 |"))
        assertTrue(report.contains("## All time"))
        assertTrue(report.contains("2 sessions scanned"))
    }

    @Test fun `usage scans more than 250 sessions across pages without truncating totals`() {
        val now = 1_800_000_000_000L
        val scanned = mutableListOf<String>()
        val report = UsageReport { path, _ ->
            if (path.startsWith("/experimental/session")) {
                val lastPage = path.contains("cursor=")
                val ids = if (lastPage) 250..300 else 1..250
                OpenCodeResponse(Json.array(ids.map { Json.obj("id" to "session-$it") }),
                    if (lastPage) null else "next")
            } else {
                scanned.add(path)
                OpenCodeResponse(Json.array(listOf(Json.obj("info" to Json.obj(
                    "id" to "message", "role" to "assistant", "parentID" to "prompt",
                    "providerID" to "provider", "modelID" to "model",
                    "time" to Json.obj("created" to now - 1000),
                    "tokens" to Json.obj("input" to 1), "cost" to 0.25,
                )))))
            }
        }.build(false, now)
        assertEquals(300, scanned.size)
        assertEquals(300, scanned.toSet().size)
        assertTrue(report.contains("300 sessions scanned"))
        assertTrue(report.contains("| provider/model | 300 | 300 | 0 | 0 | 0 | 0 | 75.0000 |"))
    }

    @Test fun `usage reports missing history instead of silently counting it as zero`() {
        val report = UsageReport { path, _ ->
            if (path.startsWith("/experimental")) OpenCodeResponse(Json.array(listOf(Json.obj("id" to "missing"))))
            else error("History unavailable")
        }.build(false)
        assertTrue(report.contains("## Incomplete history"))
        assertTrue(report.contains("History unavailable"))
        assertFalse(report.contains("## All time"))
    }
}
