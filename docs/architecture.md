# Architecture

How Varro OpenJet maps upstream Varro onto the IntelliJ Platform.

## Reusing the webview

The port keeps the upstream browser UI and implements the IDE host in Kotlin:

| Upstream layer | This port |
| --- | --- |
| `src/webview`, Solid and Tailwind chat UI | Vendored browser sources |
| `src/shared`, protocol, domain types and helpers | Vendored for the webview; host behavior implemented in Kotlin |
| `src/extension`, VS Code host | Kotlin IDE integration, persistence, session orchestration and provider quotas |

The browser UI talks to its host through globals. Upstream's VS Code bootstrap installs this contract:

```js
const vscode = acquireVsCodeApi();
window.__initialWebviewState = initialState;
window.__initialTheme = window.__initialWebviewState.theme;
window.__sendToExtension = (msg) => vscode.postMessage(msg);
window.__vscodeWebviewState = { getState, setState };
```

Upstream host-to-webview traffic uses `window.postMessage`, which `src/webview/lib/bridge.ts` listens for. The JetBrains bridge delivers the same message events through `window.__varroReceive`.

`webview/upstream.json` selects the sync source and excludes upstream test files. Its current ref is `main`; `webview/vendor/UPSTREAM.json` records the commit copied by the last sync. Normal builds use that committed snapshot. JetBrains adaptations belong in the host and bridge; syncing replaces the vendored directories.

## Layout

