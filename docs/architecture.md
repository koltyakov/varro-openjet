# Architecture

How Varro OpenJet maps upstream Varro onto the IntelliJ Platform.

## The porting decision

Upstream Varro is roughly:

| Layer | Size | Ported? |
| --- | --- | --- |
| `src/webview` (Solid + Tailwind chat UI) | ~80k lines + 17k CSS | **Reused verbatim** |
| `src/shared` (protocol, domain types, helpers) | ~7k lines | **Reused verbatim** by the webview; selectively reimplemented in Kotlin for the host |
| `src/extension` (VS Code host) | ~42k lines | IDE integration and provider quota backend reimplemented in Kotlin |

Reusing the webview is what makes the port tractable, and it is possible because upstream keeps a genuinely narrow host seam. `src/webview` contains no `import 'vscode'` anywhere, and the entire host contract is installed by one inline bootstrap in `src/extension/webview-html.ts`:

```js
const vscode = acquireVsCodeApi();
window.__initialWebviewState = { … };
window.__initialTheme = window.__initialWebviewState.theme;
window.__sendToExtension = (msg) => vscode.postMessage(msg);
window.__vscodeWebviewState = { getState, setState };
```

Host-to-webview traffic is plain `window.postMessage`, which `src/webview/lib/bridge.ts` listens for.

Rewriting the UI in Swing or Compose would have meant reimplementing the transcript virtualization, streaming renderer, Markdown/Mermaid pipeline, composer, model pickers and permission surfaces - and then maintaining two divergent UIs forever. Reusing it means upstream UI work flows into this port through a re-vendor.

## Layout

```
webview/
  vendor/webview     upstream src/webview, unchanged
  vendor/shared      upstream src/shared, unchanged
  src/host-bridge.ts the JetBrains bridge shim
  src/project-storage.ts project-backed browser preferences
  vite.config.mts    builds into src/main/resources/webview/

src/main/kotlin/varro/
  server/            OpenCode process, transport, lifecycle, path identity
  host/              webview host, routing, IDE integration
  host/quota/        native HTTP, credential lookup, quota adapters and parsers
  store/             persistence
  settings/          settings model and UI
  toolwindow/        tool window
  actions/           IDE commands
  protocol/          JSON helpers
```

## Boot sequence

1. The user opens the **Varro** tool window. `VarroToolWindowFactory` asks `VarroProjectService` for a webview surface.
2. `WebviewHost` creates a `JBCefBrowser`, a `JBCefJSQuery` for the inbound channel, and a request handler for assets, then loads `http://varro.localhost/index.html`.
3. The request handler calls back into `WebviewHtml.render(...)`, which generates the page shell with the theme variables, the boot snapshot and the bridge primitive inlined - the same way VS Code inlines them.
4. `src/host-bridge.ts` installs the four globals, then imports the vendored entry point.
5. The webview mounts and sends `ready`.
6. `VarroProjectService` replays status, context, config and persisted state, then calls `ensureServerStarted()`.
7. `OpenCodeServer` probes health, adopts or spawns `opencode serve`, and opens the SSE stream.
8. The webview loads sessions, agents, providers and MCP status through `api/request`.

Server startup stays lazy, exactly as upstream: constructing the service does no OpenCode work, so IDE startup is unaffected and several project windows can share one server.

## Component map

| Upstream | This port |
| --- | --- |
| `extension.ts`, `sidebar-provider.ts` | `host/VarroProjectService.kt` |
| `webview-session.ts`, `sidebar-provider-bridge.ts` | `host/WebviewHost.kt` |
| `webview-html.ts` | `host/WebviewHtml.kt` |
| - (VS Code serves webview assets) | `host/WebviewAssets.kt` |
| - (VS Code injects theme variables) | `host/ThemeBridge.kt` |
| `message-router.ts`, `sidebar-provider-actions.ts` | `VarroProjectService.handleMessage` |
| `rest-proxy.ts` | `host/RestProxy.kt` + `host/OpenCodeHostServices.kt` |
| `util/webview-message.ts` (`isAllowedApiRequest`) | `host/ApiRoutes.kt` |
| `context-provider.ts` | `host/ContextProvider.kt`, `host/TerminalService.kt` |
| `file-search-service.ts`, document providers | `host/EditorIntegration.kt` |
| `open-code-process.ts` | `server/OpenCodeCli.kt`, `server/OpenCodeProcess.kt` |
| `open-code-transport.ts` | `server/OpenCodeTransport.kt` |
| `server.ts`, `server-lifecycle.ts` | `server/OpenCodeServer.kt` |
| `shared/protocol.ts` (event parsing) | `server/ServerEvents.kt` |
| `shared/workspace-path.ts` | `server/WorkspacePaths.kt` |
| `util/opencode-request.ts` | `server/OpenCodeRequestScope.kt` |
| `commit-message-service.ts` | `host/CommitMessageService.kt` |
| `provider-limit-service.ts`, `provider-limits/` | `host/ProviderQuotaBackend.kt` and `host/quota/` |
| Workspace Memento stores | `store/VarroStore.kt` |
| `package.json` `contributes.configuration` | `settings/VarroSettings.kt`, `VarroConfigurable.kt` |

