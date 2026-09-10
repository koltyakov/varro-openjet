# Porting status

What is implemented, what is partial, and what is not started. Upstream Varro's host layer is ~42,000 lines; this is an honest account of how much of it this port covers.

## Implemented

| Area | Notes |
| --- | --- |
| Webview hosting | JCEF browser, asset serving, bridge, boot snapshot, queued outbound messages, devtools action. |
| Theme | ~79 `--vscode-*` variables derived from the IDE LAF, editor scheme and ANSI palette; light/dark/high-contrast; live updates without a reload. |
| OpenCode CLI discovery | PATH plus the global install locations a desktop-launched IDE does not inherit; configured-path handling; install-method detection for repair instructions; version reading. |
| Server lifecycle | Lazy start, adoption of an already-running server, version floor check, port-in-use retry walk, health polling, crash reporting, graceful-then-forced shutdown, restart. |
| Transport | REST with per-route timeouts and workspace scoping; SSE with `Last-Event-ID` resume, jittered exponential backoff and degraded-stream reporting. |
| Event handling | All envelope shapes (direct, `sync` wrapper, versioned names); attention and session-directory caches. |
| Request authorization | Full port of the route allowlist, including query constraints and encoded-separator rejection. |
| Editor context | Active file, selection, unsaved buffer, diagnostics, content roots; coalesced updates. |
| Editor integration | Open file at line, reveal directory, read-only tool output, diff view, file picker, ranked file search, plan documents. |
| Persistence | Permission modes, session models, plan state, model preferences, pinned sessions, queued messages, recycle bin, history scope, view state. |
| Host API namespace | `workspace-file`, `workspace-file/pick`, `workspace-path/resolve`, `plan/open`, `opencode-config`, `session-history-scope`, `session/*` (activate, pin, reorder-pin, permission-mode, rename-if-untitled, delete, diff-summary), `session-trash/*`. |
| Commit messages | Staged-or-unstaged (never mixed), recent-style following, replace confirmation, hidden helper session, never stages or commits. |
| Settings | Full settings page mirroring upstream's `varro.*`, with IDE-following font defaults. |
| Actions | Focus, new session, search, abort, previous/next session, restart server, add to context, devtools, generate commit message. |
| Terminal | Allowlisted setup commands with a clipboard fallback when the terminal plugin is unavailable. |

## Partial

| Area | State |
| --- | --- |
| Automatic permission approval | Deterministic half only: read/search tools are auto-allowed, everything else falls through to `ask`. The model-based judge (`auto` mode's escalation path) is not implemented, so `auto` behaves close to `default` for non-trivial requests. |
| Session diff summary | Files, additions, deletions and duration are computed. Token counts and the nested context breakdown are not. |
| Recycle bin | Entries are recorded and listed, and `restore` clears the tombstone. OpenCode deletes the session for real, so restore does not resurrect it - upstream has the same constraint. |
| Session export | Exports the raw message JSON into a read-only tab rather than upstream's formatted Markdown transcript. |

## Not started

| Area | Why it matters | Notes |
| --- | --- | --- |
| Ralph loops | Plan-driven iteration with verification and repair. | Protocol messages are accepted and answered with an empty state so the UI does not hang; the runner itself (`ralph-runner-core.ts`, ~1k lines plus host wiring) is not ported. |
| Usage reports (`/stats`) | Cross-project token and cost accounting. | Reads OpenCode's retained history; the action reports that it is unavailable and points at `opencode stats`. |
| Provider quota limits | Quota windows and reset times per provider. | Upstream ships per-vendor adapters (`provider-limits/`). Reported as `unsupported`, which the webview renders as "no quota information". |
| Editor-tab surfaces | Chats side by side in editor tabs. | `session/open-in-editor` currently focuses the session in the tool window. `WebviewHost.Surface` already models the distinction. |
| Drag-and-drop attachments | Dropping files and images into the composer. | `files/drop`, `files/drop-content`, `pdfs/store`, `images/store` are not wired to a JCEF drop target. |
| Background CLI auto-update | Upgrading OpenCode while idle. | `OpenCodeProcess.upgrade()` exists and works; the maintenance loop that decides when to call it does not. |
| Status bar widget | Attention indicator when the tool window is hidden. | Failures surface as balloon notifications instead. |
| Queued-message leases | Multi-surface arbitration of queued prompts. | With a single surface, claims are always granted; correct today, insufficient once editor tabs land. |
| Permission rule storage | Session/project allow rules stored by the host. | Returns empty, which the webview reads as "no local rules". Rules configured in OpenCode still apply. |

## Known risks

**JCEF availability.** Some IDE distributions ship a runtime without JCEF, and users can switch to one. The tool window detects this and explains how to fix it rather than showing a blank panel.

**Vendored-webview drift.** The bridge contract is four globals and a `postMessage` shape. If upstream changes it, a re-vendor will produce a blank panel until `src/host-bridge.ts` is updated. The contract has been stable, but it is not a published API.

**Protocol coverage.** The host accepts every `WebviewMessage` type, but some are intentionally inert (`commands/state`, `webview/focus`, `permission/reveal`, `editor/route-changed`). These drive VS Code affordances with no JetBrains counterpart and the webview does not wait on them.

## Testing

`./scripts/build.sh verify` runs the unit tests and the IntelliJ plugin verifier.

**Platform-fixture tests cannot run.** IntelliJ IDEA Community ended at 2025.3, so a
262-targeting build compiles against Ultimate — and `BasePlatformTestCase` fails in
setup there, because the fixture tries to instantiate obfuscated Ultimate-only
extensions that have no usable constructor. That leaves service instantiation
uncovered by automated tests; the first build shipped a crash in exactly that gap
(a collaborator notifying its owner from inside the owner's constructor). The fix
was structural rather than test-driven: collaborators no longer take callbacks in
their constructors, so nothing can fire before the owner is fully built. Current coverage focuses on the two components where a subtle mistake is most damaging:

- `ApiRoutesTest` - the authorization boundary, including traversal and encoded-separator rejection.
- `WorkspacePathsTest` / `OpenCodeRequestScopeTest` - path identity and request scoping, which decide session ownership and event routing.

The webview is not re-tested here; it ships with upstream's own suite, which the vendoring script excludes from the bundle.
