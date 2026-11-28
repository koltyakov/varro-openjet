package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.server.OpenCodeRequestException
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.nio.file.Files
import java.time.Instant

/** Ports upstream `walkthrough.test.ts` and `walkthrough-model.test.ts`, plus service coverage. */
class WalkthroughTest {
    private val gitDiff = listOf(
        "diff --git a/src/limits.ts b/src/limits.ts",
        "index 1111111..2222222 100644",
        "--- a/src/limits.ts",
        "+++ b/src/limits.ts",
        "@@ -1,3 +1,4 @@ export function reset()",
        " const a = 1;",
        "-const b = 2;",
        "+const b = 3;",
        "+const c = 4;",
        "@@ -10,2 +11,2 @@",
        " keep();",
        "--- removed line that looks like a header",
        "diff --git a/src/new.ts b/src/new.ts",
        "new file mode 100644",
        "--- /dev/null",
        "+++ b/src/new.ts",
        "@@ -0,0 +1 @@",
        "+export const fresh = true;",
        "diff --git a/package-lock.json b/package-lock.json",
        "--- a/package-lock.json",
        "+++ b/package-lock.json",
        "@@ -1 +1 @@",
        "-\"version\": \"1\"",
        "+\"version\": \"2\"",
    ).joinToString("\n")

    // --- Diff parsing ----------------------------------------------------------

