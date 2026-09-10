package dev.koltyakov.varrojet

import com.google.gson.JsonObject
import dev.koltyakov.varrojet.host.WebviewAssets
import dev.koltyakov.varrojet.host.WebviewHtml
import dev.koltyakov.varrojet.host.WebviewTheme
import dev.koltyakov.varrojet.host.WebviewThemeKind
import dev.koltyakov.varrojet.protocol.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page shell is the contract between the Kotlin host and the vendored
 * webview. A mistake here produces a blank panel with no error, so the pieces the
 * webview depends on are asserted explicitly.
 */
class WebviewHtmlTest {

    private val theme = WebviewTheme(
        kind = WebviewThemeKind.DARK,
        variables = linkedMapOf(
            "--vscode-editor-background" to "#1e1e1e",
            "--vscode-foreground" to "#cccccc",
        ),
    )

    private fun render(state: JsonObject = Json.obj("theme" to "dark")): String =
        WebviewHtml.render(
            theme = theme,
            initialState = state,
            viewState = JsonObject(),
            hostSendSnippet = "window.cefQuery({request: message});",
            assetVersion = "abc123",
        )

    @Test
    fun `installs every global the webview bridge reads`() {
        val html = render()
        // `src/host-bridge.ts` builds __sendToExtension and __vscodeWebviewState on
        // top of these; the vendored bridge then reads them directly.
        assertTrue(html.contains("window.__varroHostSend"))
        assertTrue(html.contains("window.__initialWebviewState"))
        assertTrue(html.contains("window.__varroInitialViewState"))
    }

    @Test
    fun `loads the bundle as a module with a cache key`() {
        val html = render()
        assertTrue(html.contains("""<script type="module" src="/webview.mjs?v=abc123">"""))
        assertTrue(html.contains("""href="/webview.css?v=abc123""""))
    }

    @Test
    fun `applies the theme as css variables and a body class`() {
        val html = render()
        assertTrue(html.contains("--vscode-editor-background: #1e1e1e;"))
        assertTrue(html.contains("""class="vscode-dark""""))
        assertTrue(html.contains("""data-vscode-theme-kind="dark""""))
    }

    @Test
    fun `installs the bootstrap failure handler before the bundle`() {
        val html = render()
        val handlerAt = html.indexOf("__clearVarroBootstrapFailureHandlers")
        val bundleAt = html.indexOf("webview.mjs")
        assertTrue("failure handler must precede the bundle", handlerAt in 0..<bundleAt)
    }

    @Test
    fun `escapes payload characters that would break out of the inline script`() {
        val hostile = Json.obj(
            "theme" to "dark",
            // A naive serializer would emit a literal </script> and end the block.
            "title" to "</script><img src=x onerror=alert(1)>",
        )
        val html = render(hostile)
        assertFalse(html.contains("</script><img"))
        assertTrue(html.contains("\\u003C/script\\u003E"))
    }

    @Test
    fun `escapes JS line terminators that are legal in JSON`() {
        // U+2028 and U+2029 are valid inside a JSON string but terminate a line in
        // JS source, so an unescaped one is a syntax error in an inline script.
        val serialized = WebviewHtml.serializeForInlineScript(mapOf("text" to "a\u2028b\u2029c"))
        assertTrue(serialized.contains("\\u2028"))
        assertTrue(serialized.contains("\\u2029"))
        assertFalse(serialized.contains('\u2028'))
        assertFalse(serialized.contains('\u2029'))
    }

    @Test
    fun `the loading shell renders without a bundle`() {
        val html = WebviewHtml.renderLoading(theme)
        assertTrue(html.contains("varro-startup-loading"))
        assertTrue(html.contains("--vscode-editor-background: #1e1e1e;"))
    }
}

/** Asset resolution is also the traversal boundary for page-supplied URLs. */
class WebviewAssetsTest {

    private fun resolve(url: String) = WebviewAssets.resolve(url) { "<html></html>" }

    @Test
    fun `serves the generated shell for the index`() {
        assertNotNull(resolve("${WebviewAssets.ORIGIN}/index.html"))
        assertNotNull(resolve("${WebviewAssets.ORIGIN}/"))
    }

    @Test
    fun `rejects traversal out of the bundle`() {
        // Without this, `getResourceAsStream` would happily resolve out of the
        // bundle and into the rest of the plugin jar.
        listOf(
            "${WebviewAssets.ORIGIN}/../META-INF/plugin.xml",
            "${WebviewAssets.ORIGIN}/chunks/../../META-INF/plugin.xml",
            "${WebviewAssets.ORIGIN}/..%2fMETA-INF/plugin.xml",
        ).forEach { url ->
            val resolved = resolve(url)
            assertTrue("expected $url to be refused", resolved == null || resolved.mimeType == "text/html")
        }
    }

    @Test
    fun `only claims its own origin`() {
        assertTrue(WebviewAssets.isOwnedUrl("${WebviewAssets.ORIGIN}/webview.mjs"))
        assertFalse(WebviewAssets.isOwnedUrl("https://example.com/webview.mjs"))
        assertFalse(WebviewAssets.isOwnedUrl(null))
    }
}