```
webview/
  vendor/webview     vendored upstream src/webview
  vendor/shared      vendored upstream src/shared
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
3. The request handler calls `WebviewHtml.render(...)` to generate the page shell with theme variables, the boot snapshot and the bridge primitive.
4. `src/host-bridge.ts` installs the messaging and per-view state channels, project-backed storage, and navigation and drag handling before importing the vendored entry point.
5. The webview mounts and sends `ready`.
6. `VarroProjectService` replays status, context, config and persisted state, then calls `ensureServerStarted()`.
7. `OpenCodeServer` probes health, adopts or spawns `opencode serve`, and opens the SSE stream.
8. The webview loads sessions, agents, providers and MCP status through `api/request`.

Server startup is lazy. Several projects can connect to one server. Automatic startup can be disabled while still allowing connection to an existing server.

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
| Workspace Memento stores | `store/VarroStore.kt`, `store/JsonJournal.kt` |
| Shared model preferences | `store/VarroModelStore.kt` |
| Model assignments | `host/ModelRoutingService.kt` |
| Permission rules and review | `host/PermissionService.kt`, `host/ProjectPermissionConfig.kt`, `host/PermissionJudge.kt` |
| Queued sends and Ralph orchestration | `host/QueuedDispatches.kt`, `host/RalphRunner.kt` |
| Recycle bin and transcript export | `host/SessionTrash.kt`, `host/SessionTranscript.kt` |
| Session summaries and usage reports | `host/SessionSummaryService.kt`, `host/LocalSessionSummary.kt`, `host/UsageReport.kt`, `host/LocalUsageDatabase.kt` |
| `package.json` `contributes.configuration` | `settings/VarroSettings.kt`, `VarroConfigurable.kt` |

## Webview hosting

**Asset serving.** A `CefRequestHandler` on the browser's own `JBCefClient` intercepts the synthetic origin and answers from plugin resources. A custom CEF *scheme* would have to be registered before the platform initializes CEF, which a plugin cannot reliably order; a per-client request handler works against an already-running CEF and is plain JCEF API.

The synthetic origin gives dynamic `import()` a resolvable base URL. `webview.version` contains a bundle hash used for cache invalidation after updates. Browser cache storage is not the persistence backend. The bridge replaces `localStorage` with a project-backed implementation, and drafts use the separate per-view state channel.

**Messaging.** Webview to host is a `JBCefJSQuery`, injected as `window.__varroHostSend`. Host to webview is `executeJavaScript` calling `window.__varroReceive`, which the shim re-dispatches as a `MessageEvent`. Outbound messages produced before the document finishes loading are queued and flushed on `onLoadEnd`.

**Theme.** `ThemeBridge` derives `--vscode-*` custom properties from IntelliJ UI keys, the editor color scheme and the console ANSI palette. Theme changes update variables without reloading the page.

## Persistence and recovery

| State | Owner and storage |
| --- | --- |
| IDE settings and shared model preferences | Application-level `VarroSettings` and `VarroModelStore`, in the IDE configuration's `varro-openjet.xml` |
| Session models, permission modes, pins, plan state, unread state and project UI preferences | Project-level `VarroStore`, in the project's `varro-openjet.xml` storage |
| Drafts and view snapshots | `VarroStore.viewStates`, keyed by view ID, through `host/view-state` |
| Editor chat routes | `VarroStore.editorRoutes` |
| Queued dispatches, Ralph runs and recycle-bin entries | JSON journals under `<IDE config>/varro/<project location hash>/`, with project-store snapshots |
| Attachments | `<IDE system>/varro/attachments/<project location hash>/` |

`project-storage.ts` serves synchronous reads from the boot snapshot and mirrors writes through `host/storage`. The host broadcasts changes to other views. `VarroStore` broadcasts session model and permission-mode changes through selection listeners. `VarroModelStore` uses an application message-bus topic to sync model preferences across projects.

Legacy browser session selections migrate into the project store before boot snapshots are built. Legacy project model preferences migrate into the application store only if shared preferences have not been established.

`QueuedDispatches` journals admission before sending and reconciles dispatches with OpenCode history after reconnecting. It does not automatically retry ambiguous sends. `RalphRunner` journals orchestration state and reattaches when the server becomes available. `SessionTrash` records a session tree before archiving it; restore unarchives it, while permanent deletion removes it from OpenCode. Recycle-bin retention is 7 days, with expiry processed when the bin is listed.

## Model and permission controls

`ModelRoutingService` writes small-model and agent-model assignments through OpenCode's global configuration API. Commit-message and auto-approve model assignments update `VarroSettings`.

`PermissionService` applies session rules to OpenCode before acknowledging a save. `ProjectPermissionConfig` writes project rules to an existing `opencode.jsonc`, otherwise `opencode.json`. It preserves other configuration fields but rewrites the document as JSON, so JSONC comments and formatting are not retained.

`PermissionJudge` handles known read-only tools locally and can review other requests through a hidden child session with tools denied except structured output. It asks on invalid responses or review failures. The model selection checks the configured judge model, OpenCode's `small_model`, then a supplied fallback.

## Session summaries and usage

`SessionSummaryService` tries local SQLite history through `LocalSessionSummary` and can fall back to REST. `SessionSummary` computes session-tree token totals, duration and file changes. `UsageReport` reads retained cross-project history through `LocalUsageDatabase` without starting OpenCode, with a bounded REST fallback when local history is unavailable.

## Authorization boundary

`ApiRoutes` implements the route allowlist corresponding to upstream's `isAllowedApiRequest`. Each route states its methods and accepted query keys, because several OpenCode endpoints change scope based on query parameters. Validation checks encoded path segments, so `%2f` cannot insert a separator into a captured ID.

Two request kinds share the channel:

- Paths under `/varro` are the host's namespace. Handlers use IDE state, stores, local history and OpenCode requests as needed.
- Everything else is forwarded to OpenCode after the allowlist approves it.

## Provider quota backend

`OpenCodeHostServices.providerLimit` delegates to the project-owned Kotlin `ProviderQuotaBackend`. It runs polls on virtual threads and uses IntelliJ `HttpRequests`, including the IDE's HTTP proxy and certificate configuration. Requests have connection/read timeouts, bounded response bodies, and disabled redirects.

Metadata reads and xAI refreshed-auth writes go through `OpenCodeTransport`, preserving workspace scope. Credentials come from OpenCode's XDG data directory, provider configuration/environment, or the supported provider credential files. Local Claude credential refresh compares the previous refresh token and replaces the file atomically while preserving unrelated fields. Credentials never enter the webview.

Caches are model/workspace scoped and fingerprint credential identity before reuse. Concurrent requests for the same key share a poll. HTTP 429 errors use exponential backoff, and provider errors can use a last-successful snapshot for at most 15 minutes. Configuration changes retire in-flight generations so their results cannot overwrite refreshed state. Cache coordination is project-local rather than shared through upstream's cross-process snapshot files.

`Json.stringifyMessage` preserves explicit null fields in API responses and quota events. The webview rejects quota windows when required nullable fields such as `limit` and `resetAt` are omitted.

`npm run build` builds only the browser bundle. Kotlin tests cover the quota backend, and `npm run test:host` checks the webview quota event contract. The Kotlin port used [upstream quota sources at revision `6f0d0f9ffd1f`](https://github.com/koltyakov/varro/tree/6f0d0f9ffd1f69290bdcd5c1ab0c7dca6e94c8ef/src/extension) as its reference.

## Server ownership and workspace scope

**Project roots.** The catalog uses the JetBrains project's primary root and content roots. The history scope can be `directory`, `descendants` or `project`.

**Server ownership.** A project owns only the process it spawned. Varro connects to an existing healthy, compatible server and can send chat and configuration requests to it, but does not stop that process. Managed startup retries subsequent ports after a port-in-use failure. There is no cross-process ownership lease.

**CLI maintenance.** `IdleMaintenance` installs background CLI updates only for managed servers when enabled and idle. Busy sessions, queued messages and active Ralph runs prevent maintenance. The updated CLI is used on the next start.

**Event routing.** Upstream routes detailed events to the endpoint owning their execution directory and projects safe lifecycle summaries elsewhere. Here an event is forwarded when it belongs to this project's directory or carries no directory at all.

## Threading

The IntelliJ Platform's threading rules do not match Node's single-threaded host, so the boundaries are explicit:

- `JBCefJSQuery` callbacks enqueue inbound messages on a per-view single-thread executor, preserving their order.
- The SSE stream owns a dedicated daemon thread, because reads block.
- `api/request` work moves to the IDE pooled executor so REST calls do not block the view's ordered message queue.
- Editor and UI work is marshalled with `invokeLater`/`invokeAndWait`; context snapshots are built inside a `ReadAction`.
- Editor context refreshes are coalesced because caret and selection listeners can fire per keystroke.
- Quota polling uses virtual threads; Ralph orchestration uses its own executor.
