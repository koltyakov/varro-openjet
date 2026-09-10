package varro.host

import com.google.gson.JsonObject
import varro.protocol.Json

/**
 * Generates the page shell the JCEF browser loads.
 *
 * Port of `src/extension/webview-html.ts`. The structure is deliberately the
 * same as upstream's: a loading placeholder that is visible before the bundle
 * evaluates, a pre-bundle failure handler so a bootstrap crash shows a message
 * instead of a blank panel, then the inlined boot snapshot and the module script.
 *
 * Two things differ from VS Code:
 *  - `acquireVsCodeApi()` does not exist, so [hostSendSnippet] (a `JBCefJSQuery`
 *    injection) provides the raw send primitive and `src/host-bridge.ts` builds
 *    the four `window` globals on top of it.
 *  - The theme arrives as real CSS custom properties on `:root`. VS Code injects
 *    those itself; here [ThemeBridge] derives them from the IDE LAF.
 */
object WebviewHtml {

    private val LOADING_STYLES = """
        html, body, #root { width: 100%; height: 100%; margin: 0; }
        html > body { padding: 0; }
        body { background: var(--vscode-sideBar-background, #181818); }
        body.vscode-dark, body.vscode-high-contrast { color-scheme: dark; }
        body.vscode-light, body.vscode-high-contrast-light { color-scheme: light; }
        .varro-startup-loading {
          box-sizing: border-box;
          display: flex;
          min-height: 100%;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          gap: 16px;
          padding: 40px 32px;
          text-align: center;
          font-family: var(--vscode-font-family, system-ui, sans-serif);
          color: var(--vscode-foreground, #cccccc);
        }
        .varro-startup-dots { display: flex; gap: 8px; }
        .varro-startup-dot {
          width: 8px;
          height: 8px;
          border-radius: 9999px;
          background: var(--vscode-focusBorder, #007fd4);
          animation: varro-startup-pulse 1.5s ease-in-out infinite;
        }
        .varro-startup-dot:nth-child(2) { animation-delay: 0.3s; }
        .varro-startup-dot:nth-child(3) { animation-delay: 0.6s; }
        @keyframes varro-startup-pulse {
          0%, 100% { opacity: 0.35; transform: scale(0.85); }
          50% { opacity: 1; transform: scale(1); }
        }
    """.trimIndent()

    private val LOADING_MARKUP = """
        <div class="varro-startup-loading" role="status" aria-label="Loading workspace">
          <div class="varro-startup-dots" aria-hidden="true">
            <span class="varro-startup-dot"></span>
            <span class="varro-startup-dot"></span>
            <span class="varro-startup-dot"></span>
          </div>
        </div>
    """.trimIndent()

    /**
     * Shown while the host resolves the view and restores recovery state, before
     * the real document is generated. Matches `renderWebviewLoadingHtml`.
     */
    fun renderLoading(theme: WebviewTheme): String = """
        <!DOCTYPE html>
        <html lang="en" class="${theme.kind.bodyClass}">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>Varro</title>
          <style>
        :root {
        ${theme.toCssDeclarations()}
        }
        $LOADING_STYLES
          </style>
        </head>
        <body class="${theme.kind.bodyClass}">
          <div id="root">$LOADING_MARKUP</div>
        </body>
        </html>
    """.trimIndent()

    /**
     * The real document.
     *
     * @param theme CSS variables plus the body class the webview's own theme
     *   helpers key off.
     * @param initialState upstream's `InitialWebviewState`, inlined exactly as
     *   VS Code inlines it.
     * @param viewState the last persisted `vscode.getState()` snapshot, which the
     *   bridge shim serves synchronous reads from.
     * @param hostSendSnippet JS statements that forward a string to the JVM. The
     *   `message` identifier is in scope.
     * @param assetVersion contents of `webview.version`, appended as a cache key
     *   because JCEF's disk cache is far more aggressive than VS Code's loader.
     */
    fun render(
        theme: WebviewTheme,
        initialState: JsonObject,
        viewState: JsonObject,
        hostSendSnippet: String,
        assetVersion: String,
    ): String {
        val serializedState = serializeForInlineScript(initialState)
        val serializedViewState = serializeForInlineScript(viewState)
        val cacheKey = if (assetVersion.isBlank()) "" else "?v=$assetVersion"

        return """
        <!DOCTYPE html>
        <html lang="en" class="${theme.kind.bodyClass}">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>Varro</title>
          <style>
        :root {
        ${theme.toCssDeclarations()}
        }
        $LOADING_STYLES
          </style>
          <link rel="stylesheet" href="/webview.css$cacheKey" />
        </head>
        <body class="${theme.kind.bodyClass}" data-vscode-theme-kind="${theme.kind.id}">
          <div id="root">$LOADING_MARKUP</div>
          <script>
        $BOOTSTRAP_FAILURE_HANDLER
            window.__varroHostSend = function(message) { $hostSendSnippet };
            window.__initialWebviewState = $serializedState;
            window.__varroInitialViewState = $serializedViewState;
          </script>
          <script type="module" src="/webview.mjs$cacheKey"></script>
        </body>
        </html>
        """.trimIndent()
    }

    /**
     * Installs a failure handler before the bundle loads, so a bootstrap crash
     * renders a message rather than leaving an empty panel that only an IDE
     * restart recovers from. Mirrors upstream's inline handler, including the
     * `__cleanupVarroBridge` handshake the webview's bridge registers.
     */
    private val BOOTSTRAP_FAILURE_HANDLER = """
            (function() {
              var active = true;
              var handleFailure = function(event) {
                if (!active) return;
                var failure = event;
                if (event && 'reason' in event) failure = event.reason;
                else if (event && 'error' in event && event.error !== undefined) failure = event.error;
                console.error('Varro webview bootstrap failed', failure);
                if (event && event.preventDefault) event.preventDefault();
                clearHandlers();
                try {
                  if (typeof window.__cleanupVarroBridge === 'function') window.__cleanupVarroBridge();
                } catch (error) {
                  console.error('Varro webview bridge cleanup failed', error);
                }
                var root = document.getElementById('root');
                if (!root) return;
                root.replaceChildren();
                var fallback = document.createElement('div');
                fallback.setAttribute('role', 'alert');
                fallback.style.cssText = 'box-sizing:border-box;display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:24px;text-align:center;font-family:system-ui,sans-serif;color:var(--vscode-errorForeground,#f48771);background:var(--vscode-sideBar-background,#181818)';
                var title = document.createElement('strong');
                title.textContent = 'Something went wrong';
                var message = document.createElement('span');
                message.textContent = 'Varro could not start. Reload the tool window to try again.';
                fallback.append(title, message);
                root.append(fallback);
              };
              var clearHandlers = function() {
                if (!active) return;
                active = false;
                window.removeEventListener('error', handleFailure);
                window.removeEventListener('unhandledrejection', handleFailure);
                if (window.__clearVarroBootstrapFailureHandlers === clearHandlers) {
                  delete window.__clearVarroBootstrapFailureHandlers;
                }
              };
              window.addEventListener('error', handleFailure);
              window.addEventListener('unhandledrejection', handleFailure);
              window.__clearVarroBootstrapFailureHandlers = clearHandlers;
            })();
    """.trimIndent()

    /**
     * Escapes the characters that could terminate the enclosing `<script>` or be
     * read as JS line terminators. Port of upstream's `serializeForInlineScript`.
     */
    fun serializeForInlineScript(value: Any?): String =
        Json.stringify(value)
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
            // U+2028/U+2029 are valid inside a JSON string but terminate a line in
            // JS source, so an unescaped one breaks the inline script.
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
}