    private fun withRepository(test: (java.nio.file.Path, (Array<out String>) -> Unit) -> Unit) {
        val root = Files.createTempDirectory("walkthrough-capture")
        try {
            val git = { args: Array<out String> ->
                val process = ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.waitFor())
            }
            git(arrayOf("init"))
            test(root, git)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `oversized staged diff refuses only that scope with staged advice`() = withRepository { root, git ->
        Files.writeString(root.resolve("large.txt"), "x".repeat(WalkthroughGit.MAX_DIFF_BYTES + 1))
        git(arrayOf("add", "large.txt"))
        Files.writeString(root.resolve("small.txt"), "small unstaged addition\n")
        val changes = WalkthroughGit.capture(root.toString()) {}
        assertEquals(WalkthroughGit.STAGED_TOO_LARGE, changes.staged.exceptionOrNull()?.message)
        assertTrue(WalkthroughGit.STAGED_TOO_LARGE.contains("4 MB") && WalkthroughGit.STAGED_TOO_LARGE.contains("Unstage"))
        assertTrue(changes.unstaged.getOrThrow().contains("+small unstaged addition"))
    }

    @Test fun `oversized unstaged diff refuses only that scope and suggests the staged walkthrough`() = withRepository { root, git ->
        Files.writeString(root.resolve("large.txt"), "small\n")
        Files.writeString(root.resolve("staged.txt"), "staged addition\n")
        git(arrayOf("add", "large.txt", "staged.txt"))
        Files.writeString(root.resolve("large.txt"), "x".repeat(WalkthroughGit.MAX_DIFF_BYTES + 1))
        val changes = WalkthroughGit.capture(root.toString()) {}
        assertEquals(WalkthroughGit.UNSTAGED_TOO_LARGE, changes.unstaged.exceptionOrNull()?.message)
        assertTrue(WalkthroughGit.UNSTAGED_TOO_LARGE.contains("walk through the staged changes"))
        assertTrue(changes.staged.getOrThrow().contains("+staged addition"))
    }

    @Test fun `untracked limit counts files rather than path bytes`() = withRepository { root, _ ->
        // 1,000 long names exceed the previous 128 KiB path-list cap but stay under the file-count limit.
        val name = "n".repeat(200)
        repeat(1_000) { Files.writeString(root.resolve("$name-$it.txt"), "") }
        val captured = WalkthroughGit.capture(root.toString()) {}.unstaged.getOrThrow()
        assertTrue(captured.contains("$name-999.txt"))
    }

    @Test fun `too many untracked files refuses unstaged with an actionable message`() = withRepository { root, git ->
        Files.writeString(root.resolve("staged.txt"), "staged addition\n")
        git(arrayOf("add", "staged.txt"))
        val generated = Files.createDirectory(root.resolve("generated"))
        repeat(WalkthroughGit.MAX_UNTRACKED_PATHS + 1) { Files.writeString(generated.resolve("$it.txt"), "") }
        val changes = WalkthroughGit.capture(root.toString()) {}
        assertEquals(WalkthroughGit.TOO_MANY_UNTRACKED, changes.unstaged.exceptionOrNull()?.message)
        assertTrue(WalkthroughGit.TOO_MANY_UNTRACKED.startsWith("There are more than 4,096 untracked files."))
        assertTrue(changes.staged.getOrThrow().contains("+staged addition"))
    }

    @Test fun `diff parsing stops when cancelled within a hunk`() {
        val patch = gitDiff + "\n+line".repeat(10_000)
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            Walkthrough.parseUnifiedDiff(patch, "staged") {
                if (++checks == 100) throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals(100, checks)
    }

    @Test fun `parses git diffs into files and hunks keeping removed lines that resemble headers`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        assertEquals(
            listOf(Triple("src/limits.ts", "modified", false), Triple("src/new.ts", "added", false), Triple("package-lock.json", "modified", true)),
            files.map { Triple(it.path, it.status.id, it.generated) },
        )
        assertEquals(2, files[0].hunks.size)
        assertEquals(listOf(" keep();", "--- removed line that looks like a header"), files[0].hunks[1].lines)
        assertEquals(2, files[0].additions)
        assertEquals(2, files[0].deletions)
        assertEquals(1, files[0].hunks[0].newStart)
    }

    @Test fun `parses patches without a git header`() {
        val files = Walkthrough.parseUnifiedDiff(listOf(
            "Index: src/app.ts",
            "===================================================================",
            "--- src/app.ts\t",
            "+++ src/app.ts\t",
            "@@ -4,1 +4,1 @@",
            "-old();",
            "+next();",
        ).joinToString("\n"), "unstaged")
        assertEquals(1, files.size)
        assertEquals("src/app.ts", files[0].path)
        assertEquals(1, files[0].additions)
        assertEquals(1, files[0].deletions)
    }

    @Test fun `parses deletions renames binaries omitted content and CRLF`() {
        val files = Walkthrough.parseUnifiedDiff(listOf(
            "diff --git a/old.ts b/old.ts",
            "deleted file mode 100644",
            "--- a/old.ts",
            "+++ /dev/null",
            "@@ -1 +0,0 @@",
            "-gone();",
            "diff --git a/from.ts b/to.ts",
            "similarity index 90%",
            "rename from from.ts",
            "rename to to.ts",
            "diff --git a/logo.png b/logo.png",
            "Binary files a/logo.png and b/logo.png differ",
            "diff --git a/big.txt b/big.txt",
            "new untracked file (90000 bytes; content omitted)",
            "diff --git a/link b/link",
            "new file mode 120000",
            "symbolic link; content omitted",
            "diff --git a/win.ts b/win.ts",
            "--- a/win.ts",
            "+++ b/win.ts",
            "@@ -1 +1 @@",
            "-a();",
            "+b();",
            " ",
            "",
        ).joinToString("\r\n"), "unstaged")
        assertEquals(listOf("old.ts", "to.ts", "logo.png", "big.txt", "link", "win.ts"), files.map { it.path })
        assertEquals(WalkthroughFileStatus.DELETED, files[0].status)
        assertEquals("old.ts", files[0].hunks.single().path)
        assertEquals("Binary file", files[2].note)
        assertEquals("(90000 bytes; content omitted)", files[3].note)
        assertEquals(WalkthroughFileStatus.ADDED, files[4].status)
        assertEquals("symbolic link; content omitted", files[4].note)
        // Trailing blank context lines are trimmed and CRLF is normalized.
        assertEquals(listOf("-a();", "+b();"), files[5].hunks.single().lines)
    }

    @Test fun `synthetic untracked patches parse as added files and respect upstream limits`() {
        val root = Files.createTempDirectory("walkthrough-git")
        try {
            Files.writeString(root.resolve("fresh.ts"), "export const a = 1;\nexport const b = 2;\n")
            Files.write(root.resolve("data.bin"), byteArrayOf(1, 0, 2))
            Files.writeString(root.resolve("huge.txt"), "x".repeat(WalkthroughGit.MAX_UNTRACKED_FILE_BYTES + 1))
            val patches = WalkthroughGit.untrackedPatches(root, listOf("fresh.ts", "data.bin", "huge.txt", "missing.ts"))
            assertEquals("diff --git a/huge.txt b/huge.txt\nnew untracked file (20001 bytes; content omitted)", patches[2])
            assertEquals("diff --git a/missing.ts b/missing.ts\nnew untracked file (content unavailable)", patches[3])
            val files = Walkthrough.parseUnifiedDiff(patches.joinToString("\n"), "unstaged")
            assertEquals(listOf("fresh.ts", "data.bin", "huge.txt", "missing.ts"), files.map { it.path })
            assertEquals(WalkthroughFileStatus.ADDED, files[0].status)
            assertEquals(listOf("+export const a = 1;", "+export const b = 2;"), files[0].hunks.single().lines)
            assertEquals(2, files[0].additions)
            assertTrue(patches[1].endsWith("@@ -0,0 +1 @@\nBinary untracked file (3 bytes)"))
            assertEquals("(content unavailable)", files[3].note)

            // The total budget omits content once 40,000 bytes have been included.
            for (index in 0 until 3) Files.writeString(root.resolve("part$index.txt"), "y".repeat(15_000))
            val bounded = WalkthroughGit.untrackedPatches(root, listOf("part0.txt", "part1.txt", "part2.txt"))
            assertTrue(bounded[1].endsWith("y"))
            assertTrue(bounded[2].endsWith("\n+... content truncated ..."))
            val exhausted = WalkthroughGit.untrackedPatches(root, listOf("part0.txt", "part1.txt", "part2.txt", "fresh.ts"))
            assertEquals("diff --git a/fresh.ts b/fresh.ts\nnew untracked file (40 bytes; content omitted)", exhausted[3])
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- Digest and prompt -----------------------------------------------------

    @Test fun `shows the model aliases only and keeps generated files out of its input`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        val digest = Walkthrough.buildDigest(files)
        assertEquals(listOf("h1", "h2", "h3"), digest.hunkByAlias.keys.toList())
        assertTrue(digest.digest.contains("[h1] @@ -1,3 +1,4 @@ export function reset()"))
        assertTrue(digest.digest.contains("FILE package-lock.json (modified, +1 -1): generated file"))
        assertFalse(digest.digest.contains("\"version\": \"2\""))
        val prompt = Walkthrough.buildPrompt(digest.digest, "Staged changes")
        assertTrue(prompt.contains("----- BEGIN UNTRUSTED STAGED CHANGES DIFF -----"))
        assertTrue(prompt.contains("never more than 8"))
        assertTrue(prompt.contains("Summarize them together in \"minor\""))
    }

    @Test fun `labels sections only when staged and unstaged are mixed`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged") + Walkthrough.parseUnifiedDiff(gitDiff, "unstaged")
        val digest = Walkthrough.buildDigest(files).digest
        assertTrue(digest.contains("FILE src/limits.ts [staged] (modified, +2 -2)"))
        assertTrue(digest.contains("FILE src/limits.ts [unstaged] (modified, +2 -2)"))
    }

    private fun patch(path: String, count: Int) = (listOf("--- a/$path", "+++ b/$path", "@@ -0,0 +1,$count @@") +
        (0 until count).map { "+line $it ${"x".repeat(40)}" }).joinToString("\n")

    @Test fun `leaves tests and then docs out before refusing a diff that does not fit`() {
        val files = Walkthrough.parseUnifiedDiff(listOf(
            patch("src/core.ts", 400), patch("src/core.test.ts", 2_000), patch("docs/usage.md", 2_000),
        ).joinToString("\n"), "unstaged")
        val digest = Walkthrough.buildDigest(files, 60_000)
        assertTrue(digest.digest.length <= 60_000)
        assertEquals(listOf("src/core.ts"), digest.hunkByAlias.values.map { it.path })
        assertTrue(digest.digest.contains("FILE src/core.test.ts (modified, +2000 -0): content not shown to fit"))
        assertEquals(listOf(false, true, true), files.map { it.omitted })
        val walkthrough = Walkthrough.normalize(
            Json.parse("""{"title":"Core","stops":[{"title":"Core","hunks":["h1"],"explanation":"x"}]}"""), files, digest.hunkByAlias)
        assertEquals(listOf("not sent to the model, to fit the request", "not sent to the model, to fit the request"),
            walkthrough.uncovered.map { it.note })

        val source = Walkthrough.parseUnifiedDiff(patch("src/big.ts", 4_000), "unstaged")
        val failure = assertThrows(WalkthroughTooLargeException::class.java) { Walkthrough.buildDigest(source, 60_000) }
        assertEquals(60_000, failure.budgetChars)
        assertTrue(failure.message!!.startsWith("This diff is too large for one walkthrough: ~"))
        assertTrue(failure.message!!.endsWith("and this model's walkthrough limit is ~20k."))
    }

    @Test fun `sizes the walkthrough budget to half the context window within bounds`() {
        assertEquals(Walkthrough.DEFAULT_DIGEST_CHARS, Walkthrough.digestBudget(null))
        assertEquals(Walkthrough.DEFAULT_DIGEST_CHARS, Walkthrough.digestBudget(0))
        assertEquals(192_000, Walkthrough.digestBudget(128_000))
        assertEquals(Walkthrough.MAX_DIGEST_CHARS, Walkthrough.digestBudget(1_000_000))
        assertEquals(60_000, Walkthrough.digestBudget(8_000))
        assertEquals("~1k", Walkthrough.formatTokens(10))
        assertEquals("~50k", Walkthrough.formatTokens(150_000))
    }

    // --- Normalization ---------------------------------------------------------

    @Test fun `drops unknown or reused aliases and reports what no stop covers`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        val digest = Walkthrough.buildDigest(files)
        val walkthrough = Walkthrough.normalize(Json.parse("""
            {"title":"Reset limits","focus":"Limits reset sooner.","stops":[
              {"title":"New reset values","importance":"key","hunks":["h1","h99"],"explanation":"The reset now uses `b = 3`.",
               "observations":[{"kind":"decision","note":"Three, not four."},{"kind":"verdict","note":"Looks good"}]},
              {"title":"Reuse","importance":"normal","hunks":["h1"],"explanation":"Dropped."},
              {"title":"Fresh module","importance":"context","hunks":[" h3 "],"explanation":"Adds it."}],
             "minor":"Reformats the `keep()` call."}
        """.trimIndent()), files, digest.hunkByAlias)
        assertEquals(listOf("New reset values", "Fresh module"), walkthrough.stops.map { it.title })
        assertEquals(WalkthroughImportance.KEY, walkthrough.stops[0].importance)
        assertEquals(WalkthroughImportance.CONTEXT, walkthrough.stops[1].importance)
        assertEquals("Reformats the `keep()` call.", walkthrough.minor)
        assertEquals(listOf(WalkthroughObservation(WalkthroughObservationKind.DECISION, "Three, not four.")), walkthrough.stops[0].observations)
        assertEquals(
            listOf(Triple("src/limits.ts", 1, null), Triple("package-lock.json", 1, "generated, not sent to the model")),
            walkthrough.uncovered.map { Triple(it.file.path, it.hunks.size, it.note) },
        )
    }

