package varro.host

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import varro.protocol.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

/**
 * Hosts the Varro webview in a JCEF browser and implements the host side of its
 * bridge.
 *
 * This is the JetBrains replacement for `src/extension/webview-session.ts` plus
 * `sidebar-provider-bridge.ts`. The contract it has to satisfy is narrow:
 *
 *   webview -> host   one JSON string per message, via `JBCefJSQuery`
 *   host -> webview   `window.__varroReceive(payload)`, which the bridge shim
 *                     re-dispatches as the `message` event the webview listens for
 *
 * @param initialStateProvider builds the boot snapshot. Called on every (re)load
 *   so a reload always starts from current state rather than a stale copy.
 * @param viewStateProvider last persisted `vscode.getState()` snapshot.
 * @param onMessage receives parsed webview messages.
 */
class WebviewHost(
    private val project: Project,
    val surface: Surface,
    val viewId: String,
    private val initialStateProvider: () -> JsonObject,
    private val viewStateProvider: () -> JsonObject,
    private val onMessage: (JsonObject) -> Unit,
) : Disposable {

    enum class Surface(val id: String) { SIDEBAR("sidebar"), EDITOR("editor") }

    private val log = logger<WebviewHost>()

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        .setEnableOpenDevToolsMenuItem(true)
        .build()

    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)

    /**
     * Messages produced before the document finished loading. `executeJavaScript`
     * against a document that has not evaluated the bridge shim yet is silently
     * dropped, and the host starts pushing status and context the moment the
     * server comes up - which is routinely before the first paint.
     */
    private val pendingOutbound = ConcurrentLinkedQueue<String>()

    private val loaded = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val messages = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "Varro messages $viewId").apply { isDaemon = true }
    }

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        installMessageChannel()
        installAssetHandler()
        installLoadHandler()
        installThemeListeners()
        ProjectFileDropTarget.install(browser.component, this) { message ->
            dispatchMessage(message)
        }
        browser.jbCefClient.addDragHandler({ cefBrowser, data, _ ->
            val paths = java.util.Vector<String>()
            data.getFilePaths(paths)
            cefBrowser.executeJavaScript("window.__varroNativeDropPaths = ${Json.stringify(paths)};", WebviewAssets.INDEX_URL, 0)
            false
        }, browser.cefBrowser)
    }

    // --- webview -> host ------------------------------------------------------

    private fun installMessageChannel() {
        jsQuery.addHandler { raw ->
            if (!disposed.get()) {
                val parsed = runCatching { Json.parse(raw) }.getOrNull()
                val envelope = if (parsed != null && parsed.isJsonObject) parsed.asJsonObject else null
                if (envelope == null) {
                    log.warn("Ignoring malformed webview message")
                } else {
                    // Preserve write order without blocking CEF's IO thread.
                    // The service dispatches API requests separately so long
                    // requests cannot hold up cancellation or draft persistence.
                    dispatchMessage(envelope)
                }
            }
            null
        }
    }

    private fun dispatchMessage(message: JsonObject) {
        if (disposed.get()) return
        messages.execute {
            runCatching { onMessage(message) }
                .onFailure { log.warn("Webview message handler failed", it) }
        }
    }

    /** JS statements that hand `message` to [jsQuery]; embedded in the page shell. */
    private fun hostSendSnippet(): String = jsQuery.inject("message")

    // --- host -> webview ------------------------------------------------------

    /** Queues an extension message for the webview, flushing once the page is live. */
    fun post(message: JsonElement) {
        if (disposed.get()) return
        val json = Json.stringifyMessage(message)
        if (loaded.get()) sendNow(json) else pendingOutbound.add(json)
    }

    fun post(type: String, payload: Any? = Unit) = post(Json.message(type, payload))

    private fun sendNow(json: String) {
        if (disposed.get()) return
        val cefBrowser = browser.cefBrowser
        // The payload is JSON, so embedding it as a literal is safe; `__varroReceive`
        // re-dispatches it as a MessageEvent with this object as `event.data`.
        val script = "if (window.__varroReceive) { window.__varroReceive($json); }"
        runCatching { cefBrowser.executeJavaScript(script, cefBrowser.url ?: WebviewAssets.INDEX_URL, 0) }
            .onFailure { log.warn("Failed to post message to the Varro webview", it) }
    }

    private fun flushPending() {
        while (true) {
            val next = pendingOutbound.poll() ?: break
            sendNow(next)
        }
    }

    // --- Asset serving --------------------------------------------------------

    /**
     * Serves the bundle from plugin resources.
     *
     * A request handler is used rather than a custom scheme: `CefApp` scheme
     * registration has to happen before the platform initializes CEF, which a
     * plugin cannot reliably order, whereas a per-client request handler works on
     * an already-running CEF and is plain JCEF API.
     */
    private fun installAssetHandler() {
        browser.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?,
                ): CefResourceRequestHandler? {
                    val url = request?.url ?: return null
                    if (!WebviewAssets.isOwnedUrl(url)) return null
                    disableDefaultHandling?.set(true)
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun getResourceHandler(
                            cefBrowser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest?,
                        ): CefResourceHandler {
                            val resolved = WebviewAssets.resolve(request?.url ?: url) { renderDocument() }
                            if (resolved == null) log.warn("Unresolved webview asset: ${request?.url}")
                            return WebviewAssets.Handler(resolved)
                        }
                    }
                }
            },
            browser.cefBrowser,
        )

        // A webview must never navigate away; a popup would strand the Solid app.
        browser.jbCefClient.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?,
                ): Boolean {
                    // Handled as an external link by the bridge shim's click guard;
                    // returning true cancels the popup.
                    return true
                }
            },
            browser.cefBrowser,
        )
    }

    private fun installLoadHandler() {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain != true) return
                    loaded.set(true)
                    flushPending()
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    if (frame?.isMain != true) return
                    log.warn("Varro webview failed to load $failedUrl: $errorText ($errorCode)")
                }
            },
            browser.cefBrowser,
        )
    }

    /** Builds the page shell; invoked by the asset handler on every document load. */
    private fun renderDocument(): String {
        loaded.set(false)
        return runCatching {
            WebviewHtml.render(
                theme = ThemeBridge.current(),
                initialState = initialStateProvider(),
                viewState = viewStateProvider(),
                hostSendSnippet = hostSendSnippet(),
                assetVersion = WebviewAssets.assetVersion,
            )
        }.getOrElse { failure ->
            log.error("Failed to render the Varro webview document", failure)
            WebviewHtml.renderLoading(ThemeBridge.current())
        }
    }

    /** Rebuilds the document from current state. Used after a theme or config change. */
    fun reload() {
        if (disposed.get()) return
        loaded.set(false)
        pendingOutbound.clear()
        browser.loadURL(WebviewAssets.INDEX_URL)
    }

    // --- Theme ----------------------------------------------------------------

    /**
     * Theme changes are pushed as a CSS variable update plus a `theme/update`
     * message, not a reload: a reload would discard the transcript scroll position
     * and any unsent composer text.
     */
    private fun installThemeListeners() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener { applyTheme() },
        )
        connection.subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { _: EditorColorsScheme? -> applyTheme() },
        )
    }

    fun applyTheme() {
        if (disposed.get()) return
        val theme = ThemeBridge.current()
        val declarations = theme.variables.entries.joinToString(";") { (name, value) ->
            "d.style.setProperty('$name', '${value.replace("'", "\\'")}')"
        }
        val script = """
            (function() {
              var d = document.documentElement;
              $declarations;
              var classes = ['vscode-light','vscode-dark','vscode-high-contrast','vscode-high-contrast-light'];
              classes.forEach(function(name) { d.classList.remove(name); document.body.classList.remove(name); });
              d.classList.add('${theme.kind.bodyClass}');
              document.body.classList.add('${theme.kind.bodyClass}');
              document.body.dataset.vscodeThemeKind = '${theme.kind.id}';
            })();
        """.trimIndent()
        val cefBrowser = browser.cefBrowser
        runCatching { cefBrowser.executeJavaScript(script, cefBrowser.url ?: WebviewAssets.INDEX_URL, 0) }
            .onFailure { log.warn("Failed to apply the Varro webview theme", it) }
        post("theme/update", Json.obj("theme" to theme.kind.id))
    }

    // --- Focus and devtools ---------------------------------------------------

    fun requestFocus() {
        if (disposed.get()) return
        runCatching { browser.cefBrowser.setFocus(true) }
    }

    fun openDevTools() {
        if (disposed.get()) return
        runCatching { browser.openDevtools() }
            .onFailure { log.warn("Failed to open Varro webview devtools", it) }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        pendingOutbound.clear()
        messages.shutdown()
        runCatching { Disposer.dispose(jsQuery) }
    }
}