## Webview hosting

**Asset serving.** A `CefRequestHandler` on the browser's own `JBCefClient` intercepts the synthetic origin and answers from plugin resources. A custom CEF *scheme* would have to be registered before the platform initializes CEF, which a plugin cannot reliably order; a per-client request handler works against an already-running CEF and is plain JCEF API.

A synthetic origin is used rather than `loadHTML` because the bundle is a real ES-module graph - dynamic `import()` needs a resolvable base URL - and because a stable origin gives the page a durable `localStorage` partition, which is where upstream's `BrowserPersistence` keeps composer drafts and UI preferences.

**Messaging.** Webview to host is a `JBCefJSQuery`, injected into the page shell as `window.__varroHostSend`. Host to webview is `executeJavaScript` calling `window.__varroReceive`, which the shim re-dispatches as a `MessageEvent`. Outbound messages produced before the document finishes loading are queued and flushed on `onLoadEnd` - the host starts pushing status the moment the server comes up, routinely before first paint.

**Theme.** `ThemeBridge` derives the ~79 `--vscode-*` custom properties the webview reads from IntelliJ UI keys, the editor color scheme and the console ANSI palette. Every upstream variable already carries a CSS fallback and `lib/theme.ts` re-derives readable foregrounds at runtime against whatever background it finds, so the mapping only has to be plausible, not exhaustive. Theme changes are pushed as a variable update rather than a reload, which would discard scroll position and unsent composer text.

## Authorization boundary

`ApiRoutes` is a faithful port of upstream's `isAllowedApiRequest`. Every route states its methods and exactly which query keys it tolerates, because several OpenCode endpoints change scope entirely based on a query parameter. Requests are validated on the **still-encoded** path segments, so `%2f` cannot smuggle a separator into a captured id.

Two request kinds share the channel:

- Paths under `/varro` are the host's own namespace, answered locally from IDE state and the stores.
- Everything else is forwarded to OpenCode after the allowlist approves it.

## Provider quota backend

`OpenCodeHostServices.providerLimit` delegates to the project-owned Kotlin `ProviderQuotaBackend`. It runs polls on virtual threads and uses IntelliJ `HttpRequests`, including the IDE's HTTP proxy and certificate configuration. Requests have connection/read timeouts, bounded response bodies, and disabled redirects.

Metadata reads and xAI refreshed-auth writes go through `OpenCodeTransport`, preserving workspace scope. Credentials come from OpenCode's XDG data directory, provider configuration/environment, or the supported provider credential files. Local Claude credential refresh compares the previous refresh token and replaces the file atomically while preserving unrelated fields. Credentials never enter the webview.

Caches are model/workspace scoped and fingerprint credential identity before reuse. Concurrent requests for the same key share a poll. HTTP 429 errors use exponential backoff, and provider errors can use a last-successful snapshot for at most 15 minutes. Configuration changes retire in-flight generations so their results cannot overwrite refreshed state. Cache coordination is project-local rather than shared through upstream's cross-process snapshot files.

`Json.stringifyMessage` preserves explicit null fields in API responses and quota events. The webview rejects quota windows when required nullable fields such as `limit` and `resetAt` are omitted.

`npm run build` builds only the browser bundle. Kotlin tests cover the quota backend, and `npm run test:host` checks the webview quota event contract. The Kotlin port used [upstream quota sources at revision `6f0d0f9ffd1f`](https://github.com/koltyakov/varro/tree/6f0d0f9ffd1f69290bdcd5c1ab0c7dca6e94c8ef/src/extension) as its reference.

## Simplifications from the VS Code original

These are deliberate, and each has a reason:

**Multi-root workspaces.** VS Code workspaces have several independent folders, and upstream carries substantial machinery to aggregate session catalogs across them and authorize cross-root access. A JetBrains project has one primary root; the catalog is scoped to it plus the project's content roots, which removes the cross-root authorization layer while keeping the same visible behaviour.

**Server ownership leases.** Upstream writes a lease file so several VS Code windows can negotiate which one owns a spawned server, and can take ownership of an orphan. Here, a project owns only the server it spawned; a server already listening is adopted read-only and is never stopped by this IDE. That is the conservative direction - stopping someone else's server would take down their sessions.

**Event routing.** Upstream routes detailed events to the endpoint owning their execution directory and projects safe lifecycle summaries elsewhere. Here an event is forwarded when it belongs to this project's directory or carries no directory at all.

## Threading

The IntelliJ Platform's threading rules do not match Node's single-threaded host, so the boundaries are explicit:

- `JBCefJSQuery` handlers run on a CEF IO thread that must not block, so inbound messages are dispatched to a pooled thread.
- The SSE stream owns a dedicated daemon thread, because reads block.
- REST calls run on whatever thread handles the message; they are already off the EDT.
- Editor and UI work is marshalled with `invokeLater`/`invokeAndWait`; context snapshots are built inside a `ReadAction`.
- Editor context refreshes are coalesced - caret and selection listeners fire per keystroke, and the composer only reads the snapshot when a message is sent.