    @Test fun `rejects answers without usable stops and clips long text`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        val digest = Walkthrough.buildDigest(files)
        assertEquals("The model returned no walkthrough.",
            assertThrows(IllegalStateException::class.java) { Walkthrough.normalize(Json.parse("[]"), files, digest.hunkByAlias) }.message)
        assertEquals("The model returned a walkthrough without any usable stops. Try again.",
            assertThrows(IllegalStateException::class.java) {
                Walkthrough.normalize(Json.parse("""{"stops":[{"title":"x","hunks":["h99"]},{"title":"","hunks":["h1"]}]}"""), files, digest.hunkByAlias)
            }.message)
        val long = "a".repeat(150)
        val walkthrough = Walkthrough.normalize(Json.parse("""{"title":"$long","stops":[{"title":"t","importance":"odd","hunks":["h1"]}]}"""),
            files, digest.hunkByAlias)
        assertEquals(100, walkthrough.title.length)
        assertTrue(walkthrough.title.endsWith("…"))
        assertEquals(WalkthroughImportance.NORMAL, walkthrough.stops.single().importance)
        assertEquals("Changes", Walkthrough.normalize(Json.parse("""{"stops":[{"title":"t","hunks":["h1"]}]}"""), files, digest.hunkByAlias).title)
    }

    @Test fun `parses model JSON with or without a code fence`() {
        assertEquals(Json.parse("""{"a":1}"""), Walkthrough.parseJsonObject("```json\n{\"a\":1}\n```"))
        assertEquals(Json.parse("""{"a":1}"""), Walkthrough.parseJsonObject("  {\"a\":1}  "))
        assertNull(Walkthrough.parseJsonObject("{not json"))
    }

    // --- Rendering -------------------------------------------------------------

    @Test fun `renders a Markdown report with anchors exact hunks and escaped model prose`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        val digest = Walkthrough.buildDigest(files)
        val walkthrough = Walkthrough.normalize(Json.parse("""
            {"title":"Reset <b>limits</b>","focus":"See [here](https://example.com).",
             "stops":[{"title":"Values","importance":"key","hunks":["h1","h2","h3"],"explanation":"# not a heading","observations":[]}],
             "minor":"Bumps the lockfile."}
        """.trimIndent()), files, digest.hunkByAlias)
        val markdown = Walkthrough.renderMarkdown(walkthrough, files, WalkthroughReportContext(
            scopeLabel = "Staged changes",
            modelLabel = "openai/gpt-5",
            generatedAt = Instant.EPOCH,
            linkFor = { path, line -> "vscode://file/repo/$path:$line" },
        ))
        assertTrue(markdown.contains("# Walkthrough: Reset &lt;b&gt;limits&lt;/b&gt;"))
        assertTrue(markdown.contains("> See \\[here\\](https://example.com)."))
        assertTrue(markdown.contains("1. [Values](#1-values) · Key change"))
        assertTrue(markdown.contains("## 1. Values"))
        assertTrue(markdown.contains("\\# not a heading"))
        assertTrue(markdown.contains(
            "[**`src/limits.ts`**](<vscode://file/repo/src/limits.ts:1>) · [L1](<vscode://file/repo/src/limits.ts:1>) · [L11](<vscode://file/repo/src/limits.ts:11>)\n\n```diff\n@@ -1,3 +1,4 @@ export function reset()\n const a = 1;"))
        assertEquals(1, Regex("""src/limits\.ts`\*\*\]""").findAll(markdown).count())
        assertTrue(markdown.contains("**`src/new.ts`**"))
        assertFalse(markdown.contains("file:"))
        assertTrue(markdown.contains("Staged changes · 3 files · +4 −3 · openai/gpt-5"))
        assertTrue(markdown.contains(
            "## Also changed\n\nBumps the lockfile.\n\nNot explained above: [`package-lock.json`](<vscode://file/repo/package-lock.json:1>) (generated, not sent to the model)"))
    }

    @Test fun `renders plain labels when no link resolver is available and widens fences`() {
        val files = Walkthrough.parseUnifiedDiff(listOf(
            "--- a/doc.kt", "+++ b/doc.kt", "@@ -1 +1 @@", "-val a = \"```\"", "+val a = \"````\"",
        ).joinToString("\n"), "staged")
        val digest = Walkthrough.buildDigest(files)
        val walkthrough = Walkthrough.normalize(Json.parse("""{"title":"T","stops":[{"title":"Ünïcode & Ω!","hunks":["h1"]}]}"""),
            files, digest.hunkByAlias)
        val markdown = Walkthrough.renderMarkdown(walkthrough, files, WalkthroughReportContext("Staged changes", null, Instant.EPOCH))
        assertTrue(markdown.contains("**`doc.kt`** · L1\n\n`````diff\n@@ -1 +1 @@"))
        assertTrue(markdown.contains("1. [Ünïcode & Ω!](#1-ünïcode--ω)"))
        assertTrue(markdown.contains("Staged changes · 1 file · +1 −1 · "))
        assertFalse(markdown.contains("Also changed"))
    }

    // --- Model selection -------------------------------------------------------

    private val active = WalkthroughModel("openai", "gpt-6-astra", "max")

    @Test fun `prefers the review model with its optional reasoning variant`() {
        assertEquals(WalkthroughModel("openai", "gpt-6-sol", "medium"), Walkthrough.selectModel("openai/gpt-6-sol#medium", active))
        assertEquals(WalkthroughModel("openai", "gpt-6-sol"), Walkthrough.selectModel("openai/gpt-6-sol", active))
    }

    @Test fun `uses the active chat selection when no review model is set`() {
        assertEquals(active, Walkthrough.selectModel("", active))
        assertNull(Walkthrough.selectModel("", null))
        assertEquals(active, WalkthroughModel.fromJson(Json.obj("providerID" to "openai", "modelID" to "gpt-6-astra", "variant" to "max")))
        assertNull(WalkthroughModel.fromJson(Json.obj("providerID" to "openai")))
        assertEquals(Json.parse("""{"providerID":"openai","modelID":"gpt-6-sol"}"""), WalkthroughModel("openai", "gpt-6-sol").toJson())
        assertEquals("openai/gpt-6-astra (max)", active.label)
    }

    @Test fun `treats a malformed review setting as unset`() {
        for (value in listOf("gpt-6-sol", "openai/", "/gpt", "openai/gpt 6", "openai/a#b#c")) {
            assertNull(value, Walkthrough.parseReviewModel(value))
        }
    }

    // --- Service ---------------------------------------------------------------

    private val specification = Json.parse("""{"paths":{"/api/experimental/generate":{"post":{}}}}""")

    @Test fun `service refuses OpenCode v1 without requests`() {
        val service = WalkthroughService({ _, _, _, _ -> error("V1 must not request") }, { 1 })
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        assertEquals("Changes walkthroughs require OpenCode v2.", assertThrows(IllegalStateException::class.java) {
            service.generate(files, Walkthrough.buildDigest(files), "Staged changes", "/repo", null) { false }
        }.message)
    }

    @Test fun `service reads the context limit by key or id and tolerates failures`() {
        val catalog = Json.parse("""{"providers":[
            {"id":"other","models":{"m":{"limit":{"context":1}}}},
            {"id":"openai","models":{"gpt-5":{"limit":{"context":400000}},"alias":{"id":"gpt-4","limit":{"context":128000}}}}]}""")
        val directories = mutableListOf<String?>()
        val service = WalkthroughService({ method, path, _, options ->
            assertEquals("GET /config/providers", "$method $path")
            directories += options.directory
            OpenCodeResponse(catalog)
        }, { 2 })
        assertEquals(400_000, service.readContextLimit(WalkthroughModel("openai", "gpt-5"), "/repo"))
        assertEquals(128_000, service.readContextLimit(WalkthroughModel("openai", "gpt-4"), "/repo"))
        assertNull(service.readContextLimit(WalkthroughModel("openai", "missing"), "/repo"))
        assertNull(service.readContextLimit(null, "/repo"))
        assertEquals(listOf("/repo", "/repo", "/repo"), directories)
        val failing = WalkthroughService({ _, _, _, _ -> throw OpenCodeRequestException("503 Unavailable") }, { 2 })
        assertNull(failing.readContextLimit(WalkthroughModel("openai", "gpt-5"), "/repo"))
    }

    @Test fun `service generates a report through one-shot generation`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        val digest = Walkthrough.buildDigest(files)
        val calls = mutableListOf<String>()
        val service = WalkthroughService({ method, path, body, options ->
            calls += "$method $path"
            if (method == "GET") OpenCodeResponse(specification)
            else {
                assertEquals("/repo", options.directory)
                assertEquals(WalkthroughService.MAX_RESPONSE_BYTES, options.maxResponseBytes)
                assertEquals(WalkthroughService.GENERATION_TIMEOUT_MS, options.timeoutMs)
                val request = body!!.asJsonObject
                assertEquals(Json.parse("""{"providerID":"openai","id":"gpt-5","variant":"high"}"""), request.get("model"))
                assertTrue(request.get("prompt").asString.contains("----- BEGIN UNTRUSTED STAGED CHANGES DIFF -----"))
                val answer = """```json
                    {"title":"Reset limits","focus":"Faster.","stops":[{"title":"Values","importance":"key","hunks":["h1"],"explanation":"Now 3."}]}
                    ```""".trimIndent()
                OpenCodeResponse(Json.obj("data" to Json.obj("text" to answer)))
            }
        }, { 2 }, { Instant.EPOCH })
        val report = service.generate(files, digest, "Staged changes", "/repo", WalkthroughModel("openai", "gpt-5", "high")) { false }
        assertEquals(listOf("GET /openapi.json", "POST /api/experimental/generate"), calls)
        assertEquals("Reset limits", report.title)
        assertTrue(report.content.startsWith("# Walkthrough: Reset limits\n\nStaged changes · 3 files · +4 −3 · openai/gpt-5 (high) · "))
        assertTrue(report.content.contains("**`src/limits.ts`** · L1"))
    }

    @Test fun `service explains unavailable models and missing generation APIs`() {
        val files = Walkthrough.parseUnifiedDiff(gitDiff, "staged")
        val digest = Walkthrough.buildDigest(files)
        val unavailable = WalkthroughService({ method, _, _, _ ->
            if (method == "GET") OpenCodeResponse(specification) else throw OpenCodeRequestException("400 Model unavailable: openai/gpt-5")
        }, { 2 })
        assertEquals("openai/gpt-5 is unavailable for one-shot generation. Pick another model, or set one with Use for reviews in the Models view.",
            assertThrows(IllegalStateException::class.java) {
                unavailable.generate(files, digest, "Staged changes", "/repo", WalkthroughModel("openai", "gpt-5")) { false }
            }.message)
        val missing = WalkthroughService({ _, _, _, _ -> OpenCodeResponse(Json.obj("paths" to Json.obj())) }, { 2 })
        assertEquals("This OpenCode server has no one-shot generation API. Update OpenCode to use walkthroughs.",
            assertThrows(IllegalStateException::class.java) {
                missing.generate(files, digest, "Staged changes", "/repo", null) { false }
            }.message)
        val cancelled = WalkthroughService({ _, _, _, _ -> error("Cancelled generation must not request") }, { 2 })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            cancelled.generate(files, digest, "Staged changes", "/repo", null) { true }
        }
    }
}
