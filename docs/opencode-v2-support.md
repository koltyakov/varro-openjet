# OpenCode V2 support

OpenJet supports V1 from 1.16.0 and V2 from 2.0.5. The webview and shared sources were synced from Varro's `main` branch at `5ba2e501dd4b`, version 0.36.2. Tested baselines are V1 1.19.6 and V2 2.3.2. The Kotlin adapter handles released-server contracts, including generated skill, shell and synthetic transcript records. Synthetic text appears as automatic-action notices, except goal kickoffs, which appear as user prompts. Canonical subagent completion metadata produces linked subagent activities. Pending and running V2 patch calls render as active edits. Assistant history preserves automatic retry metadata for recovery notices. History attachments use deferred references with Kotlin-generated thumbnails and on-demand original reads. Chat annotations retain bounded display context before deferral. [Session cleanup and usage export](session-cleanup.md) preserve statistics and optional parent transcripts before confirmed deletions.

V2 sessions support `/pause` and a Resume action in the transcript. Pausing parks queued messages and interrupts with `resume=false`. Pause markers persist in the shared Varro session annotations, and session summaries and usage reports exclude paused time. Commit-message generation uses the stateless endpoint when the server advertises it, falling back to a helper session when the endpoint or selected model is unavailable before generation starts.

When the native goal plugin is available, the composer shows its active, paused
or complete goal with Pause, Resume and Clear controls. Completed goals show a
Close action that uses the same native clear command. Goal inspection uses a
location-scoped read-only RPC; controls use the existing native `goal` command.
Kickoffs retain native message/part identities and own subsequent assistant
replies, including inbox delivery. Internal continuations stay hidden. Older
servers and locations without the plugin keep the row hidden without raising the
connection support floor.

Optional follow-up suggestions use V2 stateless generation after a completed Build response. They are enabled by default, with an application-only opt-out and model override in Settings > Tools > Varro > Models. The host verifies the source against idle server history and persists per-turn admission before inference. History reads are cache-only, and there is no helper-session fallback. Closing the originating editor leaves an admitted, bounded generation running while its project host remains alive. Other hosts wait at most 30 seconds for the shared result. Whole-host shutdown does not recover unfinished requests, and expiry of the 35-second pending grace never releases admission or permits another billed attempt. Dismissal and accepted new prompts retire stored suggestions without permitting regeneration. Clicked-action labels survive native message history and pending inbox projection. The action menu can start a background fork without selecting it or changing the main draft. Its session link is persisted only for a matching cached action and current source, with both sessions verified in the same workspace.

Auxiliary prompt tracing is an application-only opt-in, disabled by default. `PromptTrace.kt` adds the upstream `VARRO_TRACE_V1` marker to follow-ups, permission helpers/native decisions, commit generation, side questions, background-process judging and direct completion. Source ancestry uses at most 16 read-only requests, a shared two-second deadline and 64 KiB responses. Failed ancestry omits the root; cancellation still propagates. Workspace-only requests send a SHA-256 identity, never a raw directory. There is no ancestry cache. Ordinary chat, commands, tools and OpenCode-owned titles remain unchanged. OpenJet's existing fallback rename uses the supplied title, not auxiliary inference, so there is no host title prompt to trace. A compatible proxy must strip the marker, and live proxy accounting has not been verified.

## Connection and startup

The webview now emits validated static module-preload hints and caches versioned assets. Historical sync reviews below record the absence of those optimizations at the time of each review. Windows process-inspector warm-up remains a separate difference. Both API families now enforce request deadlines through response-body reads, including cancellation after headers arrive.

`OpenCodeTransport` validates JSON health responses before selecting the protocol. V2 uses `/api/info`, with `/api/status` for 2.0.5. V1 uses `/global/health`. An HTML fallback page is not a healthy API response.

OpenJet prefers the private service registration under Varro's shared `servers/opencode-service/opencode/service.json`. With no explicit command and before private selection, it can also adopt a live global service registered in `$XDG_STATE_HOME/opencode/service.json`. It checks the loopback URL, PID and server version. New managed V2 launches use `--service` and a process-only `XDG_STATE_HOME` pointing to that private service directory, separate from Desktop's registration. Configuration, provider credentials and session history keep their existing locations. Nested tool CLIs inherit the private service. Do not execute the same session concurrently in independent servers.

Startup waits for private registration credentials before probing new V2 launches, within the same cancellation-aware health deadline. Verified private selection survives lease/marker restoration and does not fall back to Desktop on recovery or restart. Existing running global servers are reused without migration; their next managed relaunch uses the private service. Managed launches redact generated passwords before retaining startup output. HTTP requests and SSE use the same host-only authorization, including `OPENCODE_SERVER_PASSWORD` and `OPENCODE_SERVER_USERNAME` for external servers.

New managed V2 processes inject `websearch.provider: random` only if global, ancestor, workspace, inline and explicit configuration contain no preference and the native read-only `kv` lookup finds no `websearch:provider` selection. Explicit `false` and `null`, malformed/unreadable config and unreadable databases suppress the default. Only the current process's injected file is reconciled before `/global/dispose`; user config, saved choices, external services and other hosts' files are not rewritten. Startup catalog retries live in the unchanged vendored loaders. `/varro/startup-diagnostics` concurrently reads `/api/plugin` and `/api/mcp` within one second and returns at most five sanitized names and timeout flags, never addon errors, arguments or URLs.

Recovering registered windows acquire the shared launch claim before reading registration or
probing health, so an owner's replacement credentials are fully published first. Explicit V2
service discovery can recover ownership of a moved-port replacement using an exact matching
password from a private retired Varro lease. Recovery verifies the sole listener, executable,
birth identity, account evidence and authenticated HTTP PID, with `/api/status` fallback for
2.0.5. It rejects known foreign users, preserves the original coordination key, and publishes
a fresh owner without the retired injected config. Uncertain identity and credential-only
companions retain attach-only behavior and cannot grant restart or maintenance rights.

Executable paths can differ when fresh filesystem identities prove they are the
same regular file, including macOS hard-link aliases. Missing file evidence does
not establish equivalence. Listener and process-birth checks remain required.

After an authentication failure, OpenJet retries credentials stored for the server URL in the IDE password safe, then prompts for a username and password if needed. It saves credentials only after a successful health check. Cancellation and failed verification leave the server disconnected.

The default CLI search prefers `opencode2` over `opencode`. An explicit command takes precedence. Package-manager updates use `@opencode/cli` for V2 and `opencode-ai` for V1.

## Adapter responsibilities

- `OpenCodeV2Adapter.kt` translates catalogs, configuration reads, session operations, prompts, helper generation, permissions, forms, provider authentication and MCP operations.
- `OpenCodeV2Projection.kt` maps native history into Varro messages and parts. Control records update selection context without becoming transcript rows. Skill and shell records appear as tool activity.
- `OpenCodeV2Events.kt` maps native SSE events into the shared webview event vocabulary. History and streamed content use the same part IDs.
- `OpenCodeV2BackgroundWork.kt` tracks background work through execution completion and the follow-up turn. On 2.2.6+, `OpenCodeV2BackgroundJobs.kt` reads native shell, subagent, image and Code Mode jobs, including completed/error results awaiting consumption. Native ancestry assigns descendant work to same-workspace ancestors. Cancelled jobs and saved shell services do not keep sessions waiting. Older/unidentified servers and missing job routes retain shell tracking; other failures preserve pending state and surface the error. Snapshots reconcile with newer events and requests. Abort retains the existing shell/session cancellation APIs.
- `OpenCodeV2BackgroundServices.kt` persists service choices under `background-services/` in the shared V2 state directory. Services remain visible without keeping a session busy, and ordinary abort does not stop them. `BackgroundProcessJudge.kt` uses the session model and stateless generation to classify lifetime, with a 20-second deadline and at most four concurrent reviews per adapter. Invalid verdicts preserve the current choice. Reviews run again after 5, 10, 20 and 30 minutes, then every 30 minutes; manual choices always win.
- `OpenCodeV2SessionState.kt` persists Varro metadata and fallback timestamps for older servers. Capable servers use native archive state without migrating or deleting old annotations. These files are compatible with Varro's annotations.

Background-process list, stop, service-choice and output routes check session ownership before exposing or mutating a shell. List responses exclude private metadata and log paths. Initial log reads probe size and return only the last 64 KiB; subsequent reads use byte cursors with the same chunk limit. Saved choices use Varro-compatible owner-directory locks and atomic writes. The process dialog stops directly through `DELETE /api/shell/:id`, without submitting a chat prompt or interrupting unrelated work. Released 2.2.6 has no cancellation-reason or silent-stop option. Stopping an agent-owned shell can emit a synthetic `Shell.NotFoundError` completion notice and resume the assistant through OpenCode's normal completion flow. Hiding that notice would not prevent backend execution; silent cancellation needs an upstream shell/job notification change.

History reads include admitted user inputs still in the inbox, marked with their queue or steer delivery mode. Pagination filters control records and reads preceding context to recover assistant parent IDs. Aggregate response budgets and repeated-cursor checks bound those reads.

Resuming pending steering uses `/api/session/:id/interrupt?resume=true`, not a prompt wake.
It consumes existing steering without admitting a new message or unpausing queued input.

Interrupted idle records remain in history with `MessageAbortedError`, including interruptions
before the first assistant response. Live execution interruptions emit the same error before
idle. Unread interruptions therefore retain red session markers after reload without appearing
as successful completions or provider failures.

Text and reasoning use separate type-local part ordinals so streamed content reconciles with interleaved history. Windows location requests uppercase absolute drive letters. Directory headers encode Unicode and percent escapes, with additional protection for V1's double-decoded directory query.

Authentication forms preserve prompt conditions, defaults and hidden fields. The UI hides those fields, while the adapter submits applicable defaults and converts boolean and numeric answers for OAuth and API keys. OAuth callbacks identify attempts by ID and workspace. Cancellation stops polling and attempts server-side cleanup. Configured model costs use the same pricing shape as catalog models, and partial limits preserve catalog values. Local provider-disable policies and permission overrides target the workspace's `.opencode` directory when an ancestor has a `.opencode` config.

Provider and MCP authentication preserve native `paste: true` support. These
automatic attempts accept a browser redirect URL unchanged through `code`.
Provider submission-only callbacks never poll, consume or cancel the original
attempt, even after rejected/cancelled submissions. The original callback still
owns completion and cleanup. Older servers retain their existing code/auto flows.

On V2 2.1.2 and newer, native catalog exclusions for `opencode` and `opencode-go`
are authoritative. Configured-model fallback cannot restore excluded free models.
Returned models still receive configured overrides; custom providers and older
servers retain fallback. The policy uses validated health and clears on protocol reset.

Both V2 provider catalogs omit Flex-tier and GPT Cyber models when their effective
endpoint is the Codex subscription route on `chatgpt.com` or `chat.openai.com`.
The filter runs after legacy configured-model restoration and before default
selection. It follows model/provider body and settings overrides, model aliases,
and OpenAI's subscription redirect for API-origin model overrides. Standard and
priority models, API-key endpoints and unrelated custom endpoints remain
available. Changing the endpoint refreshes these choices without rewriting user
configuration or the native catalog.

OpenCode 2.0.25 external credential methods retain their catalog position alongside key and OAuth methods. The adapter posts the chosen method ID and typed form answers to `/api/integration/:id/connect/external`, then reports completion only after acknowledgement. It does not start or poll an OAuth attempt. Custom string fields remain text inputs even when the server supplies suggested options. Older servers keep their existing key and OAuth flows.

Ordinary sends in Default mode preserve session-scoped Always approvals; explicit mode changes can still reset rules.

Permission and form replies retain the owning session. The adapter removes pending requests only after the server acknowledges the reply. Native permission configuration keeps its ordered `permissions` array. Model routing edits preserve native `agents` keys when the target file uses V2 configuration.

V2 session/project/global approvals open a resource-pattern review. Saving keeps
the pending request's action and owning session authoritative. Project and global
config approvals reply once, without creating a durable database approval or
restarting the server. Global rules require a local V2 connection; attach-only
connections cannot edit host files. New V2 configs use native permission arrays,
including URL-specific WebFetch patterns, while existing legacy syntax stays
unchanged. Dirty documents and concurrent config changes reject writes.

Saved V2 approvals are persistent. The Permissions screen separates observed
approvals from other native rows with unknown origin. Unknown rows cannot be
edited or silently adopted; explicit confirmed removal is available. Every
mutation requires a fresh valid project snapshot and the same connection. A
successful removal requires a second list proving the exact ID is gone. Cached
observations remain visible with a stale warning but cannot authorize mutations.

Native session forms retain field keys, typed values, conditions, defaults, hidden
fields, constraints and tool attribution. Replies use `kind: native-form`, the
owning `sessionID` and a keyed `answer`. Positional answers from stale views are
rejected without acknowledging the form. Unknown or malformed fields remain
visible but cannot be answered here. Concurrent replies are rejected, and failed
native validation leaves the form pending. Settled events exclude answer payloads.

Location-owned MCP forms use `sessionID: global`. They stay out of session-question
snapshots, events and reply state without being answered or cancelled. Use an
OpenCode client with location-scoped form UI to answer them.

Child-only Stop and parent background handoff require native child identities,
matching workspace directories and advertised OpenAPI support. Stop uses the
child's `interrupt?resume=false`, never the root abort or shell cleanup. Handoff
requires verified running foreground subagent progress and newly admitted native
guidance before reporting success. Requests have a 15-second total deadline,
bounded response bodies and connection-generation checks. V1 exposes no controls.

V2 owns execution recovery. Automatic interrupted-recovery requests are skipped
without sending a continuation prompt, even when the process-local active list
is empty. Lost-shell cancellation notices appear from the native inbox before
delivery and retain their identity when they enter history.

PDF prompts require valid bounded data URLs and authoritative advertised PDF
input support before model changes, revert commits or submission. Unknown and
unsupported models fail explicitly. Configured commands and one-shot generation
cannot accept PDFs. Native file picks preserve mixed PDF/file order and report
invalid PDFs rather than attaching them as ordinary text context.

V2 has no session-sharing or LSP service. Sharing controls are hidden for V2 sessions. Tail deletion uses file-preserving staged revert and commit; arbitrary single-message deletion returns an error. Unsupported operations report an explicit error.

Older V1 servers can omit MCP tools when discovery spans multiple pages. Upstream's published 1.16.0 check confirms that server ignores `nextCursor`; upgrade the server to the tested V1 baseline for that case. This server-side limitation does not change OpenJet's connection support floor.

### Side questions and native commands

`POST /session/:id/side-question` is a narrow V2-only host action. `SideQuestions.kt`
checks the open project directory and running connection, bounds responses to
512 KiB, and enforces a 35-second cancellation-aware deadline through response-body
reads. Restarted connections reject late answers. `OpenCodeV2SideQuestions.kt`
validates an 8,000-character question, answers up to 64,000 characters, fewer than
12 previous exchanges and 128,000 total context characters. The adapter rechecks
the canonical session ID and location before `/api/session/:id/generate`.
It sends only a prompt, with no model/agent changes, inbox admission, helper session
or fallback. Dialog cancellation never calls session interrupt. Follow-up history
stays in the dialog and enters subsequent generation prompts as context only.

Command discovery reads `/api/command` and `/api/skill`, without reconstructing
commands from configuration templates. Native commands win name collisions and
carry `source: command`; skills retain their content. `command.updated` and
`skill.updated` events refresh the workspace catalog. Execution still uses
OpenCode's native command route, so the server owns delegation and precedence.
Synthetic completion records expose only verified child IDs, terminal states,
descriptions and agent names from native metadata. Prompt text never establishes
child attribution.

## V1 history import

Use **Tools > Varro > Import OpenCode v1 Session into v2** while connected to V2. Import reads the local database through a read-only SQLite transaction. It honors `OPENCODE_DB`, otherwise using the standard XDG OpenCode database.

The importer copies the selected conversation and same-workspace descendants with new session and message IDs. It retains original records in import metadata, converts embedded images and completed tool output, and records unfinished tools as interrupted results. External file references stay in the preserved records and appear as attachment labels. The import does not call prompt or tool-execution endpoints. New copies use destination permission defaults.

Limits are 100 sessions, 10,000 messages and 100,000 parts per session, 32 MiB of history, and a ten-second SQLite query deadline. If a tree import fails, OpenJet deletes copies already created by that import and reports cleanup failures.

Usage reports read both V1 and V2 tables. For an imported conversation and its original, the report selects the newer complete session rather than counting both copies.

When migration preserves a message ID but replaces its completion time, reports recover the original completion time only if the session identity, creation time, provider and model match. Token counts still come from the selected session.

## Upstream sync review, 2026-10-12

Reviewed `95816b85f287f2468a1858f0820d3013ece4598e` through
`a1dd0553c46c655ae8412b72dcb712fe8cb8c6c9`, including all extension changes,
shared sources, and `package.json`. Shared sources and contributed configuration
did not change. No new host messages, endpoints, or browser dependencies were added.
The UI sync includes queue-helper initialization, wrapped-link caret positioning,
and three-line prompt headings. DOMPurify, Marked, and Vitest versions and the
lockfile were refreshed with the pinned Node/npm toolchain.

The vendor refresh does not implement the following host differences. Backend
changes remain pending user selection.

| Behavior | Upstream files and contract | OpenJet implementation and user impact |
| --- | --- | --- |
| Missing static chunk preloads | `vite.config.mts` emits `webview.imports.json`; `src/extension/sidebar-provider-bridge.ts` validates its chunk paths; `src/extension/webview-html.ts` emits `modulepreload` links without changing hashed URLs. | `webview/vite.config.mts` emits only the asset version; `src/main/kotlin/varro/host/WebviewHtml.kt` emits no module preload hints. Initial dependency downloads do not receive upstream's early-loading optimization. Startup performance in JCEF was not measured. |
| Missing Windows inspection warm-up | `src/extension/extension.ts` starts `prepareProcessInspection()` without awaiting it; `src/extension/process-inspection.ts` prepares the persistent Windows helper without reading or authorizing a PID. | `ProcessIdentity` in `src/main/kotlin/varro/server/ServerOwnership.kt` launches PowerShell commands on demand and has no persistent helper or preparation hook. Windows startup does not overlap helper preparation with view setup. This is a Windows-only performance difference, not evidence that listener authorization is missing. |
| Partial persisted V2 failure parity | `src/extension/opencode-v2-projection.ts` now prefers normalized `message.error` over live context for failed idle records; `src/extension/opencode-v2-transcript.test.ts` checks history, direct-message reads, and stale-context precedence. Relevant native routes are `/api/session/:id/message` and `/api/session/:id/message/:messageId`, projected to `/session/:id/message` and `/session/:id/message/:messageId`. | `src/main/kotlin/varro/server/OpenCodeV2Projection.kt` already prefers persisted errors, but keeps native error type names instead of mapping authorization failures to `ProviderAuthError`, does not infer 401/403 from authorization messages, and does not apply upstream's 16 KiB message bound or invalid-message fallback. Diagnostics with an explicit status are retained, but error labels and authentication classification can differ when status is only in the message. The new persisted-error cases have no equivalent focused Kotlin regression coverage. |
| Older status baseline and unverified 2.0.27 behavior | `package.json` updates `@opencode/client` to `^2.0.27`; `docs/opencode-v2-support.md` and `src/extension/opencode-v2.integration.test.ts` record 2.0.27 custom-model one-shot generation without a helper session. | `src/main/kotlin/varro/toolwindow/OpenCodeStatusPresentation.kt` still uses 2.0.26 for update hints. `src/main/kotlin/varro/host/CommitMessageService.kt` supports stateless generation, but the Kotlin flows were not run against released 2.0.27 during this sync. Users on 2.0.26 do not receive the newer baseline hint; 2.0.27 compatibility remains unverified here. |

Other extension diffs adjust tests only. The new export regression verifies complete
CLI stdout accumulation; OpenJet's `VarroProjectService.exportSession` uses API-backed
Markdown through `SessionTranscript.kt`, so that stdout-specific regression does not
apply. The older quota differences in [architecture](architecture.md#remaining-quota-parity-differences)
remain outside this revision's changes.

Vendor integrity, all 89 browser host tests, TypeScript checking, browser build, and
`./gradlew check buildPlugin` passed. No sandbox IDE flow, Windows/Linux startup,
live provider, or released OpenCode 2.0.27 verification was performed. These checks
do not establish full backend parity.

## Upstream sync review, 2026-10-13

Reviewed `9715915d2b03f557f6d1ea585cf038d8b90946f1` through
`67a4003fdaaf7f6731e7ee1b4aa540602db4b4fc`, including all extension changes,
shared sources, and `package.json`. Shared sources, host messages, HTTP routes,
and contributed settings did not change. The UI refresh includes image-copy
feedback, toast stacking/spacing, and composer text/placeholder styling. Mermaid
was updated to 12.2.0 and the lockfile regenerated with the pinned Node/npm versions.
No new browser dependencies were required.

The initial vendor-only refresh left the following differences. The same sync now
includes their host alignment and released Kotlin checks.

| Behavior | Upstream files and contract | OpenJet implementation and user impact |
| --- | --- | --- |
| Branded editor-tab icon | `src/extension/sidebar-provider.ts` uses `assets/icon.png` for newly created and restored editor chat panels instead of the `chat-sparkle` theme icon. | `src/main/kotlin/varro/host/VarroChatEditor.kt` now gives every `VarroChatFile` a branded synthetic file type, with 16-pixel light/dark icons. New, restored, and renamed chat files share the icon. Regression tests cover file-type identity and resources; actual IDE tab rendering was not verified. |
| OpenCode status baseline | `package.json` updates `@opencode/client` from `^2.0.27` to `^2.0.28`; upstream status hints derive the tested V2 version from that manifest. | `src/main/kotlin/varro/toolwindow/OpenCodeStatusPresentation.kt` now uses 2.0.28. Users on 2.0.26 and 2.0.27 receive the update hint. Sync records both SDK/client specifiers in `webview/vendor/UPSTREAM.json`; a Kotlin test checks those against status hints so later syncs cannot silently leave them behind. |
| Released OpenCode 2.0.28 compatibility | Upstream `docs/opencode-v2-support.md` records the 2.0.28 release review and TypeScript published-server matrix. | OpenJet independently passed `OpenCodeV2IntegrationTest` and `OpenCodeManagedServiceIntegrationTest` against the exact published 2.0.28 binary. Both suites ran without skips, with fixture-only HOME/XDG/database/server-state roots and a local deterministic provider. Evidence is `build/opencode-compatibility/2.0.28/verification.json`. |

The OpenCode release review covered 2.0.26 through 2.0.28, `8059cdcbdf` through
`c5aabae288`, including HTTP/OpenAPI handlers, protocol/schema, SSE, configuration,
provider/model changes, and CLI/service behavior. No consumed HTTP path or method
was removed. OpenAPI regeneration adds the external-auth operation already
supported by the Kotlin adapter, and explicit 404 declarations. Project archival
and PTY transport changes do not require OpenJet adapters. Optional provider
compatibility, cache-format, and request-encoding settings remain server-owned
and survive native configuration reads/edits.

The release persists execution failures in idle records. Kotlin already preferred
saved errors, but its normalization differed from Varro. `OpenCodeV2Projection.kt`
and `OpenCodeV2Events.kt` now infer 401/403 from authorization messages, bound
diagnostics to 16 KiB, reject malformed errors before falling back to live context,
and classify failed idle records as `ProviderAuthError` or `UnknownError`. Regression
tests preserve persisted-error precedence, aborted records, and failure-before-idle
event ordering. This resolves the persisted-error gap in the historical review above.

The first released run caught an obsolete fixture assumption about custom-model
one-shot generation. The fixture now supplies a native V2 provider alongside its
legacy project configuration, matching the released contract. On 2.0.27 and newer
it verifies exactly one provider call and no helper session; older releases retain
the unsupported-model fallback assertions. The existing one-shot implementation
needed no change.

VS Code-specific changes do not require JetBrains ports. `package.json` lowers the
VS Code floor to 1.107 and registers the chat view in the primary container.
`src/extension/extension.ts` adds Trae fork detection and one-time primary/secondary
sidebar placement. OpenJet instead registers a right-anchored native tool window
in `src/main/resources/META-INF/plugin.xml`; the IntelliJ Platform owns saved
placement. Its IDE support floor remains 252. Upstream lint/type packages and the
Docker example's OpenCode version are not browser dependencies or Kotlin adapters.

After alignment, vendor integrity, all 89 browser host tests, TypeScript checking,
and `./gradlew check buildPlugin verifyPlugin` passed. The full Kotlin run had 497
passed tests and three optional skips; both released 2.0.28 suites passed separately
without skips. All four compatibility-helper regression tests passed. Plugin
Verifier reported compatibility across all five configured IDE builds, with only
the existing deprecated/experimental API warnings.

No required host or consumed-contract gap remains in this Varro revision's reviewed
changes. No live provider, interactive OAuth, Windows/Linux native run, sandbox UI
flow, or image clipboard verification was performed. Earlier review entries above
are historical; their static-preload and Windows inspection warm-up differences
remain outside this revision's changes. These limits do not establish full backend
parity.

## Upstream sync review, 2026-10-14

Reviewed `67a4003fdaaf7f6731e7ee1b4aa540602db4b4fc` through
`d42fc672073f7498245ad17d8b815b094cbdc414`, Varro 0.33.4. The review covers
all changed extension sources, shared protocol, contributed configuration and
dependencies. Browser dependency versions and the pinned Node/npm toolchain
did not change; no new browser package was required.

| Upstream change | Kotlin alignment and regression coverage |
| --- | --- |
| `src/extension/remote-images.ts`, `rest-proxy.ts`, `extension-config.ts`, `webview-session.ts`; `POST /varro/remote-image` and `remoteImages` config | `RemoteImages.kt`, `RestProxy.kt`, `ApiRoutes.kt`, `VarroSettings.kt` and `VarroProjectService.kt` implement IDE-local off/ask/on consent, host-side DNS-pinned HTTPS, public-address checks on every redirect, no ambient auth/cookies/referrer/proxy, two distinct concurrent downloads, shared-request cancellation, a 15-second deadline including DNS waits, an 8 MiB download cap and existing bounded thumbnail conversion. Configuration changes and disposal cancel pending work. `RemoteImagesTest` covers these policies, mixed DNS answers, cache hits, response validation, bounds, cancellation and unresolved DNS. |
| `src/extension/commit-message-service.ts`, `commands.ts`, `sidebar-provider.ts`; `commitMessage.rules` and repository-scoped cancellation | `CommitMessageService.kt`, `CommitMessageGenerations.kt`, `GenerateCommitMessageAction.kt` and `CommitMessageRules.kt` add application defaults and IDE workspace repository overrides. The native toolbar action switches to cancellation, with an explicit repository choice for aggregate commit panels. Cancellation covers Git preparation, HTTP generation, polling and final application; cancelled helper sessions are aborted and deleted. Stale attempts cannot release retries. Rules enter both stateless and helper prompts without replacing grounding or no-tools constraints. Focused service/state tests cover both paths and cancellation. |
| `src/extension/rest-proxy.ts`; missing-location session list and status reads | `WorkspaceDirectoryErrors.kt` and `RestProxy.kt` distinguish directory-specific 404s from missing sessions. Paginated missing-folder reads return `incomplete: true` with the unavailable directory, preserving loaded history and decisions; full reads and session activation give restore/reopen instructions. Unit coverage checks both result shapes and unrelated errors. |
| `src/extension/opencode-v2-adapter.ts`, `opencode-config-service.ts`; native provider policies | `OpenCodeV2Adapter.kt` reads legacy policies before top-level policies and removes denied configured-provider integration placeholders rather than restoring their custom models. `ProjectProviderConfig.kt` edits the native array when present and otherwise keeps the floor-compatible legacy key. `JsoncEdit.kt` preserves unrelated JSONC comments; concurrent edits reject the write. Unit tests cover both formats, mixed precedence and write rejection. The released 2.0.29 fixture independently verifies denied-provider catalog filtering. |

The complete released OpenCode comparison covers 2.0.28 through 2.0.29,
`c5aabae288` through `34d8ded0c5`, 48 commits and 209 changed files. The
HTTP/OpenAPI comparison removes no routes or methods. Generated config types add
top-level policies and catch up with existing optional provider request encoding.
Session/message/history/inbox, permission/form and SSE envelopes remain unchanged.
Output-limit continuation and incomplete tool-input diagnostics use existing
synthetic/tool/error events, which the Kotlin projection already consumes.
Managed configuration precedence, plugin permission assertions and per-call hook
errors remain server-owned. CLI serve/upgrade arguments did not change. The PTY
and pairing authorization fix selects route identities server-side; OpenJet does
not consume those browser WebSocket/pairing flows. Desktop, Console, Figma and TUI
changes do not require JetBrains host ports. Review evidence is retained under
`build/opencode-compatibility/review-2.0.29/`.

The exact published 2.0.29 binary passed `OpenCodeV2IntegrationTest` and
`OpenCodeManagedServiceIntegrationTest` without skips, including the new native
policy case, in isolated HOME/XDG/database/server-state roots with a deterministic
local provider. Evidence is `build/opencode-compatibility/2.0.29/verification.json`.
The tested status baseline is now 2.0.29; V1 remains 1.19.0. Support floors remain
V2 2.0.5 and V1 1.16.0. The V1 SDK did not change.

Final checks passed: vendor integrity, TypeScript checking, browser build, all 89
browser host tests, all four compatibility-helper tests, and
`./gradlew check buildPlugin verifyPlugin` with the released 2.0.29 fixture binary
enabled. The full Kotlin run passed 521 tests; only the opt-in existing-database
usage benchmark was skipped. Both released suites passed again without skips.
The commit-generation tests include cancellation while an HTTP response body is
stalled. Plugin Verifier reported compatibility with all five configured IDE
builds, with only the existing deprecated/experimental API warnings. The final
build log is `build/upstream-sync-d42fc672-final.log`; the packaged plugin is
`build/distributions/varro-openjet-0.7.12.zip`. No sandbox UI interaction was run.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Accepted image-format difference | Upstream `src/extension/remote-images.ts` uses the WASM `image-thumbnails` worker. OpenJet `RemoteImages.kt` reuses `ImageThumbnails.kt` and JVM decoders for PNG, JPEG, GIF and bundled WebP. The user chose to keep these codecs for this sync rather than add an AVIF decoder and its packaging/licensing work. | Valid AVIF remote images can show the explicit unsupported-image error instead of a preview. The existing host decoder limitation also applies to history thumbnails; there is no direct-network browser fallback. |
| Native UI not verified interactively | Upstream commit toolbar/configuration and `RemoteMarkdownImage.tsx` map to `GenerateCommitMessageAction.kt`, `CommitMessageRulesAction.kt`, `VarroConfigurable.kt` and the vendored renderer. | Toolbar switching, repository dialogs, image loading/layout and settings propagation still need sandbox interaction. Unit/build checks do not establish real-IDE rendering or TLS/public-server interoperability. |

No other missing or blocked consumed-contract change was found in the reviewed
revision. Older static-preload, Windows process-inspection warm-up and quota
differences remain outside this revision's changes. No live provider, interactive
OAuth, native Windows/Linux run or production session was used for verification.

## Upstream sync follow-up, 2026-10-14

Reviewed `d42fc672073f7498245ad17d8b815b094cbdc414` through
`23d8197ff4f7000a395c3d4c4817371804c301e2`, including the intermediate
`d7d86e4b1c4a6025f18db0de4c2056ec80b2270c` snapshot. The vendored changes are
`src/webview/components/chat-input/BusySendMenu.tsx` and
`src/webview/styles/composer.css`: labels are now **Queue** and **Steer**, menu
width follows its content, enabled items use the normal menu hover/focus
background, and disabled items keep a plain background and default cursor.
Upstream adds a four-theme browser regression for these highlights. Callbacks,
keyboard hints, queue-only restrictions and request bodies did not change.
Shared protocol, contributed configuration and `package.json` are unchanged.
The SDK/client baselines remain V1 1.19.0 and V2 2.0.29, with unchanged support
floors and browser dependencies.

The runtime host change in `src/extension/varro-state-paths.ts` shares only
in-flight preparation for each native/legacy directory pair. On Windows this
prevents a second caller from treating a junction's intermediate directory as a
conflicting state root. Settled results are not cached: failures permit a fresh
attempt, and later calls still check for directory conflicts.

OpenJet does not create native/legacy compatibility junctions.
`src/main/kotlin/varro/server/OpenCodeV2SessionState.kt` uses ordinary directory
creation, per-session JVM monitors and the shared owner-directory file lock for
writes. It has no asynchronous preparation or successful-preparation cache, so
the new junction race has no Kotlin equivalent to port. New fixture-only
`OpenCodeV2SessionStateTest` cases cover concurrent first writes without lost
patches, fresh detection of a later file/directory conflict, and a successful
retry after the conflict is removed.

Other extension changes affect tests only. `isolated-process.test.ts` prepares
and disposes the upstream persistent Windows inspector around the suite;
`vitest.config.mts` runs it serially with native inspection tests. OpenJet's
`ProcessIdentity` in `ServerOwnership.kt` uses on-demand inspection, and Gradle
runs its Kotlin fixtures independently of Vitest. The upstream test-runner
change does not add a JetBrains runtime operation or relax process ownership
checks.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing state-root discovery difference | Upstream `src/extension/varro-state-paths.ts` selects platform-native state roots and checks legacy compatibility links. `OpenCodeV2SessionState.kt` still selects the legacy `$XDG_STATE_HOME/varro/opencode-v2` root, defaulting to `~/.local/state/varro/opencode-v2`. | Existing compatibility links keep annotation writers on the same files. If upstream has initialized a distinct native root without a legacy link, OpenJet can use a separate annotation directory; pause markers, metadata and background-service choices may not be shared. This selection difference predates the reviewed change. No user state was moved, linked or reconciled during this sync. |
| Accepted AVIF limitation | Upstream remote-image loading uses its WASM thumbnail worker; OpenJet uses `RemoteImages.kt` and `ImageThumbnails.kt` with the previously selected JVM codecs. | AVIF can show an unsupported-image error. The user's decision to keep the current codecs remains unchanged. |
| Unverified native behavior | Upstream Windows junction and inspector tests have no directly exercised Windows/JCEF counterpart in this macOS run. | Native Windows startup and sandbox rendering of the renamed/styled menu were not verified. The new upstream four-theme browser test was reviewed but not run in OpenJet. Older Windows inspector warm-up, preload and quota differences remain outside this revision's changes. |

No new required backend or consumed-contract gap remains in this reviewed delta.
The pre-existing state-root discovery difference above is not resolved by copying
the new in-flight junction guard. No production sessions or service registrations
were used for verification.

Vendor integrity, TypeScript checking, all 89 browser host tests and the browser
build passed. Missing platform-native npm optional packages interrupted one
repeat check; `npm ci --include=optional --no-audit --no-fund` restored them without
changing the lockfile, and all browser checks then passed.

The pinned compatibility helper passed both released 2.0.29 suites without skips.
The final `./gradlew check buildPlugin verifyPlugin` run also passed with that
binary enabled: 523 Kotlin tests passed, including the two new state regressions;
only the opt-in existing-database usage benchmark was skipped. All five configured
IDE builds were compatible, with the existing deprecated/experimental API warnings.
Evidence is `build/opencode-compatibility/2.0.29/verification.json` and
`build/upstream-sync-23d8197f-final.log`. The updated package is
`build/distributions/varro-openjet-0.7.12.zip`.

## Upstream sync review, 2026-10-15

Reviewed `23d8197ff4f7000a395c3d4c4817371804c301e2` through
`786781dcdb7e7929c315a46ead035041ae03515e`, Varro 0.33.5. The comparison
includes every changed extension source and test, shared protocol, configuration
and dependency declaration. Contributed settings and host message envelopes did
not change. The SDK/client baselines remain V1 1.19.0 and V2 2.0.29; support floors
remain 1.16.0 and 2.0.5. No new released OpenCode range needed review. Vitest and
its lockfile entries were updated to 5.0.5 with Node 24.21.0 and npm 10.9.9;
no new browser dependency was required.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `src/extension/rest-proxy.ts`, `util/webview-message.ts`, `opencode-v2-adapter.ts`; `POST /session/:id/side-question` over `/api/session/:id/generate`; `src/shared/side-question.ts` | `ApiRoutes.kt`, `RestProxy.kt`, `SideQuestions.kt`, `OpenCodeV2SideQuestions.kt` and `OpenCodeV2Adapter.kt` implement the scoped V2-only route, canonical location check, bounded prompt/history/answer, request-only cancellation, total deadline and late-connection rejection. Focused tests reject extra fields, malformed or oversized history, foreign/moved/missing sessions, empty/oversized answers, V1 and stopped connections. They verify no helper, prompt, interrupt, model or inbox fallback. A local stalled-body fixture checks cancellation and timeout. Constants are checked against the vendored contract. |
| `src/extension/opencode-v2-adapter.ts`; `/command` over native `/api/command` and `/api/skill` | `OpenCodeV2Adapter.kt` now uses registry names/descriptions, marks native commands with their source, drops config-template reconstruction, and retains noncolliding skills. `OpenCodeV2Test` verifies precedence, scope and the absence of private native fields or config reads. The vendored UI accepts command arguments, explains delegation and reconciles uncertain sends without replay. |
| `src/extension/opencode-v2-events.ts`; `command.updated` and `skill.updated` | `OpenCodeV2Events.kt` emits scoped `catalog.updated` events. Kotlin regressions verify both events; the vendored data loader refreshes commands with routing state and preserves superseded-load ordering. |
| `src/extension/opencode-v2-projection.ts`; synthetic subagent completion metadata on history and direct-message reads | `OpenCodeV2Projection.kt` projects only native `source: subagent`, a nonempty child ID and completed/error/cancelled state, with optional description and agent. `OpenCodeV2Test` checks identical history/direct results, metadata filtering and rejection of text-only attribution. The vendored renderer adds linked child activities and uses the child's model for details. |

The published OpenCode 2.0.29 binary passed both Kotlin adapter and managed-service
suites without skips through `scripts/check-opencode-compatibility.py`. Fixture
HOME/XDG/database/server-state roots and a deterministic local provider remained
isolated from production. The expanded adapter integration test verifies side
questions and follow-ups without history/inbox writes, generation while a task is
stalled, native command discovery, exactly one delegated child with arguments and
an attachment, parent model/agent preservation, and correct launch/child failure
attribution. Evidence is `build/opencode-compatibility/2.0.29/verification.json` and
`build/upstream-sync-786781dc-compatibility.log`.

The UI refresh also includes edit-reconciliation loading feedback, synchronous
native-view draft checkpoints, composer/menu polish and side-question dialog
controls. These are browser changes reused unchanged in JetBrains. The draft
checkpoint addresses VS Code snapshot timing through the shared native-view
contract; OpenJet retains its own view-state bridge. No new VS Code-only host
operation, configuration migration or Windows process change requires a port.

Vendor integrity, all 89 browser host tests, TypeScript checking and the browser
build passed. The full Kotlin run passed 538 tests, including 15 new regressions;
only the opt-in existing-database usage benchmark was skipped. Both released
suites passed again without skips in that run. All four compatibility-helper
tests and the vendor-integrity script test passed. `check` and `buildPlugin`
completed and produced `build/distributions/varro-openjet-0.8.0.zip`. The first
combined command reached Plugin Verifier before its two-minute shell deadline;
the rerun passed `check buildPlugin verifyPlugin`. All five configured IDE builds
were compatible, with deprecated/experimental API warnings only. Logs are
`build/upstream-sync-786781dc-final.log` and
`build/upstream-sync-786781dc-final-retry.log`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing state-root selection difference | Upstream `src/extension/varro-state-paths.ts` selects native roots with legacy links; `OpenCodeV2SessionState.kt` selects the legacy XDG/default root. | An upstream native root without a legacy link can separate pause markers, annotations and background-service choices across editors. This sync does not move or reconcile user state. |
| Accepted AVIF limitation | Upstream `src/extension/remote-images.ts` uses its WASM thumbnail worker; `RemoteImages.kt` and `ImageThumbnails.kt` retain the previously selected JVM codecs. | AVIF can show an unsupported-image error instead of a preview. |
| Pre-existing preload and Windows inspection warm-up differences | Upstream `vite.config.mts`, `sidebar-provider-bridge.ts`, `webview-html.ts`, `extension.ts` and `process-inspection.ts`; OpenJet `webview/vite.config.mts`, `WebviewHtml.kt` and `ProcessIdentity` in `ServerOwnership.kt`. | No static module-preload hints or persistent Windows inspector warm-up. Startup performance can differ; neither optimization changed in this delta. |
| Pre-existing quota differences | Upstream provider-limit adapters/service versus `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Malformed local Claude windows can reach browser validation; custom header-probe base URLs are ignored; an xAI monthly failure can hide earlier windows; separate hosts can duplicate quota polling and see different snapshots. These adapters did not change in this delta. |
| Native UI and external integrations not verified | Vendored `SideQuestionDialog.tsx`, `UserMessageContent.tsx`, `ToolCall.tsx` and composer/draft code, hosted through `WebviewHost.kt` and `webview/src/host-bridge.ts`. | JCEF dialog focus, cancellation controls, linked child navigation, edit loading and draft restoration still need sandbox interaction. No live provider, interactive OAuth, native Windows/Linux run or upstream Playwright visual test was performed. |

No new required backend or consumed-contract gap remains in the reviewed delta.
These checks do not establish parity for the historical differences above.
No production session, credential or service registration was used.

## Upstream sync review, 2026-10-18

Reviewed `786781dcdb7e7929c315a46ead035041ae03515e` through
`8037ebc98950`, Varro 0.33.9, including all changed extension sources, shared
protocol, contributed configuration and dependencies. The vendored sources remain
unmodified. The only new browser build dependency is `@opencode/client`, used for
native-form types. Existing browser dependency and Node/npm versions are unchanged.

| Upstream change | OpenJet implementation and regression coverage |
| --- | --- |
| `opencode-v2-adapter.ts`, `opencode-v2-projection.ts`, `opencode-v2-events.ts`, `session-state-manager.ts`; native form snapshots, replies and events | `OpenCodeV2Forms.kt`, `OpenCodeV2Projection.kt`, `OpenCodeV2Adapter.kt`, `OpenCodeV2Events.kt`, `SessionTransfers.kt` and `RestProxy.kt` preserve the native contract, validate typed/session-scoped replies, serialize concurrent replies and retain requests on failure. OpenJet carries the complete projected form to browser snapshots rather than using upstream's separate question serializer. `OpenCodeV2SyncTest` covers field identity, duplicate/malformed fields, metadata, stale views, failed replies, concurrency and answer-free settled events. The published V2 fixture now tests keyed replies and retained pending forms after rejected positional replies. |
| `rest-proxy.ts`, `opencode-v2-adapter.ts`; `/session/:id/child-task-controls`, `/interrupt-child`, `/background-parent` | `ApiRoutes.kt`, `ChildTasks.kt`, `OpenCodeV2ChildTasks.kt` and the adapter implement workspace/hidden-session authorization, native relationship and capability checks, foreground progress, bounded cancellation-aware requests and durable handoff acknowledgements. Unit fixtures cover unsupported APIs, root/moved/foreign sessions, hidden parents, reset generations, cancellation, no-op handoffs and missing acknowledgements. Released V2 checks query controls and interrupt actual delegated fixture children. Native parent IDs take precedence over compatibility annotations. |
| `rest-proxy.ts`, `diagnostics.ts`, `opencode-v2-adapter.ts`; restart recovery and lost-shell inbox records | `RestProxy.kt` logs bounded `interrupted-recovery native-owned` decisions with the request generation and suppresses V2 automatic resubmission. `OpenCodeV2Adapter.kt` projects cancellation notices before delivery. Regressions verify explicit sends/V1 remain unchanged and inbox/history identity stays stable. OpenJet does not use upstream's elected-webview V1 continuation admission path. |
| `sidebar-provider-context-files.ts`, `opencode-v2-adapter.ts`, `shared/native-pdf.ts`; PDF transport and model verification | `AttachmentStore.kt`, `VarroProjectService.kt`, `NativePdfInput.kt` and `OpenCodeV2Adapter.kt` implement ordered native picks, transport bounds, validation warnings and authoritative model checks before mutations. Tests cover mixed picks, corrupt PDFs, malformed URLs, unknown/unsupported/supported modalities and command/one-shot rejection. |
| `handy-integration.ts`, `handy-recording-state.ts`, shared Handy protocol | The application-scoped, disposable `HandyIntegration.kt` shares one command guard across projects. `HandyRecordingState.kt` tails at most 64 KiB from standard or portable logs, handles rotation and partial lines, watches only after deliberate mic actions, and stops at idle/unknown or disposal. State/revision messages support the unchanged vendored dictation UI. Tests cover fixed CLI arguments, optional logs, external completion, explicit intent, guarded clicks, timeout/failure handling and a real fixture-directory watcher. No actual Handy application was controlled. |
| `extension-config.ts`, `webview-session.ts`, `package.json`; `showEmptyStateHistogram` | `VarroSettings.kt`, `VarroConfigurable.kt` and `VarroProjectService.kt` expose the opt-in setting, default it off and include it in boot/config updates. The histogram, native forms, child controls, composer/dictation layout and retired-share actions are reused from the upstream UI. |
| Codex quota adapter, auth parsing and cache identity | `QuotaAdapters.kt` rejects usage polling for `chatgpt-token-sharing` with the upstream reconnect guidance. `OpenCodeV2QuotaCredentials.kt` already retains `methodID`, and `ProviderQuotaBackend.kt` fingerprints the full auth record, so method changes already invalidate cached identity. The new quota regression verifies no HTTP or local-credential fallback. |

The released OpenCode review covers V2 2.0.29 through 2.0.32,
`34d8ded0c5` through `843a6c51`, and V1 1.19.0 through 1.19.3,
`733b2c62` through `e3c4eb24`. The complete diffs contain 659 and 87 files;
the GitHub compare API's V2 list stops at 300, so review used the full diff.
The V1 comparison diverges at `add9b4ba5adf`; the previous release's separate
branch contains only package/lockfile version changes, which were checked too.
Evidence is under `build/opencode-compatibility/review-2.0.32/`.
Consumed HTTP routes, SSE envelopes, session/history/inbox, permissions and forms
remain compatible. V2 adds an unused voice endpoint and optional model
temperature, reasoning controls and release dates. V1 adds configured
`reasoning_options` and retires share creation while retaining local cleanup.
Kotlin preserves returned catalog variants and native configuration fields.
Provider mappings, instruction discovery, MCP negotiation/timeouts, compaction,
saved service environments and Windows snapshot writes remain server-owned.
Windows default service ports change, but OpenJet reads registrations and launches
on explicit ports. CLI serve/upgrade arguments remain compatible. OpenCode app,
Desktop, TUI, Console and schema-publication changes require no JetBrains port.

The exact published 2.0.32 binary passed both Kotlin compatibility suites without
skips through `scripts/check-opencode-compatibility.py`, with isolated
HOME/XDG/database/service-state roots and a deterministic local provider.
Evidence is `build/opencode-compatibility/2.0.32/verification.json`.
The new published V1 1.19.3 fixture passed without skips for authenticated startup,
catalog reasoning variants, rejected share creation and local unshare cleanup.
Native V1 cleanup advances its own updated timestamp; the vendored UI preserves
displayed activity time. The first V1 fixture incorrectly expected an unchanged
native timestamp, and passed after correcting that assertion. Evidence is
`build/upstream-sync-8037ebc-v1-retry.log` and
`build/opencode-compatibility/1.19.3/verification.json`. Tested status hints now match V1 1.19.3
and V2 2.0.32. Support floors remain unchanged.

Vendor integrity, TypeScript checking, browser build, all 89 browser host tests,
all four compatibility-helper tests and the vendor-integrity script test passed.
`./gradlew check buildPlugin verifyPlugin` passed with both published binaries
enabled. The final full run passed 556 Kotlin tests; only the opt-in existing-database
usage benchmark was skipped. All three released suites passed without skips.
All five configured IDE builds were compatible, with deprecated/experimental API
warnings only. Logs are `build/upstream-sync-8037ebc-final.log` and
`build/upstream-sync-8037ebc-final-retry.log`; the final repeat includes the native
parent-identity regression. The package is `build/distributions/varro-openjet-0.8.1.zip`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing state-root selection difference | Upstream `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Distinct native roots without legacy links can separate annotations, pause markers and background-service choices across editors. No user state was moved or reconciled. |
| Accepted AVIF limitation | Upstream `remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF may show an unsupported-image error. The previously selected JVM codecs remain unchanged. |
| Pre-existing startup optimizations | Upstream `vite.config.mts`, `sidebar-provider-bridge.ts`, `webview-html.ts`, `extension.ts` and `process-inspection.ts`; OpenJet `webview/vite.config.mts`, `WebviewHtml.kt` and `ServerOwnership.kt`. | Static module preloads and persistent Windows inspector warm-up remain absent. Startup performance can differ. |
| Pre-existing quota differences | Upstream provider-limit adapters/service; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, xAI partial failures and cross-host polling still differ. This sync fixes the changed token-sharing behavior only. |
| Native UI and external integrations not verified | Upstream native form, child control, histogram and dictation components; OpenJet `WebviewHost.kt`, `HandyIntegration.kt` and the vendored UI. | JCEF rendering/focus, real Handy lifecycle logs, live providers, PDF provider delivery, interactive OAuth and native Windows/Linux runs were not verified. Full native crash/recovery and active-child background handoff were not exercised against the released CLI; their adapter decisions have deterministic unit coverage. Upstream Playwright/VS Code tests were reviewed but not run in JetBrains. |

No new required backend or consumed-contract gap was found in this reviewed delta.
These checks do not establish parity for the historical differences above.
No production session, credential or service registration was used.

## Upstream sync follow-up, 2026-10-18

Reviewed `8037ebc9895050b8d84ff38e3ab295b3d01f37cf` through
`ec1e71ac1ed95243225b261342d0f4dc44c0f6f2`. Varro remains 0.33.9 and OpenJet
remains 0.8.1. Shared protocol, contributed settings, dependencies and toolchain
did not change. The tested baselines remain V1 1.19.3 and V2 2.0.32, with support
floors unchanged. No new released OpenCode version range needed review.

| Upstream fix | OpenJet alignment and regression coverage |
| --- | --- |
| `src/webview/components/MessageList.tsx`; excess reserve during summary anchoring | Copied unchanged into `webview/vendor/webview/components/MessageList.tsx`. Real layout growth no longer also increases empty bottom reserve, and the preserved target is not incremented twice for a shortfall. Reviewed the new `e2e/tests/scroll-activity-collapse.spec.ts` regression and the virtualization invariants. No Kotlin host geometry change is needed. |
| `src/extension/open-code-process.ts`, `server-connection-monitor.ts`; empty listener observations and diagnostics | `ServerOwnership.kt` now distinguishes an empty listener set while the registered PID is alive from positive replacement evidence. Background registration monitoring logs the specific mismatch without disconnecting on inconclusive inspection. Startup, takeover, stop and maintenance still require the exact listener, executable and birth identity. `ServerOwnershipTest` verifies retained lease bytes, strict lifecycle rejection, later successful inspection and positive replacement detection. |
| `src/extension/open-code-process.ts`, `server.ts`; credential-only startup recheck | `OpenCodeServer.kt` schedules one cancellation/generation-aware recheck after startup. `OpenCodeProcess.kt` and `ServerOwnership.kt` recover only a freshly verified original private registration, then use ordinary ownership election. Live owners retain control; relinquished owners can be recovered. Changed records, replacement/PID-reuse, ambiguous listeners, missing identity and standalone credential companions remain attach-only. Fixtures cover active/relinquished leases and cancellation both before and during inspection. No recheck signals a process or relaunches an attached server. |
| `src/extension/provider-limit-service.ts`; attach-only V2 OpenAI limits | `OpenCodeHostServices.kt` delegates the policy to `ProviderQuotaBackend.kt`. Attached V2 OpenAI polls read the active OAuth value from authenticated `/api/credential`, retain its method and account identity, and use only that account for Codex quota requests. No local database, V1 auth file, Codex file, inactive account or environment-token fallback is allowed. Cache identity includes the connection mode and selected credential. Other attached providers and V1 remain unsupported; token sharing retains the explicit reconnect guidance. Tests poison local sources, check selected-account headers and cache invalidation, reject malformed/unavailable/key/inactive credentials, and verify credentials never enter quota responses. |

The new optional credential read uses the released server's `Credential.Entry`
shape, including `integrationID`, `active`, typed `value` and OAuth metadata.
An older server without `/api/credential` reports a polling error instead of
falling back to a local account. This does not raise the connection support floor.
The exact published 2.0.32 binary passed both Kotlin adapter and managed-service
suites without skips through `scripts/check-opencode-compatibility.py` in isolated
HOME/XDG/database/service-state roots. Evidence is
`build/opencode-compatibility/2.0.32/verification.json` and
`build/upstream-sync-ec1e71a-compatibility-final.log`. The expanded published-server
fixture creates synthetic OAuth credentials only in its isolated database, reads
the active account through the native endpoint, and checks selected-account quota
headers and token-sharing rejection through a stub quota HTTP client. OpenAI is
denied by the fixture policy, and no provider receives these credentials. Native
fixture credentials are deleted afterward; poisoned host-local stores are ignored.

Vendor integrity, TypeScript checking, browser build, all 89 browser host tests,
all four compatibility-helper tests and the vendor-integrity script test passed.
The new upstream Chromium test, **summary anchoring does not accumulate reserve
for already-reachable layout growth**, passed against the synced upstream source.
Evidence is `build/upstream-sync-ec1e71a-scroll-regression.log`. This checks browser
geometry in the upstream harness, not JCEF rendering in a JetBrains sandbox.

`./gradlew check buildPlugin verifyPlugin` passed with both published V2 2.0.32
and V1 1.19.3 binaries enabled. The full run passed 562 Kotlin tests; only the opt-in
existing-database usage benchmark was skipped. All three released suites passed
without skips. All five configured IDE builds were compatible, with only existing
deprecated/experimental API warnings. Evidence is
`build/upstream-sync-ec1e71a-final.log` and
`build/upstream-sync-ec1e71a-final-retry.log`; the repeat also includes the expanded
published credential-endpoint regression. The package remains
`build/distributions/varro-openjet-0.8.1.zip`.

The `scripts/ai-streaming.mjs` change reads VS Code's folder-scoped sidebar route
instead of stale shared route evidence. This is upstream verification tooling,
not a new runtime host request. OpenJet's project/view state and JCEF bridge do
not use that VS Code runner. The production ownership and quota fixes above apply
across platforms and were ported rather than treated as Windows-only optimizations.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing state-root difference | Upstream `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Without legacy links, separate native roots can separate annotations, pauses and background-service choices across editors. No user data was migrated. |
| Accepted AVIF limitation | Upstream `remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF may show an unsupported-image error. |
| Pre-existing startup optimizations | Upstream preload generation and persistent Windows inspector; OpenJet `webview/vite.config.mts`, `WebviewHtml.kt` and `ServerOwnership.kt`. | Static module preloads and inspector warm-up remain absent, so startup performance can differ. |
| Other pre-existing quota differences | Upstream provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, xAI partial failures and cross-host polling still differ. The changed attach-only OpenAI behavior is implemented in this follow-up. |
| Native and live-account behavior not verified | Upstream summary-anchor and connection flows; OpenJet `WebviewHost.kt`, `OpenCodeServer.kt`, `ServerOwnership.kt` and `ProviderQuotaBackend.kt`. | JCEF scroll rendering, native Windows/Linux inspection and live attached OpenAI quota requests were not exercised. Deterministic fixtures cover transient inspection and credential selection without touching production state. |

No new required backend or consumed-contract gap remains in this reviewed delta.
The historical differences above remain. No production session, credential,
database or service registration was used for tests.

## Upstream sync review, 2026-10-19

Reviewed `ec1e71ac1ed95243225b261342d0f4dc44c0f6f2` through
`81be8105ae20ea0910ff81a7bf170a6443ffef82`, Varro 0.33.12. The comparison covers
all changed extension sources and tests, shared protocol, contributed settings,
dependencies and verification tooling. The vendored sources are copied unchanged.
The V1 SDK and V2 client remain at 1.19.3 and 2.0.32. Tested status hints and support
floors remain unchanged; there is no new released OpenCode range to review.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `extension-config.ts`, `webview-session.ts`, `sidebar-provider.ts`, `commands.ts`, `workspace-problems.ts`, shared configuration types and `package.json`; `autoAttachProblems` and `autoAttachActiveFile` | `VarroSettings.kt`, `VarroConfigurable.kt`, `ChatContextConfig.kt` and `VarroProjectService.kt` expose the default-on active-file setting and send both flags in boot snapshots and live updates. The existing persisted `chatEnableProblemsContext` field now maps to `autoAttachProblems`, preserving saved opt-outs without migrating user state. `WorkspaceProblems.kt`, `AddProblemsToContextAction.kt` and explicit problem attachments continue using that same preference. Kotlin tests cover defaults, independent persistence and the renamed payload. |
| `hooks/session/session-send.ts`, `ChatInput.tsx`, `side-question.ts`, `state-stored-values.ts`; attachment opt-outs at send time | The unchanged vendor checks the current settings for normal sends, side questions and restored queues. New OpenJet browser tests verify saved/unsaved file exclusion, manual-file retention, independent diagnostics and current/legacy queued snapshots. `MissedPortsTest` verifies that `VarroStore.kt` and `QueuedDispatches.kt` retain the automatic attachment marker and explicit files across reloads. DataGrip extension context remains independent, with focused browser coverage. |
| `ChatInput.tsx`, `chat-input/queued-steer.ts`; owning-directory routing, visible retry errors and atomic admission updates | Copied unchanged. `RestProxy.kt` already forwards the request's directory and dispatch lease; `QueuedDispatches.kt` retains message identity and write-ahead admission state. No new endpoint or Kotlin dispatch operation is needed. The focused upstream `ChatInput` suite checks queue/steer retries against a session directory different from the selected folder, preserved IDs, claim failures and admission acknowledgements. |
| `MessageList.tsx`, `MessagePart.tsx`, `MessageRows.tsx`, `ToolbarPickers.tsx`, `composer.css`; obsolete scrollbar reserve and model-speed labels | Copied unchanged. Three upstream Chromium regressions passed for top/bottom thumb release and deferred diff heights through remounts and toggles. This verifies the browser fixture, not JetBrains JCEF rendering. |
| `package.json` and CI; Node 24.22.0, npm 12.2.1 and install-script approval | `webview/package.json`, its lockfile and `Dockerfile` now pin the same toolchain. Only the locked esbuild and optional fsevents installers are approved. Clean `npm ci` succeeds, and `npm install-scripts ls` reports no unreviewed scripts. OpenJet has no Git dependency or VS Code signing package, so upstream's Git allowlist and signing-script approval do not apply. |

No browser runtime dependency changed or was added. Upstream's ImageMagick WASM
update is extension-only; OpenJet retains its previously selected JVM codecs.
Lint/formatter package updates and editor/CI settings belong to the upstream
development environment. AI fixture edits, sidebar-route evidence, command-palette
selection and native-input verification changes in `scripts/ai-*` and
`vscode-launch-process.mjs` are VS Code test tooling, not JetBrains runtime host
operations. No OpenCode adapter, server startup or service-registration contract
changed in this delta.

Vendor integrity, TypeScript checking, browser build and all 95 OpenJet browser
tests passed. The four compatibility-helper tests, seven build-script tests and
vendor-integrity script test passed. All 1,076 selected upstream unit tests and the
three Chromium regressions passed. Evidence is
`build/upstream-sync-81be810-upstream-unit.log`,
`build/upstream-sync-81be810-upstream-components.log` and
`build/upstream-sync-81be810-scroll-regressions.log`.

The pinned compatibility helper reran both Kotlin adapter and managed-service
suites against the exact published 2.0.32 binary without skips. Fixture HOME,
XDG, database and service-state roots stayed isolated, with a deterministic local
provider. Evidence is `build/opencode-compatibility/2.0.32/verification.json` and
`build/upstream-sync-81be810-compatibility.log`.

`./gradlew check buildPlugin verifyPlugin` passed with the published V2 2.0.32
and V1 1.19.3 fixture binaries enabled. The full run passed 565 Kotlin tests;
only the opt-in existing-database usage benchmark was skipped. All three released
suites passed without skips. Plugin Verifier reported compatibility with all five
configured IDE builds, with the existing deprecated/experimental API warnings.
The log is `build/upstream-sync-81be810-final.log`, and the package is
`build/distributions/varro-openjet-0.8.2.zip`. The user's existing plugin-version
change was retained.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing state-root selection difference | Upstream `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was migrated. |
| Accepted AVIF limitation | Upstream `src/extension/remote-images.ts` and its WASM thumbnail worker; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF can show an unsupported-image error instead of a preview. The upstream WASM dependency update does not change this accepted decoder difference. |
| Pre-existing startup optimizations | Upstream `vite.config.mts`, `sidebar-provider-bridge.ts`, `webview-html.ts`, `extension.ts` and `process-inspection.ts`; OpenJet `webview/vite.config.mts`, `WebviewHtml.kt` and `ServerOwnership.kt`. | Static module preloads and persistent Windows inspector warm-up remain absent, so startup performance can differ. Neither changed in this delta. |
| Pre-existing quota differences | Upstream provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Malformed Claude windows can reach browser validation, custom probe URLs are ignored, xAI monthly failures can hide usable windows, and separate hosts can duplicate polling. These adapters did not change in this delta. |
| Native UI and external behavior not verified | Vendored composer, queue, scrollbar and model-label components; OpenJet `WebviewHost.kt`, `VarroConfigurable.kt` and `VarroProjectService.kt`. | Actual sandbox settings propagation, JCEF rendering/focus and native Windows/Linux behavior were not exercised. No live provider, interactive OAuth, Handy lifecycle or Docker image build was tested. Upstream browser tests do not establish those results. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The historical differences above remain. No production session, credential,
database or service registration was used for tests.

## Upstream sync review, 2026-10-20

Reviewed Varro `81be8105ae20ea0910ff81a7bf170a6443ffef82` through
`c3b54809cc5a`, version 0.33.15, including every changed extension source,
shared protocol, package configuration and tests. V1 remains at SDK 1.19.3;
the V2 client moves from 2.0.32 to 2.1.1. Support floors stay unchanged.
Vendor sources are copied without transformations, and the lockfile uses the
pinned Node 24.22.0 and npm 12.2.1 toolchain.

| Upstream change | Kotlin alignment and regression coverage |
| --- | --- |
| `opencode-api-capabilities.ts`, `opencode-v2-adapter.ts`, `opencode-v2-session-state.ts`; native session archives | `OpenCodeApiCapabilities.kt` resolves bounded references and composed request schemas. `OpenCodeV2Adapter.kt` maps timestamps/null restores to native boolean PATCHes when advertised. Confirmed server timestamps override old annotations, including after another client restores a session. Older servers retain local annotations. Discovery and native-write failures do not silently write local fallback state. `OpenCodeV2ArchiveTest` covers validation, fallback, reconnect discovery, read-only history and include-archived list semantics. |
| `opencode-v2-events.ts`, shared protocol and session-event handlers; archive events and 2.1 tool replay fields | `OpenCodeV2Events.kt` and `ServerEvents.kt` forward archive/restore identity, sequence and workspace. The vendored handler refreshes headers without creating transcript activity. Tool call/result projection accepts `native`, retaining the older `state`/`resultState` fallback. Kotlin regressions cover all three tool lifecycle shapes. |
| `opencode-config-service.ts`, `rest-proxy.ts`, `util/webview-message.ts`; saved-permission editing | `OpenCodeSavedPermissions.kt`, `OpenCodeTransport.kt`, `RestProxy.kt` and `ApiRoutes.kt` implement observed allowance tracking, project filtering, native-ID reconciliation, optional edit discovery, validation and verified update acknowledgements. This also replaces OpenJet's previous unsupported server-memory endpoint with list/remove and restart-only fallback behavior. Tests cover foreign/unobserved IDs, conflicts, failed discovery, unverified responses, legacy allowances and connection reset. |
| `MarkdownRenderer.tsx`, `PermissionSettingsPanel.tsx`, `client.ts` and permission styles | Copied unchanged. Markdown file citations recognize `#L68`, `#L91-L105` and `#L91-105` fragments without losing multi-root targets. Existing `VarroProjectService.kt` and `EditorIntegration.kt` already accept the resulting `vscode/open` path/line payload. Permission edits preserve drafts on errors and refresh after successful mutations. No new runtime dependency or host message is required. |
| Adapter runner, E2E harness and upstream documentation | npm installer approval and VS Code fixture adjustments are upstream tooling. OpenJet's pinned CLI helper already installs only the selected release's binary. No JetBrains runtime equivalent is needed. |

The released OpenCode review compares `843a6c51`, V2 2.0.32, through `133b1ebf`,
V2 2.1.1. The complete 738-file diff is retained under
`build/opencode-compatibility/review-2.1.1/complete.diff`; the GitHub JSON comparison
alone truncates at 300 files. The review covers HTTP/OpenAPI and generated clients,
SSE/schema changes, configuration, provider/model catalogs, permissions/forms,
session/history/inbox records, credential shapes, and serve/update behavior.

OpenAPI changes affect session list filters, session PATCH, message filters,
legacy transcript import and saved-permission PATCH. Native archive support and
permission editing are implemented above. Cursor values remain opaque; legacy
`archived=true` still includes both states and is not sent as the native
archived-only filter. Message filtering adds `idle`, already handled by the adapter.
The server upgrades old transcript imports and stored replay records itself.

Assistant/text/reasoning replay data also changes to `native`, and compaction drops
required `recent` and `providerContext` fields. OpenJet does not consume those opaque
replay fields or require the removed fields. Compaction dividers and status still
project through `OpenCodeV2Projection.kt` and `OpenCodeV2Events.kt`. The removed
transitional `session.compacted` event was not emitted by the V2 adapter.
Key credentials drop `configuration`; OpenJet connects keys through the unchanged
integration endpoints, and attached quota reads consume OAuth credentials only.
Provider/model compatibility adds optional `streamUsage`, owned by the server.
Generation, inbox delivery, permission/form replies and managed serve flags retain
their consumed contracts. New experimental todos and Gemini voice are optional
server functionality, not new Varro controls. Server-side compaction, cache,
provider decoding, filesystem, skill-discovery and retry fixes need no Kotlin port.
Windows persistent terminal changes and OpenCode app, desktop, TUI, Console and
build changes do not implement JetBrains host behavior.

The exact published native macOS ARM64 2.1.1 binary passed both Kotlin adapter and
managed-service suites without skips in isolated HOME, XDG, database and
server-state roots. The adapter fixture verifies native archive/restore events,
server timestamps, stale-annotation precedence, saved edits, other-client edits
and removal. Both suites also passed on the published 2.0.32 binary, exercising
the older-server archive fallback and saved list/remove behavior. Evidence is
`build/opencode-compatibility/2.1.1/verification.json` and
`build/opencode-compatibility/2.0.32/verification.json`.

Vendor integrity, TypeScript checking, browser build and all 95 OpenJet browser
tests passed. The four compatibility-helper tests, seven build-script tests and
vendor-integrity script test passed. The full Kotlin run passed 579 tests, including
the released V1 1.19.3 and V2 2.1.1 adapter and managed-service fixtures. Only the
opt-in existing-database usage benchmark was skipped. The package is
`build/distributions/varro-openjet-0.8.3.zip`; the user's existing version change
was retained. The complete build log is `build/upstream-sync-c3b548-final.log`.

`./gradlew check buildPlugin verifyPlugin` passed. Plugin Verifier reported
compatibility with all five configured IDE builds, including IU-263.6800.24,
with the existing deprecated/experimental API warnings. The first combined run
hit its outer two-minute timeout after tests and packaging; the longer rerun
completed successfully.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing state-root selection difference | Upstream `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was migrated. Native archive changes now use the server when supported. |
| Accepted AVIF limitation | Upstream `src/extension/remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF can show an unsupported-image error instead of a preview. These decoders did not change in this delta. |
| Pre-existing startup optimizations | Upstream preload generation and Windows process inspection; OpenJet `webview/vite.config.mts`, `WebviewHtml.kt` and `ServerOwnership.kt`. | Static module preloads and persistent Windows inspector warm-up remain absent, so startup performance can differ. |
| Pre-existing quota differences | Upstream provider-limit adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ. These adapters did not change in this delta. |
| Native UI and other platforms not verified | Upstream permission panel, Markdown and archive refresh handlers; OpenJet `WebviewHost.kt` and the unchanged vendored UI. | Actual JCEF layout, permission-edit focus/draft behavior, archive refresh in a sandbox IDE and native Windows/Linux behavior were not exercised. No live provider, OAuth account or production state was used. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The historical differences and unverified native UI checks above remain.

## Upstream sync follow-up, 2026-10-20

Reviewed Varro `c3b54809cc5ae0205c7d7521765053b9b0fc8468` through
`c24d8b4f85194f84f80d563f00022051c9e2ff1a`, still version 0.33.15. This
continues the existing staged sync without discarding its Kotlin changes or the
user's 0.8.3 version selection. Two new commits change the extension backend;
the third reduces padding on inactive unpinned model-picker rows. Shared sources,
host protocol and package configuration are unchanged. SDK 1.19.3 and client
2.1.1 baselines are unchanged, as are the support floors.

| Upstream change | OpenJet alignment |
| --- | --- |
| `server-memory-permission-store.ts`, `sidebar-provider.ts`; durable management links | `ServerMemoryPermissionStore.kt` validates restored native IDs and stores them in project-local `VarroStore.serverMemoryPermissions`. Keys include API version, server URL, project and native ID. Legacy process-only rules and capability flags are not persisted. Storage failures retain in-memory management and cannot fail an acknowledged approval. |
| `opencode-config-service.ts`, `rest-proxy.ts`; approval capture and reconciliation | `OpenCodeTransport.kt` resolves native saved IDs immediately after a successful Always reply, without requiring the Permissions screen to open. `OpenCodeSavedPermissions.kt` follows external edits by ID and discards removed IDs. Scope matching is restricted to legacy observations, so an internal replacement with a new ID cannot become user-managed. Edit/remove operations still affect only the saved layer and never resolve pending requests or change session/project rules. |
| `context-provider.ts`; bounded missing absolute-file lookup | `EditorIntegration.resolve` already checks only the requested absolute path or a project-relative path. It has no basename fallback, cross-project search or filesystem-root walk, so the unrelated-file bug does not apply. Missing shorthand absolute paths remain unavailable rather than searching the parent's descendants. This preserves existing JetBrains behavior. |
| `src/webview/styles/menus.css`; model-picker padding | Copied unchanged into `webview/vendor/webview/styles/menus.css`. Inactive unpinned rows use 12px right padding, while pinned, hovered and keyboard-focused rows retain space for their controls. No host change is required. |
| Context, REST and store tests; VS Code sandbox fixture namesakes; permission lifecycle documentation | Kotlin regressions cover XML recreation, server/API/project isolation, duplicate scopes with distinct IDs, malformed storage, capability re-discovery, failed storage/retry, approval acknowledgement, external edits, removals and rejected replacement IDs. VS Code sandbox scaffolding has no JetBrains runtime equivalent. |

The exact published macOS ARM64 OpenCode 2.1.1 binary passed the Kotlin adapter
and managed-service suites without skips in isolated HOME, XDG, database and
server-state roots. The adapter fixture now recreates its transport before the
first managed-permission read, proving native approval IDs were already saved.
Evidence is `build/opencode-compatibility/2.1.1/verification.json`; the run log is
`build/upstream-sync-e87b0b0-compatibility.log`.

Vendor integrity, all 95 browser host tests, TypeScript checking and the browser
build passed with pinned Node 24.22.0 and npm 12.2.1. The full Kotlin run passed
589 tests, including released V1 1.19.3, V2 2.1.1 and managed-service fixtures.
Only the opt-in existing-database usage benchmark was skipped. Compatibility
helper, build-script and vendor-integrity script tests passed. `check buildPlugin
verifyPlugin` passed before the final CSS-only refresh; Plugin Verifier reported
compatibility with all five configured IDE builds, including IU-263.6800.24,
with the existing deprecated/experimental API warnings. Its log is
`build/upstream-sync-e87b0b0-final.log`.

After the final model-picker CSS refresh, `./gradlew check buildPlugin` passed
again with the same Kotlin/browser results. The packaged stylesheet contains
the new upstream selector. The package is
`build/distributions/varro-openjet-0.8.3.zip`; the final build log is
`build/upstream-sync-c24d8b4-final.log`.

Concurrent worktree edits were detected after these checks in host scheduling,
file search, transport, assets and the browser receive channel. Those changes
were left untouched and are outside this upstream review. The passing results
apply to the source snapshot read by the build, not subsequent concurrent edits;
the latest combined worktree has not been revalidated.

No new required backend or consumed-contract gap remains in these three commits.
The pre-existing state-root, AVIF, startup and quota differences listed in the
preceding review remain unchanged, with their upstream/OpenJet paths and impact.
Actual JCEF model-picker layout, permission-panel behavior after a full IDE
restart and native Windows/Linux behavior were not exercised. This follow-up
did not repeat the unchanged OpenCode release-diff review or claim parity for unrelated backend
behavior. No production session, credential, database or service registration
was used.

## Windows fixture sync follow-up, 2026-10-20

Reviewed every changed file from Varro
`c24d8b4f85194f84f80d563f00022051c9e2ff1a` through
`b14dc601414e43ce0f41a33029b18814260007eb`, still version 0.33.15.
The webview, shared protocol, package configuration, dependencies and content hash
are unchanged. SDK 1.19.3, client 2.1.1 and both support floors remain unchanged.
Only the recorded upstream revision changes in the vendor snapshot. Existing
staged changes and the user's OpenJet 0.9.0 version are preserved.

| Upstream change | OpenJet applicability |
| --- | --- |
| `src/extension/process-inspection.ts`, `extension.ts` and their tests; propagate Windows helper preparation failures to fixtures, catch and log them only during activation | `ProcessIdentity` in `src/main/kotlin/varro/server/ServerOwnership.kt` uses on-demand OS commands, with no persistent helper, preparation promise or background activation hook. There is no swallowed preparation failure to fix. The existing absence of Windows inspector warm-up remains a performance difference, not a new missing port. |
| `src/extension/isolated-process.test-support.ts`, `isolated-process.test.ts`, new `isolated-process-support.test.ts` and `docs/windows-startup.md`; coherent Windows birth/executable snapshots, confirmed-exit short circuits, setup round-trip and visible fixture warnings | OpenJet has no `IsolatedProcessTree` or native/CIM helper fallback. `OpenCodeV2IntegrationTest.kt` and `OpenCodeManagedServiceIntegrationTest.kt` retain JVM `Process`/`ProcessHandle` objects for fixture teardown. Managed lifecycle authorization still checks listener, executable, birth identity and lease through `ServerOwnership.kt`; `ServerOwnershipTest.kt` covers reused PIDs, changed executables and ambiguous listeners. The upstream TypeScript fixture changes require no Kotlin runtime or test-helper port. |

No new required backend or consumed-contract gap remains in this delta. No new
released OpenCode range needs review. This follow-up does not repeat the unchanged
2.0.32-to-2.1.1 release review or re-review unrelated staged backend work.

The compatibility helper passed both Kotlin adapter and managed-service suites
against the exact published OpenCode 2.1.1 binary without skips. Fixtures use
isolated HOME, XDG, database and server-state roots, never production state.
Evidence is `build/opencode-compatibility/2.1.1/verification.json` and
`build/upstream-sync-b14dc60-compatibility.log`.

Vendor integrity, all 97 browser host tests, TypeScript checking and browser
bundling passed. The first browser attempt found missing optional native npm
bindings; `npm ci --include=optional --no-audit --no-fund` restored them without
changing the lockfile. `npm install-scripts ls` reports no unreviewed scripts.
The four compatibility-helper, seven build-script, three host-benchmark checker
and one vendor-integrity script tests passed.

`./gradlew check buildPlugin verifyPlugin` passed against the combined working
tree. All 614 enabled Kotlin tests passed, including released V1 1.19.3, V2 2.1.1
and managed-service fixtures; only the opt-in existing-database usage benchmark
was skipped. Source hashes confirmed no build/test source changed during this
verification. Plugin Verifier reported compatibility with all five configured
IDE builds, including IU-263.6800.24, with deprecated/experimental API warnings
and unresolved optional dependency notices. The log is
`build/upstream-sync-b14dc60-final.log`; the package is
`build/distributions/varro-openjet-0.9.0.zip`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing Windows inspector difference | Upstream `src/extension/process-inspection.ts` and `extension.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | OpenJet starts PowerShell inspection on demand rather than warming a persistent helper. Windows startup performance can differ. Native Windows setup/cleanup was not exercised in this macOS run. |
| Pre-existing state-root selection difference | Upstream `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was migrated. |
| Accepted AVIF limitation | Upstream `src/extension/remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF can show an unsupported-image error instead of a preview. |
| Pre-existing quota differences | Upstream provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ. None changed in this delta. |
| Native UI and external integrations not verified | Upstream host/UI and OpenJet `WebviewHost.kt` and its vendored UI. | No sandbox interaction, native Linux run, live provider, interactive OAuth or Handy application check was performed. No UI source changed in this delta. |

The staged static-module-preload implementation in `webview/vite.config.mts` and
`WebviewHtml.kt` supersedes the historical missing-preload entries above. Actual
JCEF startup performance has not been measured during this sync.

## Upstream sync review, 2026-10-21

Reviewed Varro `b14dc601414e43ce0f41a33029b18814260007eb` through
`1a79bb1eb0ca90efab329d6765c58c7d90bce754`, version 0.33.16. The review includes
every changed extension source and test, shared protocol, package configuration,
documentation and browser source. Shared protocol and contributed settings did not
change. Vite moves to 8.3.5 and the V2 client to 2.1.2; no new browser dependency
is needed. The lockfile uses pinned Node 24.22.0 and npm 12.2.1. The user's
OpenJet 0.9.1 version selection is unchanged.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `opencode-v2-adapter.ts`, `opencode-v2-events.ts`; `/api/form` and form events | `OpenCodeV2Adapter.kt` and `OpenCodeV2Events.kt` exclude the location-owned `global` sentinel before session projection or reply caching. `OpenCodeV2SyncTest` covers mixed snapshots, all three events, unchanged session forms and rejection of session-scoped replies/cancellations without native mutations. Global forms remain pending for a location-aware client, matching Varro. |
| `open-code-transport.ts`, `opencode-v2-adapter.ts`; `/api/model`, `/provider`, `/config/providers` | `OpenCodeTransport.kt` passes validated health versions to `OpenCodeV2Adapter.kt`. From 2.1.2, configured fallback cannot restore excluded OpenCode/Go models. Tests cover unknown, old, prerelease, current and newer versions, custom fallback, returned-model overrides and reset. The published 2.1.2 fixture independently checks native free-model omission and both projected catalog routes with `OPENCODE_DISABLE_FREE_MODELS=true`. |
| `provider-limits/adapters/opencode-go.ts`; Go usage endpoint | `QuotaAdapters.kt` now uses `https://opencode.ai/inference/go/v1/usage`. `QuotaAdaptersTest` and `ProviderQuotaMatrixTest` verify the new URL, bearer authentication and usage projection through fixture HTTP clients. |
| `MessageList.tsx`, `session-list.css` | Copied unchanged. Native touch ownership survives pointer cancellation until touch end/cancel; upward reading remains detached during streaming. Taps and horizontal swipes retain follow. Composer feedback accounts for its border when positioning above the input. Upstream scroll unit and Chromium regressions passed. |

The released OpenCode review covers 2.1.1 through 2.1.2, `133b1ebf` through
`4b50f5cc`, including the `a517687f` release tag and subsequent package-version
synchronization. Complete diffs and comparison metadata are retained under
`build/opencode-compatibility/review-2.1.2/`. The 174-file comparison includes
HTTP/OpenAPI, generated clients, schemas, SSE, configuration, provider/model
metadata, permissions/forms, session/history/inbox, and serve/upgrade behavior.
No consumed route, method or event envelope was removed. The only OpenAPI field
addition is optional `experimental.location_idle_timeout`, preserved by native
configuration reads. Structured errors add optional `retryAfterMs` and
`provider.free-limit`; `OpenCodeV2FailureTest` verifies that history and live errors
retain usage guidance and HTTP status. Countdown and prompt-recall differences
are listed below rather than treated as implemented.

Location retention during active sessions/background jobs, Code Mode job ordering,
lower-reasoning titles, provider decoding/realtime fixes and legacy Console
credential rewriting remain server-owned. App, TUI, Electron, Console, website,
installer and retired hosted-sharing changes do not require JetBrains host ports.
Local V2 sharing remains unavailable; no production credentials were rewritten.
V1's SDK stays at 1.19.3; both support floors are unchanged.

The exact published native macOS ARM64 2.1.2 binary passed the Kotlin adapter and
managed-service suites without skips through `scripts/check-opencode-compatibility.py`.
Fixtures use isolated HOME, XDG, database and service-state roots with a local
deterministic provider. Evidence is
`build/opencode-compatibility/2.1.2/verification.json` and
`build/upstream-sync-1a79bb1-compatibility.log`.
Both suites also passed again without skips on published 2.1.1, checking the
older-server path after the version-gated change. Its log is
`build/upstream-sync-1a79bb1-older-compatibility.log`.

Vendor integrity, all 97 browser host tests, TypeScript checking and browser build
passed. npm reports no unreviewed install scripts. All 549 selected upstream unit
tests and 24 Chromium cases passed, covering touch, composer feedback and the
histogram. These are upstream browser fixtures, not JCEF sandbox verification.
The four compatibility-helper, seven build-script, three benchmark-checker and
one vendor-integrity script tests passed. Logs use the `build/upstream-sync-1a79bb1-`
prefix.

`./gradlew check buildPlugin verifyPlugin` passed with published V2 2.1.2 and
V1 1.19.3 fixture binaries enabled. All 617 enabled Kotlin tests passed; only the
opt-in existing-database usage benchmark was skipped. All three released suites
passed without skips. Plugin Verifier reported compatibility with all five
configured IDE builds, including IU-263.6800.24, with the existing deprecated/
experimental API warnings and unresolved optional-dependency notices. Source
hashes confirmed no build/test source changed during verification. The final log
is `build/upstream-sync-1a79bb1-final.log`; the package is
`build/distributions/varro-openjet-0.9.1.zip`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Optional OpenCode UI parity, also absent in Varro | OpenCode `packages/schema/src/session-error.ts` and `packages/session-ui/src/timeline/projection.ts`; Varro `opencode-v2-projection.ts`; OpenJet `OpenCodeV2Projection.kt`, `OpenCodeV2Events.kt` and vendored `lib/usage-limit.ts`. | Free-limit guidance and HTTP status remain visible, but the server's new `retryAfterMs` is not retained for a countdown and the OpenCode app/TUI prompt-recall flow is not mirrored. |
| Pre-existing pending-snapshot completeness difference | Upstream `rest-proxy.ts` and the expanded `rest-proxy.test.ts` reject unresolved real-session ownership before reconciliation. OpenJet `RestProxy.kt` forwards current-directory snapshots; `ProjectNotifications.kt` reconciles before asynchronous metadata loading. | Kotlin does not implement the same all-directory, all-session resolution barrier. Incomplete snapshots across content roots or unresolved session owners can differ in pending-question/permission visibility. This delta adds no upstream runtime change to that barrier; the global-form fix above does not establish parity for it. |
| Pre-existing state-root selection difference | Upstream `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can separate annotations, pauses and background-service choices across editors. No state was migrated. |
| Accepted AVIF limitation | Upstream `src/extension/remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF can show an unsupported-image error instead of a preview. |
| Pre-existing Windows inspector difference | Upstream `process-inspection.ts` and `extension.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | OpenJet inspects on demand rather than warming a persistent helper. Windows startup performance can differ. Static module preloads are already implemented in OpenJet. |
| Other pre-existing quota differences | Upstream provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ. This sync changes only the Go URL. |
| Native UI and external behavior not verified | Upstream touch/composer changes; OpenJet `WebviewHost.kt` and its vendored UI, plus `QuotaAdapters.kt`. | Actual JCEF touch/momentum and feedback layout, native Windows/Linux behavior, live Go quotas, OAuth and Handy were not exercised. No production session, credential, database or service registration was used for tests. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The historical differences and optional UI parity work above remain.

## Upstream sync review, 2026-10-22

Reviewed every changed file from Varro
`1a79bb1eb0ca90efab329d6765c58c7d90bce754` through
`61aa4b2b4ccb45bdc6722eb17cdf42fc00fa7d97`, version 0.33.18, including
extension sources and tests, shared protocol, configuration, dependencies and
verification tooling. Shared sources, host messages, contributed settings and
Node/npm pins are unchanged. SDK/client baselines advance to V1 1.19.4 and
V2 2.1.3. Support floors remain V1 1.16.0 and V2 2.0.5. The user's OpenJet
0.9.2 version selection is retained.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `src/webview/components/MarkdownRenderer.tsx`, `index.tsx`, `index.css`, `styles/math.css`; math and pending-content rendering | Copied unchanged. `webview/package.json` now explicitly declares KaTeX 0.19.0, and its lockfile is refreshed. `webview/vite.config.mts` disables CSS splitting so the Kotlin shell's single stylesheet includes chat and math styling. Fonts are packaged locally with the MIT notice at `src/main/resources/META-INF/licenses/katex.txt`. `WebviewAssetsTest` checks chat styles and resolves every emitted KaTeX font URL through the Kotlin resource handler, checking bytes, MIME types, caching and the notice. OpenJet has no restrictive `font-src` CSP to widen; its existing owned-origin handler already serves fonts. |
| `src/webview/components/MessageList.tsx`, `message/AssistantMessageContent.tsx`, `lib/measured-entrance.ts`, `lib/markdown-cache.ts`, Markdown and motion styles | Copied unchanged. Gesture mounts batch geometry reads before retaining the destination anchor; fractional entrance heights, independent Markdown append/cache reuse and a 256-entry cap avoid repeated rendering work. Incomplete links, code and math show pending dots, with reduced-motion handling. No host geometry or protocol change is required. |
| `src/extension/open-code-process.ts`; post-signal shutdown race | `ServerOwnership.kt` already waits on a retained `ProcessHandle` rather than polling executable/birth reads after ordinary shutdown. Its timeout/escalation branch now accepts exit confirmed during identity inspection. Live missing identity still retains the lease and rejects escalation. `ServerOwnershipTest` covers missing executable and birth identity for exited/live outcomes, plus pre-signal rejection without termination. All process handles in these interleaving tests are synthetic. |
| `src/extension/open-code-process.ts`; unchanged credential-only refresh during SSE verification | Kotlin `ServerOwnership.connection`, `verifyConnection` and recovery methods share the same monitor, do not compare registration object identity, and validate saved records before and after authentication. No publication rewrite is needed. New lease/companion interleaving tests verify concurrent refresh without false invalidation or lifecycle rights; password, username and saved-port changes during verification remain rejected. |
| `windows-process-inspector.ts`, native process fixtures and startup cleanup tests | Upstream's persistent-helper preparation now has a 30-second bound and bounded phase diagnostics. OpenJet's `ProcessIdentity` in `ServerOwnership.kt` uses on-demand commands, not that helper or its compilation/readiness protocol. Those helper-specific changes do not apply. Kotlin released fixtures use retained JVM process handles and `finally` cleanup, not upstream's `completeTestCleanup` aggregator. The pre-existing Windows warm-up difference remains below. |

The complete released OpenCode review covers V2 2.1.2 through 2.1.3,
`4b50f5cc` through `e965774f`, 63 commits and 298 files, and V1 1.19.3 through
1.19.4, `e3c4eb24` through `148842ef`, five commits and 48 files. Full diffs
and comparison metadata are retained under
`build/opencode-compatibility/review-2.1.3/`. The review includes HTTP/OpenAPI,
generated clients, SSE/schema, configuration, provider/model shapes,
permissions/forms, session/history/inbox and serve/upgrade behavior. V1's
comparison diverges at `60c9be092129`; the previous release's separate branch
contains only version/lockfile updates, reviewed in `v1-previous-branch.diff`.

No consumed route, method, event envelope or required record field was removed.
Experimental voice-model discovery and hosted instruction endpoints are not
consumed by Varro or OpenJet. OpenAPI catches up with optional `retryAfterMs`,
whose countdown limitation remains listed below. Provider base-URL precedence,
compaction output limits, title generation, legacy `apply_patch` permissions,
unsupported agent colors, relative-directory migration, MCP media/resource
handling, instruction deltas and provider decoding remain server-owned.
OpenJet launches with explicit ports and discovers service registrations, so the
new Windows shared-service default of 28894 requires no hard-coded port change.
App/TUI, hosted Console, Desktop and build-system changes do not require a
JetBrains host port. Both releases bundle Bun 1.4.4; the V1 delta otherwise
changes packaging/native search dependencies and tests, not its HTTP API or SDK
contract. No user database, config or service registration was migrated.

The exact published native macOS ARM64 2.1.3 binary passed both Kotlin adapter
and managed-service suites without skips through
`python3 scripts/check-opencode-compatibility.py --version 2.1.3`. Fixture HOME,
XDG, database and server-state roots are isolated, with a deterministic local
provider. Evidence is `build/opencode-compatibility/2.1.3/verification.json` and
`build/upstream-sync-61aa4b2-compatibility.log`.

Vendor integrity, all 97 OpenJet browser host tests, TypeScript checking and
browser bundling passed. npm reports no unreviewed install scripts. All 893
selected upstream unit tests and 101 Chromium cases passed, including local math
fonts, wide equations, incomplete-token indicators, accessibility semantics,
stable append, sticky previews and scrolling. These are browser-harness results,
not JCEF or real-editor performance verification. The four compatibility-helper,
seven build-script, three benchmark-checker and one vendor-integrity script tests
passed. Logs use the `build/upstream-sync-61aa4b2-` prefix. An initial narrow
Kotlin run overlapped browser output replacement and failed while hashing a
removed chunk; subsequent Gradle runs do not overlap browser builds. The first
full run then caught CSS splitting: the shell's `webview.css` contained only a
small dynamically extracted rule while chat/math styles landed in
`webview2.css`. The new asset regression failed, and the build configuration now
requires one stylesheet rather than depending on dynamic CSS injection.

`./gradlew check buildPlugin verifyPlugin` passed with the exact published
V2 2.1.3 and V1 1.19.4 binaries enabled. All 622 enabled Kotlin tests passed;
only the opt-in existing-database usage benchmark was skipped. The V1 adapter,
V2 adapter and managed-service fixtures all passed without skips. The shutdown
and credential unit regressions and the packaged-font regression passed too.
V1 evidence is `build/opencode-compatibility/1.19.4/verification.json`.
Plugin Verifier reported compatibility with all five configured IDE builds,
including IU-263.6800.24, with existing deprecated/experimental API warnings.
The final log is `build/upstream-sync-61aa4b2-final-retry.log`; the package is
`build/distributions/varro-openjet-0.9.2.zip`. The emitted single stylesheet
contains chat/math rules, and all 47 external KaTeX font references resolve
to packaged local assets.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing pending-snapshot completeness difference | Varro `src/extension/rest-proxy.ts`; OpenJet `RestProxy.kt` and `ProjectNotifications.kt`. | No equivalent all-directory ownership-resolution barrier; incomplete snapshots across content roots can differ in question/permission visibility. This backend behavior did not change in this delta. |
| Pre-existing state-root selection difference | Varro `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Distinct native roots without legacy links can separate annotations, pauses and background-service choices across editors. No state was moved. |
| Accepted AVIF limitation | Varro `src/extension/remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF may show an unsupported-image error instead of a preview. |
| Pre-existing Windows inspector difference | Varro `src/extension/windows-process-inspector.ts`, `process-inspection.ts` and `extension.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand PowerShell inspection lacks upstream's persistent-helper warm-up and preparation diagnostics. Windows startup performance and diagnostics can differ. The helper-specific timeout change is not a Kotlin runtime requirement. |
| Pre-existing quota differences | Varro provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ. No quota adapter changed in this delta. |
| Optional OpenCode UI parity, also absent in Varro | OpenCode `packages/schema/src/session-error.ts` and session UI; Varro `opencode-v2-projection.ts`; OpenJet `OpenCodeV2Projection.kt`, `OpenCodeV2Events.kt` and vendored `lib/usage-limit.ts`. | Free-limit guidance and status are visible, but `retryAfterMs` does not drive a countdown, and app/TUI prompt recall is not mirrored. |
| Pre-existing third-party notice packaging gap | Varro `NOTICES.md`; OpenJet `webview/vite.config.mts` and `src/main/resources/META-INF/licenses/katex.txt`. | The new KaTeX notice ships with the plugin, and some JVM dependencies retain notices inside their JARs, but the frontend pipeline does not collect notices consistently for all bundled dependencies. This sync is not a comprehensive license audit or a fix for that existing distribution gap. |
| Native UI and external behavior not verified | Varro math, streaming and lifecycle flows; OpenJet `WebviewHost.kt`, `WebviewAssets.kt`, `ServerOwnership.kt` and vendored UI. | No sandbox JCEF math/font/focus or real-editor streaming measurement, native Windows/Linux run, live provider, OAuth or Handy check was performed. OpenJet intentionally retains all MathML semantics because it does not publish VS Code's screen-reader-mode signal. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The historical differences above remain. No production session, credential,
database or service registration was used for tests.

## Upstream sync follow-up, 2026-10-22

Reviewed every changed file from Varro
`61aa4b2b4ccb45bdc6722eb17cdf42fc00fa7d97` through
`5ececc972cc08065f1c5bb29feb5f4a3b1412fb6`, version 0.33.19. The three
commits add local hiding of transferred links, keep parent turns pending while
descendants work, and fix inline math boundaries beside CJK text and punctuation.
The review covers extension sources and tests, shared types/protocol, browser
sources, configuration and dependencies. The only package change beyond the
version is an upstream lint-tool patch; browser dependencies, Node/npm pins,
contributed settings, SDK 1.19.4 and client 2.1.3 are unchanged. No new OpenCode
release range needs review. Both support floors and the user's OpenJet 0.9.3
version selection are preserved.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `src/extension/session-transfer-catalog.ts`, `rest-proxy.ts`, `sidebar-provider.ts`, `util/webview-message.ts`; `POST /varro/session-transfer/hide` | `SessionTransfers.kt`, `RestProxy.kt`, `VarroProjectService.kt` and `ApiRoutes.kt` persist bounded per-origin preferences, validate the session ID and open-folder scope, and invalidate all project views after saving. No server request, recycle-bin operation, helper-session hide or timestamp change occurs. Hidden links retain history authorization and metadata in paginated/full catalogs and direct reads; later moves keep the preference. Kotlin regressions cover reload through `VarroStore`, multiple origins, aggregate scope, Windows path identity, destination exclusion, foreign servers, closed roots, invalid IDs, failed persistence/retry and stale live summaries. |
| `src/shared/opencode-types.ts`, `protocol.ts`; transfer visibility fields and endpoint constant | Copied unchanged. The Kotlin projection carries `hidden` and `hiddenOrigins` separately from session activity, rather than dropping hidden summaries from snapshots. |
| `Chat.tsx`, `SessionListView.tsx`, `SessionActionsMenu.tsx`, `session-lifecycle.ts`, `client.ts` and `session-transfer.ts` | Copied unchanged. The origin link and header running badge disappear after a successful hide, without closing an active conversation. Visibility preferences survive older catalog pages and active-session refreshes. |
| `ChatInput.tsx`, `MessageList.tsx`, `ToolCall.tsx`, `subagent-visibility.ts` and related styles | Copied unchanged. Busy/retrying descendants keep Stop, queue/steer controls and loading visible after the parent's response finishes. Worked summaries wait for descendants. The waiting status appears only when a working child's card is not at least half visible. Existing Kotlin session identities and status events supply this contract; no new native OpenCode endpoint is consumed. |
| `MarkdownRenderer.tsx` | Copied unchanged. Unicode CJK boundaries permit adjacent inline formulas, including supplementary Han characters, while ASCII identifiers, prices, escaped dollars and code retain their literal treatment. Streaming uses the same boundaries. |
| KaTeX notice exclusion requested by the user | The first full Kotlin run found that `src/main/resources/META-INF/licenses/katex.txt`, referenced by the earlier asset regression and documentation, was absent from the tracked checkout. The user explicitly requested neither including nor testing that file. This sync leaves it absent and removes only the notice-presence assertion from `WebviewHtmlTest.kt`. Offline font resolution, font MIME types, immutable caching and chat stylesheet assertions remain. |

All 1,949 selected upstream unit tests passed across 12 changed test files.
All 14 Chromium subagent cases passed, including off-screen visibility, loading
and Worked-summary ownership. These browser fixtures do not establish JCEF
rendering or real-editor performance. Logs are
`build/upstream-sync-5ececc9-upstream-unit.log` and
`build/upstream-sync-5ececc9-chromium.log`.

Vendor integrity, all 97 OpenJet browser host tests, TypeScript checking and
browser bundling passed with pinned Node 24.22.0 and npm 12.2.1. The first browser
attempt lacked optional native npm packages; `npm ci --include=optional
--no-audit --no-fund` restored them without changing the lockfile. npm reports no
unreviewed install scripts. All four compatibility-helper, seven build-script,
three benchmark-checker and one vendor-integrity script tests passed.

The pinned compatibility helper passed both Kotlin adapter and managed-service
suites against the exact published macOS ARM64 OpenCode 2.1.3 binary without
skips, using isolated HOME, XDG, database and server-state roots and a local
deterministic provider. Evidence is
`build/opencode-compatibility/2.1.3/verification.json` and
`build/upstream-sync-5ececc9-compatibility.log`. The unchanged OpenCode release
range was not reviewed again; the preceding entry retains that review.

After the user's notice exclusion, `./gradlew check buildPlugin verifyPlugin`
passed with the published V1 1.19.4 and V2 2.1.3 binaries enabled. All 629 enabled
Kotlin tests passed; only the opt-in existing-database usage benchmark was
skipped. The V1 adapter, V2 adapter and managed-service suites all passed without
skips. All seven new transfer/route regressions and the retained offline-font
checks passed. Plugin Verifier reported compatibility with all five configured
IDE builds, including IU-263.6800.24, with existing deprecated/experimental API
warnings. The final package does not contain `katex.txt`.

Build/test source hashes remained unchanged during final verification. The log
is `build/upstream-sync-5ececc9-no-notice-final.log`; the package is
`build/distributions/varro-openjet-0.9.3.zip`. Concurrent CI, Marketplace publishing
and README/contributor-documentation changes were left untouched and are outside
this upstream review.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing pending-snapshot completeness difference | Varro `src/extension/rest-proxy.ts`; OpenJet `RestProxy.kt` and `ProjectNotifications.kt`. | No equivalent all-directory ownership-resolution barrier; incomplete snapshots across content roots can differ in question/permission visibility. This sync does not change pending snapshots. |
| Pre-existing state-root selection difference | Varro `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was moved. |
| Accepted AVIF limitation | Varro `src/extension/remote-images.ts`; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF may show an unsupported-image error instead of a preview. |
| Pre-existing Windows inspector difference | Varro `src/extension/windows-process-inspector.ts`, `process-inspection.ts` and `extension.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. Windows startup performance can differ. No Windows-specific code changed in this delta. |
| Pre-existing quota differences | Varro provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ. These adapters are unchanged. |
| Optional OpenCode UI parity, also absent in Varro | OpenCode `packages/schema/src/session-error.ts` and session UI; Varro `opencode-v2-projection.ts`; OpenJet `OpenCodeV2Projection.kt`, `OpenCodeV2Events.kt` and vendored `lib/usage-limit.ts`. | `retryAfterMs` does not drive a countdown, and app/TUI prompt recall is not mirrored. The baseline and this behavior are unchanged. |
| Third-party notice packaging gap | Varro `NOTICES.md`; OpenJet `webview/vite.config.mts` and `WebviewHtmlTest.kt`. | Frontend notices are not collected consistently for every bundled dependency. The user explicitly excluded `katex.txt` and its presence assertion during this sync. The KaTeX notice is not bundled or tested; this is not a license audit. |
| Native UI and external behavior not verified | Varro transfer-list, subagent and math changes; OpenJet `WebviewHost.kt`, `SessionTransfers.kt` and vendored UI. | No sandbox JCEF context-menu, multi-view reload, math or scrolling interaction, native Windows/Linux run, live provider, OAuth or Handy check was performed. The upstream Chromium run is not a substitute for those checks. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The pre-existing differences and user-selected notice exclusion above remain.
No production session, credential, database or service registration was used for
tests. No VS Code-only runtime change in this delta requires a separate JetBrains
adaptation.

## Upstream sync review, 2026-10-24

Reviewed `5ececc972cc08065f1c5bb29feb5f4a3b1412fb6` through
`191ac6d1ec69c5fd7a10380b79e9000354d645f5`, Varro 0.33.20. The comparison
includes all changed extension sources and tests, shared protocol, contributed
configuration and dependencies. Shared sources, host messages and settings did
not change. The vendored UI remains unmodified. KaTeX advances to 0.19.1,
including the Mermaid override, and the client dependency advances to 2.1.4.
No new browser package is required. Node/npm pins, V1 1.19.4 and the support
floors of V1 1.16.0 and V2 2.0.5 are unchanged. The user's OpenJet 0.9.4 version
selection is preserved.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `src/extension/context-provider.ts`; file-opening actions without a positive line | `EditorIntegration.kt` now calls `FileEditorManager.openFile` so registered image, PDF and custom editors handle response files. Positive line targets retain text-editor navigation and centered scrolling. Failed editor actions return `unavailable` rather than leaving the browser request unresolved. Four `EditorIntegrationTest` regressions cover registered-editor selection, invalid/absent lines, line offsets, unavailable editors and no text fallback or replay after failure. |
| `src/extension/image-thumbnail-shared-client.ts`; reset health-probe retries | Upstream retries `ECONNRESET` and `EPIPE` on the same authenticated thumbnail-service port, addressing competing Windows starters. `ImageThumbnails.kt` decodes in-process under an application-wide lock. OpenJet has no thumbnail listener, service authentication or bind race, so no network retry is needed. The existing codec limitations remain below. |
| `MarkdownRenderer.tsx`, `chat-input/completion.ts` | Copied unchanged. Quoted emphasis delimiters stay literal without changing source offsets or code, and a caret inside a pasted absolute path no longer opens command/skill completion. |
| Upstream dependency overrides | The browser's Mermaid/KaTeX override follows upstream. The Azure/MSAL override belongs to upstream's VS Code packaging dependencies, which OpenJet does not install. |

The released OpenCode comparison covers V2 2.1.3 through 2.1.4,
`e965774fa` through `2a3e7c0f3`, 69 commits and 580 changed files. The complete
diff was retained because the GitHub compare API truncates its file list to 300.
Review evidence is under `build/opencode-compatibility/review-2.1.4/`.
All 121 existing OpenAPI paths are identical. Three optional paths add heap-dump
diagnostics and experimental client-hosted MCP WebSocket connections and tickets.
The only changed existing OpenAPI schema adds opt-in
`experimental.background_compaction`. SSE names/payloads, session/history/inbox,
permissions/forms, model wire shapes and managed serve/upgrade arguments remain
compatible. No consumed-contract adapter change is required.

MCP elicitation location propagation, OAuth callbacks/timeouts, catalog cancellation,
parallel shutdown, startup reconciliation, compaction overflow handling, native
agent-file detection, agent model selection and V1 import ownership fixes remain
server-owned. ChatGPT catalog filtering and context caps use the existing model
contract. OpenJet retains its configuration-editing and catalog projection paths.
App, Desktop, TUI, Console, SDK declaration cleanup and release tooling do not
require JetBrains ports. The unchanged V1 release range was not reviewed again.

The exact published macOS ARM64 OpenCode 2.1.4 binary passed both Kotlin adapter
and managed-service suites without skips through
`python3 scripts/check-opencode-compatibility.py --version 2.1.4`. The fixtures
use separate HOME, XDG, database and service-state roots and a deterministic local
provider. An initial attempt overlapped source edits and failed test compilation;
the fresh rerun passed. Evidence is
`build/opencode-compatibility/2.1.4/verification.json` and
`build/upstream-sync-191ac6d-compatibility-retry.log`. Tested-version status hints
and current documentation now use 2.1.4.

Vendor integrity, all 97 OpenJet browser host tests, TypeScript checking and the
browser build passed with pinned Node 24.22.0 and npm 12.2.1. npm reports no
unreviewed install scripts. All 468 focused upstream tests passed across Markdown,
completion, side questions, file opening and shared thumbnails. The six
compatibility-helper tests and vendor-integrity script regression also passed.
Logs are `build/upstream-sync-191ac6d-webview.log` and
`build/upstream-sync-191ac6d-upstream-unit.log`.

The full pinned-release run passed `./gradlew check buildPlugin` with published
V1 1.19.4 and V2 2.1.4 fixtures enabled. All 633 enabled Kotlin tests passed,
including the four editor regressions and all three released suites without
skips. Only the opt-in existing-database usage benchmark was skipped. The log is
`build/upstream-sync-191ac6d-final.log`; the package is
`build/distributions/varro-openjet-0.9.4.zip`.

Plugin Verifier passed against that existing ZIP on all five configured IDE
builds, including IU-263.6800.24. It reported deprecated/experimental API warnings
and optional-dependency notices, with no compatibility errors. The new
registered-editor call uses `FileEditorManager.openFile`, which is deprecated on
263 but remains available across the supported matrix. The log is
`build/upstream-sync-191ac6d-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing pending-snapshot difference | Varro `src/extension/rest-proxy.ts`; OpenJet `RestProxy.kt` and `ProjectNotifications.kt`. | No equivalent all-directory ownership-resolution barrier; incomplete snapshots across roots can differ in question/permission visibility. |
| Pre-existing state-root selection difference | Varro `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Distinct native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was moved. |
| Accepted AVIF limitation | Varro `src/extension/remote-images.ts` and WASM thumbnails; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt`. | AVIF may show an unsupported-image error. The selected JVM codecs are unchanged. |
| Pre-existing Windows inspector difference | Varro `windows-process-inspector.ts`, `process-inspection.ts` and `extension.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. Windows startup performance can differ. |
| Pre-existing quota differences | Varro provider-limit service/adapters; OpenJet `QuotaAdapters.kt` and `ProviderQuotaBackend.kt`, detailed in [architecture](architecture.md#remaining-quota-parity-differences). | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ. |
| Optional OpenCode UI additions, also absent in Varro | OpenCode client MCP, heap-dump diagnostics and app/TUI controls; OpenJet `OpenCodeV2Adapter.kt`, `ApiRoutes.kt` and vendored UI. | No UI for those new optional operations. Background compaction remains a server configuration choice. Existing `retryAfterMs` diagnostics still do not drive a countdown. |
| Existing third-party notice gap | Varro `NOTICES.md`; OpenJet `webview/vite.config.mts` and `WebviewHtmlTest.kt`. | Frontend notices are not collected consistently for every bundled dependency. The prior user-selected exclusion of `katex.txt` and its presence assertion is preserved. This is not a license audit. |
| Native UI and external behavior not verified | Varro `context-provider.ts`, Markdown/completion changes; OpenJet `EditorIntegration.kt`, `WebviewHost.kt` and vendored UI. | Registered-editor rendering, JCEF Markdown/completion interactions, native Windows/Linux behavior, live providers and interactive OAuth still need manual verification. No sandbox UI or upstream Playwright run was performed. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The pre-existing differences and verification limits above remain. No production
session, credential, database or service registration was used for tests.

## Upstream sync review, 2026-10-25

Reviewed Varro `191ac6d1ec69c5fd7a10380b79e9000354d645f5` through
`172942b6b690369cd537985322dbb34a362822fd`, version 0.33.21. The review covers
changed extension sources and tests, shared protocol, package settings and
dependencies, plus the browser changes. Vendored files are copied unchanged.
SDK 1.19.4, client 2.1.4, browser dependencies, toolchain pins and support floors
are unchanged. The existing OpenJet 0.9.5 version edit is retained. No new released
OpenCode diff is required; the preceding 2.1.4 release review still applies.

| Upstream change | OpenJet alignment |
| --- | --- |
| `followup-service.ts`, `rest-proxy.ts`; `/varro/session/:id/followups` and `/followups/dismiss` | `FollowupService.kt`, `OpenCodeHostServices.kt`, `RestProxy.kt` and `ApiRoutes.kt` implement validated, project-scoped V2 stateless generation with a 30-second total deadline. Server history must confirm idle status, Build agent, completed final answer and exact visible source text. Only explicit live-turn admission can infer. Concurrent views share results and subscriber cancellation. No helper session, tools or provider retry is used. |
| `followup-cache.ts`, `file-lock.ts`, V2 annotation lock extraction | `FollowupCache.kt` and `NativeFileLock.kt` persist one admission per normalized workspace/session/user turn before inference. Failures, abstention, cancellation, model changes and restarts cannot bill the same turn again. Dismissal and accepted new prompts erase generated text without erasing admission. Shared native roots, JSON format, hashes and owner-directory locks coordinate across editors. Atomic replacement retries bounded Windows sharing violations while retaining the committed file. `OpenCodeV2SessionState.kt` reuses the lock. There is no old OpenJet workspace cache to migrate. |
| `extension-config.ts`, `opencode-config-service.ts`, initial/config messages and application-scoped package settings | `VarroSettings.kt`, `VarroConfigurable.kt`, `SharedCoreSettings.kt`, `ModelRoutingService.kt` and `VarroProjectService.kt` expose the default-enabled opt-out and optional model override. Repository settings cannot change either preference. With no override, inference retains the session model and reasoning. Settings explain the extra paid-token/subscription-quota use. |
| `opencode-v2-adapter.ts`, `opencode-v2-projection.ts`, `shared/followups.ts` | `Followups.kt`, `OpenCodeV2Adapter.kt` and `OpenCodeV2Projection.kt` validate action/source/output bounds and retain clicked labels as `metadata.varroFollowupTitle`, independently of `varroDelivery`. History, direct reads and pending inboxes preserve attribution. Ordinary typed text is not relabeled. |
| `FollowupSuggestions.tsx`, message/model UI, send/retry and selection hooks, scrolling and sticky navigation | Copied unchanged. Follow-ups preserve the composer, attachments and permissions. Detached-reader removal reserves scroll range, sticky navigation remembers its final painted anchor, and steering no longer forces bottom-follow. No additional JetBrains bridge transform or native dependency is needed. |
| VS Code configuration inspection, legacy Memento migration, test-isolation setup and fixture changes | Application-level IDE settings replace VS Code's global configuration inspection. Kotlin has no VS Code workspace Memento or async junction preparation. Tests pass explicit cache roots and use isolated released-server state. These platform mechanisms do not need a literal Kotlin port. |

The focused upstream unit run passed 1,603 tests across 18 files. One native
Windows-only cache replacement test was skipped on macOS; Kotlin separately tests
injected sharing violations, bounded failures and committed-file preservation.
All 13 upstream Chromium cases passed for follow-up send/dismiss, narrow/wide
detached-reader geometry, sticky reflow and steering. Logs are
`build/upstream-sync-172942b-upstream-unit.log` and
`build/upstream-sync-172942b-chromium.log`. These are browser-harness checks,
not native JCEF or real-editor performance verification.

The full pinned-release helper passed `check buildPlugin` with published V2
2.1.4 and V1 1.19.4 enabled. All 655 enabled Kotlin tests passed, including the
V1 adapter, V2 adapter and managed-service suites without skips. Only the opt-in
existing-database usage benchmark was skipped. The V2 fixture exercises actual
follow-up generation, cache reuse, dismissal and unchanged session history.
Kotlin regressions also cover model reasoning, stale sources, non-Build agents,
cache-only history, subscriber cancellation, timeouts, concurrent writers,
invalid output, private cache files, native/test roots and Windows replacement
failures. An initial full run exposed a macOS generic ENOTEMPTY race after another
writer released the lock; `NativeFileLock.kt` now retains that contention evidence.
The fixture was corrected to obtain parent IDs from canonical history and return
JSON through both streaming and non-streaming provider responses.

Vendor integrity, TypeScript checking, browser bundling and all 101 OpenJet
browser host tests passed. The six compatibility-helper tests and vendor-integrity
script regression passed too. Final build evidence is
`build/upstream-sync-172942b-verified.log` and
`build/opencode-compatibility/2.1.4/verification.json`. The plugin is
`build/distributions/varro-openjet-0.9.5.zip`.

Plugin Verifier passed against that existing ZIP on all five configured IDE
builds, including IU-263.6800.24. It reported existing deprecated/experimental API
warnings and unresolved optional-dependency notices, with no compatibility errors.
Its log is `build/upstream-sync-172942b-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing pending-snapshot difference | Varro `rest-proxy.ts`; OpenJet `RestProxy.kt`, `ProjectNotifications.kt`. | Incomplete snapshots across roots can differ in permission/question visibility. This delta does not change that behavior. |
| Pre-existing annotation-root difference | Varro `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can split pauses, annotations and background-service choices. The new follow-up cache uses the native shared root; no existing user state was migrated. |
| Accepted codec limitation | Varro `remote-images.ts` and WASM thumbnails; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`. | AVIF may show an unsupported-image error. These decoders are unchanged. |
| Pre-existing Windows startup difference | Varro `windows-process-inspector.ts`, `process-inspection.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. Startup performance can differ. |
| Pre-existing quota differences | Varro provider-limit service/adapters; OpenJet `QuotaAdapters.kt`, `ProviderQuotaBackend.kt`. | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ, as detailed in the architecture guide. |
| Optional OpenCode UI and notice gaps | OpenCode app/TUI and Varro `NOTICES.md`; OpenJet `OpenCodeV2Adapter.kt`, vendored UI and `webview/vite.config.mts`. | No optional client-MCP/heap-dump controls or retry countdown; frontend notices remain incompletely collected. The prior user-selected KaTeX notice exclusion is retained. None changed in this delta. |
| Native UI and external behavior unverified | Varro follow-up/scrolling flows and `file-lock.ts`; OpenJet `WebviewHost.kt`, `FollowupService.kt`, `NativeFileLock.kt` and vendored UI. | No sandbox JCEF interaction, native Windows/Linux run, live paid provider, interactive OAuth or Handy check was performed. Browser and injected-filesystem tests do not establish those results. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The pre-existing differences and verification limits above remain. The unchanged
OpenCode release range and unrelated backend behavior were not reviewed again.
Tests did not use production sessions, credentials, databases or service registrations.

## Upstream sync review, 2026-10-26

Reviewed all four commits from Varro
`172942b6b690369cd537985322dbb34a362822fd` through
`5860cbc7a89caba760cf613d3c0418506644eebc`, version 0.33.23. The comparison
includes every changed extension source and test, shared sources, package
configuration, documentation and browser source. Shared protocol, contributed
settings, SDK 1.19.4 and client 2.1.4 are unchanged. The previous OpenCode release
review still applies; this sync does not repeat that unchanged release diff.
Support floors remain V1 1.16.0 and V2 2.0.5. The user's OpenJet 0.9.6 version
selection is preserved.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `src/extension/followup-cache.ts`, `followup-service.ts`; `/varro/session/:id/followups` and shared `followups/cache.json` | `FollowupCache.kt` writes the compatible `pendingSince` field and distinguishes pending admission from a completed empty result. `FollowupService.kt` waits for another host's saved result without holding its attempt monitor or inferring twice. The 30-second wait and 35-second disk grace never release permanent admission. Closing the last subscribing editor no longer cancels an admitted generation; settings/connection invalidation, accepted new turns and host disposal still can. Failed attempts clear pending state, while transient pre-admission failures permit a fresh request. Kotlin tests cover origin cancellation, concurrent hosts, bounded/cancelled readers, grace expiry, legacy entries, late completion after dismissal, source mismatches and pre-admission cancellation. |
| `src/extension/opencode-v2-projection.ts`; failed/interrupted status history | `OpenCodeV2Projection.kt` keeps contextual model/provider/reasoning fields only when both model and provider IDs are present. `OpenCodeV2FailureTest.kt` checks missing, empty, partial and complete identities for both status outcomes, retaining errors, parent identity and completion time. The unchanged vendor skips model-less rows when announcing model switches. |
| `FollowupSuggestions.tsx`, `session-send.ts` and runtime types | Copied unchanged. Clicked actions revalidate the cache before sending and after asynchronous send preparation, so a dismissal in another window prevents stale actions without changing the main draft. No new host route or message is required. |
| `routing-state.ts`, `new-chat-draft.ts`, runtime session creation | Copied unchanged. Fresh chats restore the last explicitly chosen visible primary agent, including custom agents, with a Build fallback when unavailable. OpenJet's existing `project-storage.ts` and native `host/storage` persistence supply the saved preference without a migration. |
| `chat-input/drop-paths.ts`, `messages.css` | Copied unchanged. Web/virtual-document URLs cannot become same-path local file attachments. File-backed drops remain supported. Explored summary hit targets now fit their label and chevron rather than spanning the row. JCEF's separate native file-drop bridge still uses host-provided local paths, not URL pathnames. |
| `src/extension/macos-tray.ts` and its socket-discovery regression | The generation guard prevents stale asynchronous socket observations from disconnecting upstream's experimental macOS companion. OpenJet has no companion publisher or socket watcher. This platform-integration-specific race has no Kotlin equivalent; adding companion support would be a separate feature, not a fix to an existing JetBrains flow. |
| `package.json` | Marked advances to 18.1.2 and material-icon-theme to 5.40.0. Node advances to 24.22.1 in both `webview/package.json` and `Dockerfile`; npm stays at 12.2.1. The lockfile was refreshed with those exact tools. No browser dependency was added. |

All 613 selected upstream unit tests passed across the 13 changed test files.
The one native Windows-only cache replacement case was skipped on macOS;
Kotlin retains injected Windows replacement tests. All nine selected Chromium
cases passed, covering follow-up send/dismiss and detached geometry, activity
summary hit targets and sticky-navigation reflow. These are upstream browser
fixtures, not JCEF sandbox checks. Logs are
`build/upstream-sync-5860cbc-upstream-unit.log` and
`build/upstream-sync-5860cbc-chromium.log`.

The pinned-release helper passed `check buildPlugin` against exact published
OpenCode V2 2.1.4, with V1 1.19.4 enabled as well. All 663 enabled Kotlin tests
passed, including eight new regressions and the V1 adapter, V2 adapter and
managed-service suites without skips. Only the opt-in existing-database usage
benchmark was skipped. Fixtures use isolated HOME, XDG, database and service-state
roots and a deterministic local provider, never production sessions, credentials
or registrations. Evidence is
`build/opencode-compatibility/2.1.4/verification.json` and
`build/upstream-sync-5860cbc-final.log`.

Vendor integrity, all 101 browser host tests, TypeScript checking, browser
bundling, all six compatibility-helper tests, all seven build-script tests and
the vendor-integrity script regression passed. npm reports no unreviewed install
scripts. The plugin is `build/distributions/varro-openjet-0.9.6.zip`.

Plugin Verifier passed against that ZIP on all five configured IDE builds,
including IU-263.6800.24. It reported existing deprecated/experimental API warnings
and unresolved optional-dependency notices, with no compatibility errors. The log
is `build/upstream-sync-5860cbc-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Pre-existing pending-snapshot difference | Varro `src/extension/rest-proxy.ts`; OpenJet `RestProxy.kt`, `ProjectNotifications.kt`. | Incomplete snapshots across roots can differ in permission/question visibility. This delta does not change pending snapshots. |
| Pre-existing annotation-root difference | Varro `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can split pauses, annotations and background-service choices. The follow-up cache already uses the native shared root. No user state was migrated. |
| Accepted codec limitation | Varro `src/extension/remote-images.ts` and WASM thumbnails; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`. | AVIF may show an unsupported-image error. The selected JVM codecs are unchanged. |
| Pre-existing Windows startup difference | Varro `src/extension/windows-process-inspector.ts`, `process-inspection.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. Startup performance can differ. |
| Pre-existing quota differences | Varro provider-limit service/adapters; OpenJet `QuotaAdapters.kt`, `ProviderQuotaBackend.kt`. | Claude validation, custom probe URLs, partial xAI failures and cross-host polling still differ, as detailed in the architecture guide. No quota code changed in this delta. |
| Optional OpenCode UI and notice gaps | OpenCode app/TUI and Varro `NOTICES.md`; OpenJet `OpenCodeV2Adapter.kt`, vendored UI and `webview/vite.config.mts`. | No optional client-MCP/heap-dump controls or retry countdown; frontend notices remain incompletely collected. The prior user-selected KaTeX notice exclusion is retained. None changed in this delta. |
| Native UI and external behavior unverified | Varro follow-up, agent, drop and activity-summary flows; OpenJet `WebviewHost.kt`, `FollowupService.kt`, `FollowupCache.kt`, `webview/src/host-bridge.ts` and vendored UI. | No sandbox JCEF interaction, native Windows/Linux run, live paid provider, interactive OAuth, macOS companion or Handy application check was performed. Unit/browser fixtures do not establish those results. |

No new required backend or consumed-contract gap remains in the reviewed delta.
The pre-existing differences and verification limits above remain. Unrelated
backend behavior and the unchanged OpenCode release range were not reviewed again.

## Upstream sync review, 2026-10-27

Compared Varro `5860cbc7a89caba760cf613d3c0418506644eebc` with
`e50926dcd2ce3342e1a4dd53a527d53a3ea808ca`, version 0.33.25. The comparison
includes changed extension implementations and regressions, shared protocol, browser sources,
package configuration and documentation. The sync preserves unmodified vendor sources and the
user's OpenJet 0.9.7 version selection. The client advances to 2.2.0; SDK 1.19.4,
Node/npm pins and both support floors are unchanged. No new browser dependency is needed.

| Upstream change | Kotlin alignment and coverage |
| --- | --- |
| `native-decisions.ts`, `decision-providers.ts`, `auto-approve-judge.ts`, `rest-proxy.ts`, shared `DecisionProviderStatus` and native selection action | `NativeDecisions.kt`, `DecisionProviders.kt`, `PermissionJudge.kt` and `OpenCodeHostServices.kt` discover `/api/experimental/generate/decisions` and the base `/api/model?types=decision` catalog. Native review is an explicit application-level opt-in, uses server credentials without local helper sessions and shares Jev's typed questions/probability gates. Native errors fall back to opted-in Jev, then chat; `ask` is terminal. Malformed names, types, duplicate choices and invalid probabilities cannot approve. Tests cover status/selection validation, local read-only bypass, fallback order and disabled/old-server behavior. |
| `package.json`, `sidebar-provider.ts`, native decision Models section | `VarroSettings.kt`, `VarroConfigurable.kt` and `SharedCoreSettings.kt` expose the reviewer selection and local debug visibility gate. A hidden section does not disable a configured reviewer. No existing credential or opt-in is migrated. The shared protocol and browser section are copied unchanged. |
| `opencode-usage-limits.ts`, `provider-limit-service.ts`; `/api/experimental/usage/limits` | `OpenCodeUsageLimits.kt` and `ProviderQuotaBackend.kt` read server-owned Console/Go limits, including attach-only mode, before credential access. Scope and 1 MiB response budget are preserved. Only the generic route 404 permits local fallback; location 404, sign-out, malformed data and other errors never switch accounts. Tests cover model-specific spend, absent spend counters, Go/budget separation, strict numeric validation, integration aliases, remote reads, bounded last-good retention and sign-out invalidation. |
| `one-shot-generation.ts` | `OneShotGeneration.kt` prefers `/api/experimental/generate/text`, discovers the legacy alias only before admission and accepts the exact selected-model 404 as an unadmitted helper fallback. Generic, mismatched, implicit-model and post-admission failures do not retry. Unit and released-server tests cover the route and admission error. |
| `opencode-v2-adapter.ts` | `OpenCodeV2Adapter.kt` trusts the 2.2.0+ native chat catalog, retaining source labels and deny filtering without restoring missing models/providers or overriding server limits. Older V2 skips explicit non-chat types while retaining its fallback. Session results omit private `generationTiming` without editing annotations. Tests cover both catalog routes, old versions, config labels, exclusions, limits and unchanged annotation storage. |
| `open-code-process.ts`; recovery after temporary injected config removal | `ServerOwnership.kt` already bases handoff on private registration and fresh process/account identity, not the continued existence of an upstream temporary config directory. No config deletion or ownership relaxation is needed. A new regression verifies recovery with a missing config path and rejection after PID birth identity changes. The foreign config path is preserved rather than rewritten by the Kotlin host. |
| `commands.ts`, `extension.ts`, `session-state-manager.ts`; view placement and focus commands | VS Code-specific container registration, fork recovery and one-time sidebar migration do not apply to JetBrains. `VarroToolWindowFactory.kt`, `VarroActions.kt` and `ProjectNotifications.kt` retain native tool-window placement/focus behavior. No JetBrains layout migration is introduced. |
| `ChatInput.tsx`, attachment components, completion and composer CSS | Copied unchanged. Pinning automatic editor context preserves selected lines and exposes unpin controls. Completion avoids padding when the draft already ends with a space or tab. Existing `ContextProvider.kt`, `host-bridge.ts` and project storage supply the unchanged context contract. |

The released OpenCode comparison is V2 2.1.4 `2a3e7c0f349b` through the published
2.2.0 tag commit `1097e2668b1e`. The complete 3,109-file diff is retained under
`build/opencode-compatibility/review-2.2.0/`, along with both OpenAPI documents,
contract/runtime diffs and the path/schema inventory. Review focuses on consumed contracts
and their runtime implementations; unrelated desktop/TUI rendering and build changes are not
line-by-line porting targets.

OpenAPI grows from 124 to 130 paths. Seven additions cover text/image/decision generation,
usage limits, file watching, media reads and GUI plugins. The removed experimental voice
instructions endpoint is not consumed. Existing session/history/inbox and permission/form
payloads remain compatible. Session operations can avoid initializing inactive locations;
SSE type filtering is optional and unfiltered event names/shapes remain compatible. Inline
attachments remain the default even with experimental blob storage because OpenJet sends no
media-reference capability header. Native model typing requires the catalog fix above.
Provider prompt-cache options, integration-supplied configuration, billed-cost/compaction
accounting, plugin settlement and event-order fixes remain server-owned. Managed serve and
upgrade arguments and released service registrations remain compatible. Dev-channel service
separation does not change the private released-service root used by OpenJet.

The exact published macOS ARM64 OpenCode 2.2.0 binary passed the Kotlin adapter and
managed-service suites without skips through the pinned-release helper. Fixtures use separate
HOME, XDG, database and service-state roots, never production sessions, credentials or
registrations. The released fixture additionally covers native decision discovery, signed-out
limits, non-chat catalog exclusion and selected-model admission without provider inference.
Verification evidence is `build/opencode-compatibility/2.2.0/verification.json`.

The final pinned-release `--full-check` passed `check buildPlugin` with V2 2.2.0
and V1 1.19.4 enabled. All 680 enabled Kotlin tests passed, including 17 new tests
and the released adapter/managed-service suites without skips. Only the existing
opt-in existing-database usage benchmark was skipped. Older-V2 quota matrix fixtures
now return the exact missing-route 404 for the new capability read so they still
exercise legacy adapters. The full build log is
`build/upstream-sync-e50926d-final-recheck.log`; the ZIP is
`build/distributions/varro-openjet-0.9.7.zip`.

Vendor integrity, all 101 browser host tests, TypeScript checking, browser bundling,
all six compatibility-helper tests and npm install-script review passed. All 1,541
selected upstream unit/component tests across 15 files passed. All five Chromium
editor-context pin cases passed, including selection preservation, editor switching,
merging selected ranges and unpinning. These browser fixtures do not establish JCEF
sandbox behavior. Logs are `build/upstream-sync-e50926d-upstream-unit.log` and
`build/upstream-sync-e50926d-chromium.log`.

Plugin Verifier passed against the existing 0.9.7 ZIP on all five configured IDE
builds, including IU-263.6800.24. It reported existing deprecated/experimental API
warnings and unresolved optional-dependency notices, with no compatibility errors.
The log is `build/upstream-sync-e50926d-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing permission-judge caching difference | Varro `auto-approve-judge.ts` verdict cache; OpenJet `PermissionJudge.kt`, `NativeDecisions.kt`. | Kotlin re-evaluates requests rather than reusing equivalent-action verdicts across requests. Repeated reviews can use more inference; probability gates and actionable manual fallback are retained. |
| Existing pending-snapshot difference | Varro `rest-proxy.ts`; OpenJet `RestProxy.kt`, `ProjectNotifications.kt`. | Incomplete snapshots across roots can differ in permission/question visibility. No pending-snapshot logic changed. |
| Existing annotation-root difference | Varro `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Separate native roots without legacy links can split pauses, annotations and background-service choices. No user state was migrated. |
| Accepted codec limitation | Varro remote-image/WASM codecs; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`. | AVIF may show an unsupported-image error. Decoders are unchanged. |
| Existing Windows startup difference | Varro `windows-process-inspector.ts`, `process-inspection.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and diagnostics. Startup performance can differ. |
| Existing quota coordination/adapter differences | Varro provider-limit coordinator and adapters; OpenJet `ProviderQuotaBackend.kt`, `QuotaAdapters.kt`. | Polls are project-local, not cross-host coordinated. Claude validation, custom probe URLs and partial xAI failure behavior still differ as documented in the architecture guide. |
| Optional client features and notices | OpenCode GUI/TUI, media/watch/filter endpoints and Varro notices; OpenJet `OpenCodeV2Adapter.kt`, vendored UI, `vite.config.mts`. | No GUI-plugin runtime, image-generation UI, media-reference opt-in, SSE filtering, explicit file-watch controls, client-MCP/heap-dump controls or retry countdown. Existing frontend notice collection remains incomplete; the selected KaTeX exclusion is retained. These optional additions do not break existing consumed APIs. |
| Native UI and live-account behavior unverified | Varro editor pinning, Models and quota flows; OpenJet `WebviewHost.kt`, `NativeDecisions.kt`, `ProviderQuotaBackend.kt` and vendor UI. | No sandbox JCEF interaction, live signed-in Console/Go account, real native decision-model quality, interactive OAuth, native Windows/Linux, Handy or macOS companion check is claimed. Deterministic tests do not establish those results. |

No new required backend or consumed-contract gap remains in the reviewed delta. The existing
differences and verification limits above remain. The unchanged V1 release diff was not reviewed
again. This review does not claim complete parity for unrelated backend behavior.

## Upstream sync review, 2026-10-29

Reviewed all seven commits from Varro
`e50926dcd2ce3342e1a4dd53a527d53a3ea808ca` through
`066ad2df839ce37ecef3bc1af8b69f07282f4d06`, still version 0.33.25. The review
includes every changed extension implementation and test, shared sources,
package configuration, browser sources and documentation. Shared protocol and
contributed settings are unchanged. SDK 1.19.5 replaces 1.19.4; client 2.2.0,
Node/npm pins and support floors remain unchanged. Only `@types/node` advances
among OpenJet's browser dependencies, to 26.6.6. The lockfile uses pinned Node
24.22.1 and npm 12.2.1. The user's OpenJet 0.9.8 version selection is preserved.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `src/extension/opencode-v2-adapter.ts`; `DELETE /api/shell/:id?reason=user` | `OpenCodeV2Adapter.kt` sends the user-stop hint after verifying session ownership. Failed stops retain service tracking; successful stops clear it. `OpenCodeV2BackgroundProcessTest.kt` checks scoped and unscoped requests, failures and successful cleanup. The synced process dialog has one direct Stop action and never sends a steering prompt. Ordinary session abort is unchanged. |
| `src/extension/open-code-process.ts`; changed-port credential preparation | `OpenCodeProcess.recheckCredentialOwnership` calls synchronized `ServerOwnership.recheckAttachment` with the selected port. It never calls startup discovery or selects the retired port. Same-port recovery still verifies process identity. A new `ServerOwnershipTest.kt` concurrency fixture overlaps preparation with fresh stream-style credential verification, checks only the discovered endpoint was probed, rejects changed credentials, retains the private record and prevents lifecycle ownership or process signals. No runtime change is needed. |
| `open-code-process.test.ts`, `server.test.ts`, `opencode-startup.integration.test.ts` | Upstream separates running attach-only restart rejection from error-state reattachment. `OpenCodeServer.restart` already makes that distinction before any lifecycle operation; `AttachOnlyServerTest.kt` covers forced running-state rejection. Kotlin's exact-version managed-service fixture still exercises replacement discovery, ownership recovery and independent global-service preservation. No ownership relaxation or startup replay was ported. |
| `SessionSearch.tsx`, `session-search.ts`, `MessageList.tsx`, `client.ts` | Copied unchanged. Cmd/Ctrl+F searches prompts and responses, including older history, without prepending transcript rows while typing. Navigation loads and pins the chosen row. Existing `api/cancel`, `RestProxy.kt` request tickets and transport cancellation support aborted history reads; existing paginated message routes supply cursors. No new host endpoint or search index is needed. |
| `retained-row-heights.ts`, virtualization, `ToolCall.tsx`, assistant activity and styles | Copied unchanged. At most 2,000 numeric row estimates across four session/window generations reduce reopening mounts; fresh measurements and current zero-height projections remain authoritative. Mounted diffs survive deferred completion hydration. Tool activity no longer uses the reasoning entrance animation. |
| Todo tooltips, native-form controls, quota warnings and responsive sizing | Copied unchanged. Truncated todos use styled tooltips, native-form accessors avoid event-handler memo creation, quota warnings open reset details, and CSS replaces the retained pixel-width cap. Existing Kotlin form and quota payloads already supply the required data. |
| `docs/patches/chromium-oopif-resize.patch`, resize investigation and upstream test harness | The retained Chromium patch is a VS Code/Electron engine investigation, not extension code or a JetBrains patch. OpenJet uses JCEF through `WebviewHost.kt`; this sync does not modify the IDE browser engine or claim native clipping is repaired. VS Code fixture and runner changes need no Kotlin port. |

The released V1 review compares 1.19.4 `148842ef7cb1` with 1.19.5
`810c0eba1a73`. The complete comparison contains 13 commits and 100 changed
files. The previous tag diverges at `c0c7bae72679`; its separate branch was
checked and contains only package/lockfile version changes. Consumed HTTP/OpenAPI
routes, SSE events, configuration, model/provider shapes, permissions/questions,
session/history/parts and serve/upgrade arguments remain compatible. The PTY
authentication fix uses the selected route without changing its public path;
OpenJet does not use PTY connection tickets. GitLab provider updates, TUI worker
error propagation, unused-code removal and release tooling need no Kotlin port.
The two published SDK packages differ only in their version field. Evidence is
under `build/opencode-compatibility/review-1.19.5/`. The unchanged V2 release
range was not reviewed again.

The published macOS ARM64 V1 1.19.5 fixture passed authenticated startup,
configured reasoning variants and retired-share cleanup in isolated HOME, XDG,
database and workspace roots. Status hints and current version references now
match 1.19.5. The published V2 2.2.0 source still accepts only location fields
on shell deletion and ignores `reason=user`. OpenJet matches Varro's request,
but cannot promise silent cancellation of an agent-owned shell on that release.

Vendor integrity, all 102 OpenJet browser host tests, all six compatibility-helper
tests, all seven build-script tests and the vendor-integrity script regression
passed. npm reports no unreviewed install scripts. All 1,024 selected upstream
unit/component tests across 14 files
and all 53 selected Chromium cases passed. Browser coverage includes session
search/navigation, transcript reopening, retained diffs, direct Stop, quota
reset details and per-frame responsive layout. Logs are
`build/upstream-sync-066ad2d-upstream-unit.log`,
`build/upstream-sync-066ad2d-chromium.log` and
`build/upstream-sync-066ad2d-layout.log`. These are upstream browser fixtures,
not native JCEF verification.

The exact published macOS ARM64 V2 2.2.0 binary passed the Kotlin adapter and
managed-service suites without skips through the compatibility helper. The new
adapter fixture creates a bounded fixture-only shell, stops it through the host
route and verifies unchanged history/inbox and provider-call count. The first
full run exposed a missing directory argument in this new fixture's process
reads. It was corrected to match the UI's explicit workspace scope; the fresh
released-suite rerun passed. Evidence is
`build/opencode-compatibility/2.2.0/verification.json` and
`build/upstream-sync-066ad2d-compatibility-retry.log`. Tests never use production
sessions, credentials, databases or service registrations.

The final pinned-release `--full-check` passed `check buildPlugin` with V2 2.2.0
and V1 1.19.5 enabled. All 685 enabled Kotlin tests passed; only the opt-in
existing-database usage benchmark was skipped. All three released suites passed
without skips. TypeScript checking and browser bundling passed too. Evidence is
`build/upstream-sync-066ad2d-final-retry.log`,
`build/opencode-compatibility/1.19.5/verification.json` and the V2 evidence above.
The package is `build/distributions/varro-openjet-0.9.8.zip`.
Source/build hashes remained unchanged during final verification. The packaged
JAR contains the new search stylesheet and the Kotlin adapter.

Plugin Verifier passed against that existing ZIP on all five configured IDE
builds, including IU-263.6800.24. It reported existing deprecated/experimental
API warnings and unresolved optional-dependency notices, with no compatibility
errors. The log is `build/upstream-sync-066ad2d-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Released-server stop semantics | Varro `opencode-v2-adapter.ts`; OpenCode 2.2.0 `packages/protocol/src/groups/shell.ts`, `packages/server/src/handlers/shell.ts`; OpenJet `OpenCodeV2Adapter.kt`. | The user-stop hint is sent, but released 2.2.0 ignores it. Agent-owned completion can still notify/resume the assistant. No chat prompt is injected by the direct Stop action. |
| Existing permission-review cache difference | Varro `auto-approve-judge.ts`; OpenJet `PermissionJudge.kt`, `NativeDecisions.kt`. | Equivalent actions can trigger repeated paid reviews rather than using a cross-request verdict cache. This code did not change in the delta. |
| Existing pending-snapshot difference | Varro `rest-proxy.ts`; OpenJet `RestProxy.kt`, `ProjectNotifications.kt`. | Incomplete multi-root snapshots can differ in permission/question visibility. No pending-snapshot logic changed. |
| Existing annotation-root difference | Varro `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was migrated. |
| Accepted codec limitation | Varro `remote-images.ts` and WASM thumbnails; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`. | AVIF can show an unsupported-image error. Decoders are unchanged. |
| Existing Windows startup difference | Varro `windows-process-inspector.ts`, `process-inspection.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. Native Windows behavior was not exercised. |
| Existing quota differences | Varro provider-limit coordinator/adapters; OpenJet `ProviderQuotaBackend.kt`, `QuotaAdapters.kt`. | Cross-host polling, Claude validation, custom probe URLs and partial xAI failures retain the differences documented in the architecture guide. The changed reset-details UI reuses existing payloads. |
| Optional client features and notices | OpenCode GUI/TUI and Varro notices; OpenJet `OpenCodeV2Adapter.kt`, vendored UI, `webview/vite.config.mts`. | Optional image generation, media/watch/filter, client-MCP/heap-dump controls and retry countdown are not mirrored. Frontend notice collection remains incomplete; the user's KaTeX notice exclusion is preserved. |
| Native UI and external integrations unverified | Varro search, resize, quota and process UI; OpenJet `WebviewHost.kt`, `OpenCodeV2Adapter.kt` and vendored UI. | No sandbox JCEF search-key routing, resizing, focus or live agent-owned stop check, native Windows/Linux run, live provider, interactive OAuth, Handy or macOS companion check was performed. Chromium fixture results do not establish those results. |

No new required Kotlin host or consumed-contract gap remains in the reviewed
delta. The released-server limitation and existing differences above remain.
Unrelated backend behavior and the unchanged V2 release range were not reviewed
again. This review does not claim full backend parity.

## Upstream sync follow-up, 2026-10-29

Compared Varro `066ad2df839ce37ecef3bc1af8b69f07282f4d06` with the amended
`417a69cdfe14832752f8e0cb2e603219618aa2ef` revision. The previous revision
remains available locally, so review used the exact five-file tree diff rather
than treating the rewritten history as new UI work. Webview/shared files,
assets, host protocol, contributed settings, dependencies and toolchain pins
are identical. The vendor content hash remains
`faec3f8954b5a53655c0841e786127d526d6d30c8d4100ee520a4b72688b5713`.
Only the recorded revision/time and this report change in OpenJet. Existing
staged work, including the user's 0.9.8 version selection, is preserved.

| Upstream change | Kotlin applicability and coverage |
| --- | --- |
| `src/extension/macos-notification-helper.ts`, `native-notifications.ts`, helper tests and `native/macos-notifications/README.md`; non-forced Launch Services registration with a 30-second timeout and retry after failure | `src/main/kotlin/varro/host/ProjectNotifications.kt` uses IntelliJ `SystemNotifications` and notification groups, JVM audio playback and in-process Cocoa window-occlusion queries. OpenJet installs no `Varro.app`, runs no `lsregister` command and has no helper-registration timeout to port. `AttentionNotificationsTest.kt` retains notification admission, visibility and delivery-policy coverage. No native registration or notification was executed during this sync. |
| `src/extension/followup-cache.test.ts`; bounded Windows PowerShell handle fixture, accumulated stdout and awaited process close | This changes upstream test teardown/readiness only, not its cache implementation or shared format. `src/test/kotlin/varro/host/FollowupCacheTest.kt` injects Windows sharing/access failures instead of spawning PowerShell. It already verifies bounded retries, retained committed files, successful retry and concurrent admission. There is no equivalent child-process fixture to repair. |

SDK 1.19.5, client 2.2.0 and both support floors are unchanged. The preceding
release reviews still apply; no new OpenCode release range needed review.
No new required Kotlin host or consumed-contract gap remains in this delta.
The preceding review's gap table still applies, including the released-server
`reason=user` limitation, permission-review caching, pending snapshots,
annotation roots, AVIF, Windows inspector startup, quota coordination/adapters
and optional client/notice differences, with their upstream/OpenJet paths and
user-visible effects. None of those implementations changed here.

Vendor integrity passed. The focused upstream notification-helper, native
notification and follow-up cache suites passed 42 tests; the real Windows handle
case was skipped on macOS. Evidence is
`build/upstream-sync-417a69c-upstream-unit.log`; the reviewed five-file patch is
`build/upstream-sync-417a69c-review.diff`.

The fresh pinned-release `--full-check` passed `check buildPlugin` with V2 2.2.0
and V1 1.19.5 enabled. All 685 enabled Kotlin tests and 102 browser host tests
passed, along with TypeScript checking and browser bundling. Only the opt-in
existing-database usage benchmark was skipped. All three released suites passed
without skips, using isolated HOME, XDG, database and service-state roots.
Evidence is `build/upstream-sync-417a69c-final.log` and
`build/opencode-compatibility/2.2.0/verification.json`. Build/source content
remained unchanged during verification.

The rebuilt `build/distributions/varro-openjet-0.9.8.zip` has SHA-256
`f3050680cf47a8c4710316bc2fa43a8f84719830372953526cff6611a593194a`,
identical to the package that passed all five IDE Plugin Verifier checks in
the preceding sync. No new Plugin Verifier run was needed. Chromium was not
rerun because its sources are unchanged.

Native macOS notification delivery, real Windows handles, sandbox JCEF
interactions, native Windows/Linux execution, live providers and OAuth were
not verified. No production session, credential, database or service registration
was used. This follow-up does not claim full backend or native UI parity.

## Native verification follow-up, 2026-10-29

Reviewed the unverified checks above against the available macOS 15.7.7 ARM64
host, cached IntelliJ IDEA 2026.2.2/JBR runtime and Docker Linux ARM64 engine.
The native checks below supersede the corresponding unverified JCEF and Linux
backend entries in the preceding sync reports. No production code changed.

The macOS run used a new sandbox under
`build/native-verification-20261029/`, with separate JVM `user.home`, IDE config,
system/log directories, HOME, XDG roots, workspace and database path. OpenCode
autostart/updates were disabled and its command pointed to a nonexistent fixture
path. The IDE opened the existing scrolling harness with the actual vendored UI,
synthetic history and no OpenCode/provider connection. A disposable diagnostic
plugin, installed only in this sandbox, queried native APIs and resized only its
own windows. Provenance records plugin 0.9.8, IDE PID 62796, JCEF Chromium 144,
`com.jetbrains.cef.remote.browser.RemoteBrowser` and the sandbox plugin JAR.
The consolidated evidence is `build/native-verification-20261029/summary.json`.

### Checks run

| Check | Result and evidence | Coverage limit |
| --- | --- | --- |
| JCEF session search, pagination, navigation and focus | Passed in real JCEF. Search found historical prompt/response matches in a 260-message fixture; All prompts listed 130 prompts. Typing moved the transcript by 0 px, navigation loaded prompt 4, closed the panel and returned focus to the composer. `build/native-verification-20261029/results-ui.json`, `jcef-search.png`. | CDP key input reached JCEF. This does not verify the OS/AWT Cmd+F route or the ordinary Kotlin host bridge; history came from the synthetic host. |
| Native Swing/JCEF resizing | Passed at measured browser widths 480, 694, 974, 1274 and 480 px, by resizing the sandbox's actual floating window rather than emulating a viewport. Root width and image-prompt right alignment agreed within 1 px at each settled size. `results-ui.json`, `jcef-resized.png` in the same directory. | Settled geometry and JCEF captures do not establish frame-by-frame desktop compositor correctness during a drag, maximize or restore. |
| Native JCEF scrolling during synthetic streaming | Both `large-transcript` runs passed the harness's validity checks with no page errors or long tasks. Automatic following reached the bottom with no backward steps; the wheel-during-streaming run traversed 6,000 px and remained detached. p95 animation-frame intervals were 16.8 and 16.7 ms. `build/scroll-benchmark/cdp-dyIch7/` and `cdp-f2iQbl/` contain results, traces and viewport captures. | These are two synthetic fixture runs, not a performance comparison or complete real-conversation rendering verification. Wheel input was delivered through CDP to JCEF. |
| macOS Cocoa visibility selectors | Passed. The same `NSApplication.windows`, `isVisible` and `occlusionState` selectors used by `ProjectNotifications.kt` reported visible/unoccluded counts `1/1`, then `0/0` after hiding the sandbox windows, then `1/1` after restoring them. `build/native-verification-20261029/results.json`. | Confirms native API availability and the tested transition, not every partial-occlusion, multi-monitor or notification-suppression case. |
| IntelliJ system-notification invocation | Partial. A single labeled fixture notification was sent through the running IDE's `com.intellij.ui.SystemNotificationsImpl` while its windows were hidden; the call returned without exception. `results.json`. | No desktop-control tools were available to observe a banner. The call alone does not prove OS permission, visible delivery, sound, click-to-chat behavior or production notification admission. |
| Native Linux V2 adapter and managed service | Passed both Kotlin suites without skips against the exact published Linux ARM64 OpenCode 2.2.0 binary through `scripts/check-opencode-compatibility.py --version 2.2.0`. The container used copied current sources, isolated HOME/XDG/database/service roots and the existing build cache. `build/native-verification-20261029/linux-results/result.json`, `linux-results/test-results/`, `linux-check.log`. | Verifies the tested Linux backend paths, including native process inspection/service recovery, not Linux desktop notifications, JCEF or other architectures/releases. |
| Handy availability | Read-only discovery found `/Applications/Handy.app/Contents/MacOS/Handy`; same-user process inspection found it was not running. `build/native-verification-20261029/handy-discovery.json`. | No app launch, microphone recording, transcription command or personal log read was performed. Real dictation remains unverified. |

The first search attempt was blocked by the scrolling benchmark's diagnostic
overlay, not a production search element. The initial failed result is retained
in `results.json`; the UI-only rerun hid that fixture-only overlay and passed.
No vendor styles or application components were patched. The IDE launch log also
retains sandbox-environment startup errors: `SystemHealthMonitor` encountered a
null value with the minimal environment, and Station's Unix socket path was too
long. The completed checks establish their stated outcomes, not an error-free
IDE startup. No plugin fix or general native-startup pass is claimed for those
unrelated diagnostics.

Cleanup is recorded in `build/native-verification-20261029/cleanup.json`: the
sandbox IDE exited, fixture/debug/probe ports 50532, 50533 and 50534 closed and no OpenCode
fixture database was created by the UI run. The Linux container exited and was
removed. No production session, credentials, service registration or IDE config
was used. Only this report changes in tracked source; diagnostic scripts, plugin
and evidence remain under ignored build output.

### Still unverified

| Remaining check | Why it remains unverified / next prerequisite |
| --- | --- |
| macOS notification banners, audible output and Open chat action | Requires observation of actual desktop delivery with appropriate notification permissions and a configured fixture conversation. The native invocation and Cocoa transition above are only partial coverage. |
| OS/AWT shortcut routing, native focus ownership and production multi-view bridge flows | The JCEF checks used browser-level input and a synthetic host. Exercise Cmd+F, native dialogs, settings propagation and view ownership through the normal Kotlin-backed tool window/editor chats in an isolated IDE. |
| Desktop compositor clipping and broader UI cases | No desktop capture/control facility was available. Frame-by-frame native resizing/maximize/restore, quotas/forms/child controls and other real-editor flows remain outside this run. |
| Agent-owned process Stop notification/resumption | The released fixture covers direct shell deletion, but this run did not stop an agent-owned running tool through the native chat UI. A dedicated tool-running provider fixture or isolated live-account scenario is still needed. OpenCode 2.2.0's ignored `reason=user` hint remains a known server limitation. |
| Real Windows handles, PowerShell inspection and Windows desktop behavior | No Windows host/runtime was available. macOS and Docker Linux cannot validate Windows sharing violations or native Windows UI behavior. Existing injected sharing-failure tests do not replace that check. |
| Linux desktop UI and native x86 variants | The Docker check was headless Linux ARM64 backend execution only. A desktop-enabled Linux test environment and the relevant architecture are still needed. |
| Signed-in live providers, quota accounts, native decision-model quality and interactive OAuth | No isolated test account, credentials or paid-inference authorization was supplied. Local deterministic providers and signed-out tests do not establish those results. |
| Real Handy recording lifecycle | Handy is installed but not running. It must be deliberately started/configured with a model and microphone permissions before a user-approved recording test. No microphone was activated. |
| Upstream macOS companion | OpenJet has no companion publisher/socket watcher, as recorded in the 2026-10-26 review. This is not an implemented JetBrains operation with a runnable native parity check. |

The existing backend differences listed in the sync review remain unchanged.
These native results narrow the verification gaps; they do not establish full
backend, desktop-notification or IDE interaction parity.

## Initial-render virtualization sync, 2026-10-29

Reviewed every changed file from Varro
`417a69cdfe14832752f8e0cb2e603219618aa2ef` through
`688aca3fd9a0fd409b1c7550c1f9f0c2a3582382`, still version 0.33.25. Shared
protocol, contributed configuration, dependencies and toolchain pins are
unchanged. Tested baselines remain V1 1.19.5 and V2 2.2.0; support floors remain
1.16.0 and 2.0.5. No new OpenCode release range needed review. OpenJet's version
remains 0.9.8.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `MessageList.tsx`, `message-list/row-layout.ts`, `message-list/assistant-dialog.ts` | Vendored unchanged. Cold transcripts mount a bounded tail immediately, measure mounted rows before paint, and reconcile provisional heights without first mounting the full page. Equivalent streaming boundaries and unfinished-dialog summaries avoid copying full-history maps. |
| `hooks/runtime/open-code-runtime-instance.ts`, `styles/chat-shell.css` | Vendored unchanged. Sticky-prompt lookup skips steering, follow-up actions and automatic notices while fetching older prompt pages. The opaque sticky card ends in a fading tail. Existing Kotlin pagination and projected delivery/synthetic/follow-up fields provide the required contract; no new host endpoint is needed. |
| `src/extension/opencode-v2-adapter.ts`, background-process tests and support documentation | `OpenCodeV2Adapter.kt` removes unsupported `reason=user` and uses only `DELETE /api/shell/:id` plus optional location scope. `OpenCodeV2BackgroundProcessTest.kt` checks both request shapes, empty bodies, retained tracking after failure and cleanup after success. Session ownership checks and ordinary abort behavior are unchanged. |
| Upstream E2E fixtures, virtualization documentation and Vitest output | These are verification tooling, not additional JetBrains runtime operations. The changed browser regressions cover cold/reopened transcripts, measurements, sticky prompts, viewport coverage and typing during streaming. No VS Code-only runtime change requires a Kotlin port in this delta. |

Before the user requested pipeline-only builds, vendor integrity, all 102 OpenJet
browser host tests, TypeScript checking and browser bundling passed with pinned
Node 24.22.1 and npm 12.2.1. All 351 selected upstream tests passed across six
files, covering scrolling, virtualization, sticky previews, dialog summaries,
session-state pagination and background processes. Evidence is
`build/upstream-sync-688aca3-upstream-unit.log`; the complete reviewed patch is
`build/upstream-sync-688aca3-review.diff`.

The local full-build attempt timed out before completion and is not a passing
verification result. No local build or sandbox run was resumed after the user's
instruction. The attempted Chromium run stopped before any cases because port
4174 was already in use; the existing listener was left untouched. Its log is
`build/upstream-sync-688aca3-chromium.log`. Prior Chromium/JCEF results above do
not verify this new UI revision.

Full Kotlin/browser validation, packaging and the exact published V2 2.2.0
adapter/managed-service checks are delegated to the pushed commit's
[CI workflow](../.github/workflows/ci.yml). It runs
`python3 scripts/check-opencode-compatibility.py --version 2.2.0 --full-check`,
rejects missing/skipped released-suite tests, and uploads compatibility evidence,
test reports and the plugin archive. Fixture HOME, XDG, database and service-state
roots are isolated. CI does not run Plugin Verifier, native UI flows or the
optional published V1 suite. No production session, credentials, database or
service registration was used locally.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Released-server stop limitation, also present in Varro | Varro `opencode-v2-adapter.ts`; OpenCode 2.2.0 shell protocol/handler and job notification; OpenJet `OpenCodeV2Adapter.kt`. | Deleting an agent-owned shell can emit synthetic `Shell.NotFoundError` and resume the agent. There is no silent-stop API. OpenJet injects no prompt and does not interrupt unrelated work to hide this behavior. |
| Existing permission-review cache difference | Varro `auto-approve-judge.ts`; OpenJet `PermissionJudge.kt`, `NativeDecisions.kt`. | Equivalent actions can trigger repeated paid reviews instead of a cached verdict. |
| Existing pending-snapshot difference | Varro `rest-proxy.ts`; OpenJet `RestProxy.kt`, `ProjectNotifications.kt`. | Incomplete multi-root snapshots can differ in permission/question visibility. |
| Existing annotation-root difference | Varro `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Native roots without legacy links can separate annotations, pauses and background-service choices across editors. No state was migrated. |
| Accepted codec limitation | Varro `remote-images.ts` and WASM thumbnails; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`. | AVIF can show an unsupported-image error. |
| Existing Windows startup difference | Varro `windows-process-inspector.ts`, `process-inspection.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. |
| Existing quota differences | Varro provider-limit coordinator/adapters; OpenJet `ProviderQuotaBackend.kt`, `QuotaAdapters.kt`. | Cross-host polling, Claude validation, custom probe URLs and partial xAI failures retain the differences documented in the architecture guide. |
| Optional client features and notices | OpenCode GUI/TUI and Varro notices; OpenJet `OpenCodeV2Adapter.kt`, vendored UI, `webview/vite.config.mts`. | Optional image generation, media/watch/filter, client-MCP/heap-dump controls and retry countdown remain absent. Frontend notice collection remains incomplete. |
| New native UI revision not exercised | Upstream cold-render/sticky-prompt changes; OpenJet `WebviewHost.kt` and vendored UI. | New cold-open, sticky fade and scroll behavior still need Chromium/JCEF interaction. Native Windows/Linux runs, live agent-owned Stop, providers, OAuth and Handy were not repeated. The earlier native follow-up applies only to its recorded snapshot. |

No new required Kotlin host or consumed-contract implementation gap remains in
this reviewed delta. Completion still requires successful exact-commit CI; the
local timeout and historical checks do not establish it. The existing differences
above remain, and unrelated backend behavior was not reviewed again.

## OpenCode 2.2.1 sync review, 2026-10-29

Reviewed every changed file from Varro
`688aca3fd9a0fd409b1c7550c1f9f0c2a3582382` through
`75c523daedbbe819c54125077a99fc53962fd7ec`, still version 0.33.25.
Webview and shared sources, host messages, contributed settings and the vendor
content hash are unchanged. The V1 SDK remains 1.19.5; the V2 client advances
to 2.2.1. Node advances to 24.22.2 in `webview/package.json` and `Dockerfile`,
with npm unchanged at 12.2.1. The lockfile updates only the client and its
protocol/schema packages. OpenJet remains 0.9.8 and both support floors are unchanged.

The only upstream runtime host edit is the request-type rename in
`src/extension/native-decisions.ts`, from `GenerateDecisionsInput` to
`ExperimentalGenerateDecisionsInput`. OpenJet's `NativeDecisions.kt` uses the
unchanged HTTP path and JSON contract directly, so no adapter rewrite is needed.
`NativeDecisionsTest.kt` covers path-based discovery independent of generated-client
namespaces. `OpenCodeStatusPresentation.kt` now advertises tested V2 2.2.1, with
regression coverage for the previous 2.2.0 update hint and manifest alignment.
Other Varro changes are documentation and performance/settings test fixtures,
not new JetBrains runtime operations.

The complete released OpenCode comparison covers the previous OpenJet-reviewed
2.2.0 tag, `1097e2668b1e`, through the 2.2.1 tag, `3ebcf9a32c7b`, across 701 files.
Evidence is retained under `build/opencode-compatibility/review-2.2.1/`, including
the complete diff, both OpenAPI documents, focused contracts and path/schema inventory.
All 130 existing paths retain their request/response contracts; nine experimental
PTY/log path entries change only their generated operation IDs. Four new paths
provide opt-in experimental schedules. SSE, session/history/inbox, permission/form,
credential and shell-stop contracts remain compatible. Configuration adds timezone,
schedules and image generation, and model variants no longer override prompt-cache
settings. Kotlin preserves returned native configuration and catalog variants.

Provider authentication/settings isolation, catalog filtering, context headroom,
throttling, snapshots, Code Mode and execution/idle-hook fixes remain server-owned.
The added schedule database table does not change OpenJet's read-only history/usage
queries. Serve/upgrade arguments remain compatible; dev-channel service discovery
now shares the released registration, while OpenJet continues using explicit ports
and verified registrations. OpenCode app/TUI, Desktop, hosted Console and persistent
PTY daemon changes do not require JetBrains host ports. Goal/schedule/image controls
are optional client features, not changed Varro host requirements.

The exact published macOS ARM64 OpenCode 2.2.1 binary passed both Kotlin adapter
and managed-service suites without skips through
`python3 scripts/check-opencode-compatibility.py --version 2.2.1`. Fixtures use
isolated HOME, XDG, database and service-state roots and a deterministic local
provider. Evidence is `build/opencode-compatibility/2.2.1/verification.json` and
`build/upstream-sync-75c523d-compatibility.log`. Vendor integrity, all 102 browser
host tests, TypeScript checking, six compatibility-helper tests and seven build-script
tests passed. npm reports no unreviewed install scripts. No extension packaging,
browser bundling, Docker image build, Plugin Verifier or sandbox launch was run,
as requested.

The final `./gradlew test -PskipWebview=true` run passed 686 Kotlin tests,
including the published V1 1.19.5, V2 2.2.1 and managed-service suites without
skips. Only the opt-in existing-database usage benchmark was skipped. The log is
`build/upstream-sync-75c523d-kotlin-tests.log`. Kotlin test compilation and its
instrumented test JARs ran; `buildPlugin` and the webview build did not.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing released-server Stop limitation | OpenCode shell protocol/handler and job notifications; Varro `opencode-v2-adapter.ts`; OpenJet `OpenCodeV2Adapter.kt`. | Agent-owned shell deletion can notify/resume the agent; 2.2.1 still has no silent-stop API. |
| Existing permission-review cache difference | Varro `auto-approve-judge.ts`; OpenJet `PermissionJudge.kt`, `NativeDecisions.kt`. | Equivalent actions can trigger repeated paid reviews rather than reuse a cached verdict. |
| Existing pending-snapshot difference | Varro `rest-proxy.ts`; OpenJet `RestProxy.kt`, `ProjectNotifications.kt`. | Incomplete multi-root snapshots can differ in permission/question visibility. |
| Existing annotation-root difference | Varro `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Native roots without legacy links can separate annotations, pauses and background-service choices across editors. No user state was migrated. |
| Accepted codec limitation | Varro `remote-images.ts` and WASM thumbnails; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`. | AVIF can show an unsupported-image error. |
| Existing Windows startup difference | Varro `windows-process-inspector.ts`, `process-inspection.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt`. | On-demand inspection lacks persistent-helper warm-up and preparation diagnostics. |
| Existing quota differences | Varro provider-limit coordinator/adapters; OpenJet `ProviderQuotaBackend.kt`, `QuotaAdapters.kt`. | Cross-host polling, Claude validation, custom probe URLs and partial xAI failures retain the differences documented in the architecture guide. |
| Optional client features and notices | OpenCode goal/schedule/image/media/watch/filter/client-MCP UI; Varro notices; OpenJet `OpenCodeV2Adapter.kt`, vendored UI, `webview/vite.config.mts`. | Dedicated optional controls and retry countdown remain absent; frontend notice collection remains incomplete. |
| Native UI and external behavior not repeated | Varro host/UI; OpenJet `WebviewHost.kt`, vendored UI and native integrations. | No JCEF/Chromium interaction, native Windows/Linux run, live agent-owned Stop, provider, OAuth or Handy check was performed for this baseline. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. The pre-existing differences above remain; unrelated backend
behavior and optional OpenCode UI implementations were inventoried, not reverified
for full parity. No production session, credential, database or service registration
was used for testing.

## Loading-dot sync follow-up, 2026-10-29

Reviewed the complete six-file diff from Varro
`75c523daedbbe819c54125077a99fc53962fd7ec` through
`01f3cf89ecbc5ca2b9074cff36aa24e99114ba04`, still version 0.33.25.
The sole commit replaces utility-sized startup dots in `src/webview/App.tsx`
and `components/ServerStatus.tsx` with the shared `.loading-dot` rule in
`styles/messages.css`. Startup and transcript dots now have fixed 8-pixel
dimensions, minimum dimensions, a square aspect ratio and no flex shrinking.
Sources are vendored unchanged.

Upstream `src/extension`, `src/shared` and `package.json` have no changes.
No endpoint, message, configuration, persistence, server-adapter or
platform-specific host behavior changed, so no Kotlin port is required.
SDK 1.19.5, client 2.2.1, browser dependencies, Node/npm pins and support floors
are unchanged. The preceding review's OpenCode release comparison remains
applicable; there is no new released range to review.

Vendor integrity, all 102 OpenJet browser host tests, TypeScript checking and
browser bundling passed with pinned Node 24.22.2 and npm 12.2.1. All 34 upstream
App/ServerStatus unit tests passed. Both new upstream Chromium regressions passed,
checking connecting and workspace dots at font sizes 10, 13 and 16 pixels in
320-by-120 and 1280-by-900 viewports. These are isolated browser fixtures, not
JCEF sandbox verification.

This follow-up left the full Kotlin/build and exact published V2 2.2.1 run
pending. The file-citation sync below completes those checks and fixes the
ownership-election race found by CI.

No new required backend or consumed-contract gap remains in the reviewed delta.
The preceding review's remaining-differences table still applies, including its
upstream/OpenJet paths and user impact: shell-stop notifications, permission-review
caching, pending snapshots, annotation roots, accepted AVIF decoding limits,
Windows inspector warm-up, quota coordination/adapters, optional client controls
and frontend notices. None changed here or was reverified for full parity.
No sandbox IDE, native Windows/Linux, live provider, OAuth or Handy interaction
was performed. No production session, credential, database or service registration
was used for testing.

## File-citation sync and CI ownership fix, 2026-10-29

Reviewed all three Varro commits from `01f3cf89ecbc5ca2b9074cff36aa24e99114ba04`
through `e60613b07c0f4f71628df97fd66a43d596881632`, version 0.33.27. The comparison
includes all changed host, shared, configuration and browser sources. SDK 1.19.5, client 2.2.1, Node 24.22.2,
npm 12.2.1 and support floors are unchanged. No new OpenCode release range or
browser dependency needs review. Vendored sources are unmodified.

| Upstream change | OpenJet handling |
| --- | --- |
| `RichComposerArea.tsx`, multiline native insertion | Vendored controlled newline insertion preserves the composer value and caret without introducing browser block nodes. No Kotlin input change is needed. |
| `MarkdownRenderer.tsx`, message/side-question renderers and `lib/bridge.ts` | Vendored links carry the owning session, directory and full line range. Render caches include the directory. Local URI escapes decode once; encoded hash/colon characters remain part of the filename. Cancellation does not disable the link, and native chooser waits use the longer request timeout. |
| `sidebar-provider-actions.ts`, `context-provider.ts`, `util/webview-message.ts`, shared `vscode/open`/`vscode/open-result` | `FileReferences.kt` validates ranges and authorizes nested/project session directories using fresh server records and the history scope. It prefers exact session paths, bounded complete-suffix matches, then the owning workspace. `EditorIntegration.kt` offers duplicate-file selection, preserves cancellation and clamps inclusive line selections. `VarroProjectService.kt` returns a correlated unavailable result on validation/authorization failure instead of leaving the browser promise pending. |
| Vite E2E cache/worker discovery, harness and fuzzy composer reads | Upstream test/development tooling only. OpenJet does not run those E2E servers or use the VS Code fuzzy controller in production. No Kotlin port is needed. |

CI run `40840054379` failed in `ServerOwnershipTest` while eight hosts competed
for a claim. `Files.isRegularFile` returned false when another host removed the
claim between existence and type checks. `ServerOwnership.kt` now uses a
throwing no-follow attribute read, allowing the existing missing-file handler to
treat disappearance as contention. Non-regular, symlink and public records remain
rejected. The regression repeats the eight-host election 100 times and verifies
that unsafe claims are retained.

Verification passed with pinned Node 24.22.2 and npm 12.2.1:

- `python3 scripts/check-opencode-compatibility.py --version 2.2.1 --full-check`
  ran `check buildPlugin --rerun-tasks` with published V1 1.19.5 enabled as well.
  All 700 enabled Kotlin tests passed. The optional local-database usage report
  was skipped; V1, V2 and managed-service suites had no skips. V2 fixtures use
  isolated HOME/XDG/database/service roots. Evidence is
  `build/opencode-compatibility/2.2.1/verification.json` and
  `build/varro-e60613b-final-check.log`. The plugin ZIP is
  `build/distributions/varro-openjet-0.9.8.zip`.
- `npm run test:host`, `npm run typecheck`, `npm run sync:check` and browser
  bundling passed, including all 106 browser host tests. New contract coverage
  accepts cancellation results and rejects unknown statuses.
- Selected upstream `npm run test -- ...` passed 943 tests across nine files:
  context provider, sidebar actions, message validation, Markdown, message parts,
  side questions, rich composer, assistant content and bridge. These are unit
  fixtures, not live IDE or native-input verification. Evidence is
  `build/varro-e60613b-upstream-tests.log`.
- All five Python script-regression commands passed, 56 tests total. Actionlint
  1.7.12, shell syntax checks and Ruff 0.17.1 passed. Ruff was installed in an
  isolated build venv after the host Python reported it missing. `git diff --check`
  passed for staged and unstaged changes.

GitHub's Varro `main` still resolved to `e60613b07c0f` after verification. The
OpenJet changes were not committed or pushed, so GitHub CI has not run this fix.

No new required Kotlin implementation gap remains in the reviewed delta. The
pre-existing differences in the OpenCode 2.2.1 review table above remain unchanged,
including their upstream/OpenJet files and user-visible effects. They were not
reverified for full parity. The chooser and editor selection have not been exercised
in a sandbox IDE; native Windows/Linux, live providers, OAuth and Handy remain
unverified for this sync. The new upstream tooling changes are not JetBrains host
requirements. Tests do not use production sessions, credentials, databases or
service registrations.

## Setup and file-link sync, 2026-10-29

Reviewed the complete 22-file change from Varro
`e60613b07c0f4f71628df97fd66a43d596881632` through
`e453def1f5b6a328592eb910e08f0c5e7cb72aea`, still version 0.33.27. The review
includes all changed `src/extension`, browser and configuration sources. Shared
sources, protocol messages, persistence and server adapters did not change.
SDK 1.19.5, client 2.2.1, support floors, browser dependencies and Node/npm pins
are unchanged. There is no new OpenCode release range to review.

| Upstream change | Kotlin/JetBrains handling |
| --- | --- |
| `open-code-process.ts`, missing-CLI message | `OpenCodeCli.kt` now gives the same concise V2 installation command. Its missing-CLI classifier recognizes that message, and `OpenCodeCliSetupTest.kt` covers the notification, spawn failures and distinct configured-path failure. V1 remains supported. |
| `App.tsx`, `ServerStatus.tsx` | Vendored startup opens provider management and the connection dialog after essential initialization when no providers exist. Closing the dialog does not remount chat or repeatedly reopen it. The fallback status view uses the same provider dialog. Existing Kotlin `/provider`, `/provider/auth`, `/auth/:id` and OAuth routes already implement its requests. Setup command cards use existing `terminal/run`, settings, output and safe restart actions. |
| `MarkdownRenderer.tsx` | Vendored inferred code-path links preserve their written label and remain plain code in illustrative prose. Explicit links remain actionable, and forged renderer metadata is stripped. The previous sync's `FileReferences.kt` and `EditorIntegration.kt` continue handling session scope and inclusive line ranges; no new host payload is consumed. |
| `status-icons.ts`, `styles/prompts.css` | Vendored warning dots, compact command fields and centered run/copy controls. No Kotlin rendering changes are needed. |
| `package.json`, `assets/workbench-icon.svg`, VS Code fork documentation | Secondary-before-Primary registration and monochrome workbench masks apply to VS Code/VSCodium/Cursor containers. JetBrains uses its own tool-window registration and SVG, so these manifest/assets changes are not copied into the plugin. Upstream's color in-chat icon is unchanged. |

Verification passed with pinned Node 24.22.2 and npm 12.2.1:

- `python3 scripts/check-opencode-compatibility.py --version 2.2.1 --full-check`
  ran `check buildPlugin --rerun-tasks`, with the published V1 1.19.5 fixture also
  enabled. All 703 enabled Kotlin tests passed. One optional local usage-report
  test was skipped; V1, V2 and managed-service suites had no skips. The exact
  published V2 2.2.1 tests used isolated HOME/XDG/database/service roots.
  Evidence is `build/opencode-compatibility/2.2.1/verification.json` and
  `build/varro-e453def-full-check.log`. The ZIP is
  `build/distributions/varro-openjet-0.9.8.zip`.
- Vendor integrity, all 106 browser host tests, TypeScript checking and browser
  bundling passed. The selected upstream unit run passed 1,106 tests across
  eight files covering CLI startup, extension registration, context resolution,
  App routing, status states, Markdown, messages and status icons. Evidence is
  `build/varro-e453def-upstream-tests.log`.
- Four isolated macOS JCEF checks passed in IntelliJ IDEA 2026.2.2. They verified
  empty-provider startup and dialog dismissal, missing-CLI install intent without
  restart, invalid-path settings intent without restart, and illustrative versus
  actionable code-path rendering with the full line range preserved. There were
  no JavaScript page errors. Result and screenshots are under
  `build/upstream-sync-e453def/`. These checks loaded the upstream synthetic host
  in the plugin's real JCEF browser, not the ordinary Kotlin bridge. They did not
  execute an install command, open real settings/files, save credentials or sign
  into a provider. The isolated IDE logged clean-profile environment/plugin
  warnings and a `SystemHealthMonitor` initialization error; those logs are retained
  in `ide.log` and did not fail the checked UI flows.
- The fixture used a fresh IDE config, JVM home, HOME/XDG/database/service roots
  and project. It verified the loaded plugin's sandbox path and IDE PID. Cleanup
  shut down that IDE and stopped the fixture Vite child left by npm, after checking
  its exact command and cwd. `build/upstream-sync-e453def/cleanup.json` records
  exited fixture processes and no remaining listeners on ports 50532, 50533 or
  50534. No production state was used.

Varro `main` still resolved to `e453def1f5b6` after the checks. The changes are
not committed or pushed, and GitHub CI has not run this revision. The previous
uncommitted CI fix and citation sync remain intact.

Remaining behavior and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Shared upstream setup-action issue | Varro `ServerStatus.tsx` offers Run for the configured executable, but `src/extension/util/webview-message.ts` only accepts authored terminal commands. OpenJet `TerminalService.kt` retains that allowlist. | A custom configured path's Run button is refused in both hosts. Use Open Settings to repair the missing path. The sync does not authorize arbitrary browser-provided shell text. |
| Existing backend differences | The OpenCode 2.2.1 review's remaining-differences table maps upstream files to `OpenCodeV2Adapter.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt`, `OpenCodeV2SessionState.kt`, image codecs, `ProcessIdentity`, quota adapters and optional frontend controls. | Shell-stop notifications, repeated paid reviews, incomplete cross-root pending snapshots, annotation-root separation, AVIF failures, Windows inspector warm-up, quota coordination and optional controls retain their previously documented differences. None changed in this delta. |

No required Kotlin alignment gap remains in the reviewed changes. The shared
configured-command action issue above remains visible rather than weakening the
terminal allowlist. Unrelated backend behavior, native Windows/Linux, live provider
sign-in, OAuth and Handy were not reverified. Tests use isolated state, never
production sessions, credentials, databases or service registrations.

## Cleanup, annotations and OpenCode 2.2.3 sync, 2026-10-30

Reviewed all ten commits from Varro
`e453def1f5b6a328592eb910e08f0c5e7cb72aea` through
`8ec5a53939ecf9b790a5be4f1d22ad7d8947c8ca`, version 0.33.28. The comparison
includes changed extension sources and tests, shared contracts, contributed
commands/configuration, dependencies and browser sources. Vendored sources are
unchanged copies. Solid advances to 1.9.18, its Vite plugin to 2.11.15 and the
OpenCode client to 2.2.3. No new browser package is needed. Node 24.22.2, npm
12.2.1, SDK 1.19.5, both connection support floors and OpenJet 0.9.8 are unchanged.

| Upstream change | Kotlin alignment and coverage |
| --- | --- |
| `session-cleanup-service.ts`, `usage-report-service.ts`, `session-cleanup-progress.ts`, `file-lock.ts`; confirmed global cleanup and artifact recovery | `HistoryMaintenance.kt`, `SessionCleanup.kt`, `UsageArchive.kt`, `ArtifactCleanup.kt` and `NativeFileLock.kt` implement the retention/preservation/confirmation flow, calendar cutoff, recursive-tree validation, durable usage-before-delete ordering, post-archive validation, deletion confirmation and batched shared-file checks. Cancellation cannot abandon a dispatched deletion. Regressions cover active/newer descendants, retained parents, changed metadata, pending inbox work, missing-location errors, cancellation, matching-database admission, shared V1/inbox references, symlinks and forged journal paths. |
| Dormant V1 cleanup and `usage-original-timing.ts` | `LegacySessionCleanup.kt`, `OriginalUsageTiming.kt` and `LocalUsageDatabase.kt` preserve original timing separately, validate archive versions, merge live/archive snapshots without double counting and keep newer V2 token counts. Offline deletion holds a live write transaction through validation, preservation and removal, rejects unknown triggers/cross-version foreign keys and leaves retained V2 records unchanged. Tests cover writer exclusion, child ordering, conflicts, malformed history, cancellation, missing source databases and multiple attachment-directory provenance. |
| `session-transcript-archive.ts`, `local-session-transcript.ts` | `TranscriptArchive.kt` stores the compatible version-one parent transcript schema, preserves text attachments and explicit idle outcomes, omits tools/reasoning/media, prefers separately stored parts, and uses native pagination for forks/reverts. Limits, stale source checks and cancellation fail closed. Tests cover authoritative parts, text blobs, partial interrupted replies, inferred boundaries, terminal empty pages, repeated cursors, unavailable attachments, child omission and reverted V1 history. |
| `database-compaction.ts`, `server.ts`, `about-database.ts`, About/command registration | Native actions in `plugin.xml` and `HistoryMaintenance.kt` replace VS Code commands and modal/progress APIs. `OpenCodeServer.withStoppedServer` reserves local and shared ownership through SQLite closure and restart; `OfflineMaintenancePreflight.kt` checks active execution, idle background services and location-owned forms without opening historical directories. `DatabaseMaintenance.kt` checks links, free space, exclusive access, WAL and integrity. `AboutDatabase.kt` reads physical counts, creation age, WAL size and reusable pages without scanning message bodies. The existing native Markdown About report replaces upstream's HTML card/polling layout. |
| All-time usage CSV command | `UsageCsv.kt` exports daily UTC model/variant/token/cost aggregates from the same live/archive selection. It blocks spreadsheet-formula identifiers and database aliases, stages output before atomic replacement and leaves the destination untouched on cancellation. Local-only admission and explicit output limits remain. |
| `context-provider.ts`, `citation-file-search.ts`, `rest-proxy.ts`, `shared/protocol.ts`; `/varro/citation-paths`, exact opens and prompt guidance | `CitationPaths.kt`, `ApiRoutes.kt`, `RestProxy.kt`, `FileReferences.kt`, `EditorIntegration.kt` and `VarroProjectService.kt` authorize bounded exact metadata checks, cache hits/misses, keep relative references inside their root, avoid namesake discovery for validated links and supply citation guidance on sends. Legacy discovery has time/entry/result budgets and respects JetBrains exclusions. Tests cover namesakes, aliases, cache expiry, invalid payloads, authorization, exact opens, exclusions and cancellation. VS Code glob/sibling settings are platform-specific; JetBrains uses its project file index. |
| `shared/chat-annotation.ts`, `message-content.ts`, browser annotation/copy-selection UI | Vendored annotation editing, queue/draft behavior, Markdown copying and styling are reused unchanged. `ChatAnnotations.kt`, `TextAttachments.kt` and `MessageContent.kt` retain bounded annotation previews before deferring originals, with strict UTF-8 and the shared 64 KiB text limit. Regressions include source attribution, quoted headings, malformed text, long percent-encoded Unicode and ordinary pasted files. |
| OpenCode 2.2.2 local-summary fix | `LocalSessionSummary.kt` defers V2 records to native API hydration, including separately stored parts, instead of accidentally using a dormant V1 namesake. OpenJet did not have upstream's local V2 summary implementation to patch. |
| Portable extension fixtures | Upstream test-only SQLite, worker and VS Code mocks have no Kotlin runtime operation to port. Kotlin tests use explicit temporary databases, JDBC and retained JVM process handles. |

The complete OpenCode release review spans 2.2.1 through 2.2.3,
`3ebcf9a32c7b` through `b44b7164ba`, across 947 files. Evidence under
`build/opencode-compatibility/review-2.2.3/` includes the full diff, both OpenAPI
documents, route/schema inventory and focused runtime contracts. All 134 paths
and their methods remain. Generated schemas flatten equivalent unions and
deduplicate native/form definitions. Numeric parameters acquire safe-integer
bounds; Kotlin's bounded integer shell cursors remain valid. Session `time.busy`
and attachment `source` are optional. SSE event names, history/inbox envelopes,
permissions/forms, provider/model wire identities and serve/upgrade arguments
remain compatible.

Native history now hydrates `session_part` before media. The local transcript
reader handles that table explicitly; normal Kotlin history already uses the API.
Managed policy adds integration/account, MCP, skill and plugin decisions, and
credential/connect policy errors remain visible through existing error handling.
Modern native catalogs remain authoritative, so configured fallback does not
restore server-excluded providers/models. Claude Agent external authentication,
provider routing, image-job notices, media fork/import handling, Code Mode fetch
permissions/progress, portable shell scanning and schedule conditions remain
server-owned. OpenCode app/TUI/desktop, optional schedules/media helpers and
direct AI/OpenTUI plugin-export changes require no JetBrains HTTP adapter port.
New Go/ChatGPT limit diagnostics retain their message/status in history and live
projection tests. V1's unchanged released contract was not reviewed again.

The exact published macOS ARM64 2.2.3 binary passed both Kotlin compatibility
suites without skips. The adapter fixture also runs actual cleanup with usage
and parent transcript preservation before deleting its own fixture conversation.
The managed-service fixture verifies ownership reservation and restoration after
successful and failed offline operations. Initial runs exposed blocked preflight
reads during the restart phase and reacquisition of an already-held launch claim;
the maintenance phase and pre-held-claim startup path fix both. Fixture HOME,
XDG, database, annotations and service registrations are isolated. Evidence is
`build/opencode-compatibility/2.2.3/verification.json` and
`build/upstream-sync-8ec5a53-compatibility-final.log`.

The final pinned-release `--full-check` passed `check buildPlugin` with V2 2.2.3
and V1 1.19.5 enabled. All 745 enabled Kotlin tests passed across 117 suites;
only the opt-in existing-database usage benchmark was skipped. All four released
test cases passed without skips. Vendor integrity, all 106 browser host tests,
TypeScript checking and browser bundling passed. The six compatibility-helper
regressions and vendor-integrity script test passed too. Evidence is
`build/upstream-sync-8ec5a53-full-check.log`; the package is
`build/distributions/varro-openjet-0.9.8.zip`.

All 278 focused upstream unit/component tests passed across 13 files, and all
14 selected Chromium cases passed for annotations, Markdown copying/rendering
and delayed exact-citation validation. Logs are
`build/upstream-sync-8ec5a53-upstream-tests.log` and
`build/upstream-sync-8ec5a53-chromium.log`. They use the synthetic upstream host,
not the Kotlin bridge or a real provider. No upstream source was modified.

Plugin Verifier passed against that existing ZIP on all five configured IDE
builds, from IU-252.28539.97 through IU-263.6800.24, with no compatibility
errors. It reported deprecated/experimental API warnings and optional-component
layout/dependency notices. The initial command exceeded its two-minute execution
limit before producing a result; the longer retry passed. Its log is
`build/upstream-sync-8ec5a53-verifier-retry.log`. The verified ZIP has SHA-256
`fabc16c2b7403a554948a73408b563f7be236d955f0cef3f0a55c8803cb58ff4`.

Remaining differences and verification limits:

| Status | Upstream and OpenJet code | User-visible impact |
| --- | --- | --- |
| Retained bounded all-time scans | Varro `usage-report-service.ts` now has an unbounded all-time report path; OpenJet `LocalUsageDatabase.kt`, `UsageReport.kt`, `OriginalUsageTiming.kt` retain explicit scan/aggregation/provenance bounds. | Very large histories can report a limit error rather than produce an unbounded report. Cleanup fails before deletion if preservation exceeds its bounds. No partial report is represented as complete. |
| JetBrains attachment-cache difference | Varro `session-cleanup-service.ts` targets its owned drop/transfer formats; OpenJet `AttachmentStore.kt` also stores UUID copies under the IDE system directory. `ArtifactCleanup.kt` only authorizes the shared Varro formats. | Those pre-existing IDE-cache copies remain. Cleanup does not guess ownership or broaden deletion to unrelated IDE caches. |
| Existing released-server Stop limitation | OpenCode shell protocol/job notifications; Varro `opencode-v2-adapter.ts`; OpenJet `OpenCodeV2Adapter.kt`. | Deleting an agent-owned shell can still notify/resume the agent; 2.2.3 has no silent-stop API. |
| Existing permission-review and pending-snapshot differences | Varro `auto-approve-judge.ts`, `rest-proxy.ts`; OpenJet `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt`. | Equivalent actions can repeat paid reviews; incomplete multi-root snapshots can differ in permission/question visibility. These implementations did not change here. |
| Existing annotation-root difference | Varro `varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt`. | Native roots without legacy links can separate annotations, pauses and background-service choices. No user state was migrated. |
| Accepted AVIF and Windows startup differences | Varro WASM thumbnails and persistent inspector; OpenJet `RemoteImages.kt`, `ImageThumbnails.kt`, `ProcessIdentity` in `ServerOwnership.kt`. | AVIF can be unsupported. Windows inspection remains on demand, without helper warm-up/diagnostics. These native paths were not exercised on Windows. |
| Existing quota differences | Varro provider-limit coordinator/adapters; OpenJet `ProviderQuotaBackend.kt`, `QuotaAdapters.kt`, detailed in the architecture guide. | Cross-host polling, Claude validation, custom probe URLs and partial xAI failures retain their documented differences. |
| Optional client UI and notices | OpenCode app/TUI and Varro notices; OpenJet `OpenCodeV2Adapter.kt`, vendored UI and `webview/vite.config.mts`. | Dedicated goal/schedule/image/media/client-MCP controls and retry countdown remain absent. Frontend notice collection remains incomplete; the previous user-selected KaTeX notice exclusion is unchanged. |
| Native UI and external integrations not verified for this revision | Varro annotation/citation and maintenance flows; OpenJet `WebviewHost.kt`, `HistoryMaintenance.kt`, native actions and vendored UI. | No sandbox JCEF dialog/focus/clipboard/cleanup-wizard interaction, native Windows/Linux run, live paid provider, interactive OAuth or Handy test was performed. Automated browser fixtures are not native IDE verification. |

No additional required consumed-contract implementation gap was found in the
reviewed delta. The explicit bounded/cache differences above remain; this is not
a claim of full backend or native UI parity. Tests never used production history,
credentials, databases or service registrations.

## Claude Agent and OpenCode 2.2.5 sync, 2026-10-31

Reviewed Varro `8ec5a53939ecf9b790a5be4f1d22ad7d8947c8ca` through
`1b96a544f15db20f5914109da6830a449d9c45e8`, version 0.33.31. The comparison
includes all changed extension sources and tests, shared contracts, package
configuration, dependencies and browser sources. No contributed setting or host
API version changed. The SDK remains 1.19.5, the client advances to 2.2.5, and
both support floors and the Node/npm pins remain unchanged. The only lockfile
changes are the client and its matching protocol/schema packages. The existing
user change to OpenJet 0.9.10 in `gradle.properties` is preserved.

| Upstream change | OpenJet alignment and coverage |
| --- | --- |
| `provider-limits/adapters/anthropic.ts`, `provider-limit-service.ts`, quota coordination | `QuotaCredentials.kt`, `QuotaAdapters.kt` and `ProviderQuotaBackend.kt` add official `claude-agent` usage polling. Credential order is explicit `CLAUDE_CODE_OAUTH_TOKEN`, macOS Keychain, then the Claude credentials file. OpenCode's separate Anthropic account is never substituted. Claude Code owns rotation; no refresh request or credential write is made. Cache identity includes the resolved token and is re-read after both successful and failed polls. Fixtures cover precedence, invalid/inaccessible Keychain fallback, missing login, 401/403 recovery, file/Keychain rotation, stale snapshot exclusion, retry expiry and attach-only isolation. Existing project-local coordination limits remain below. |
| `util/webview-message.ts`, terminal login tests | `TerminalService.kt` allows exactly `claude auth login`, with shell suffixes and arbitrary Claude commands rejected in regression tests. `VarroProjectService.kt` keeps JetBrains terminal/fallback handling and blocks local login in attach-only mode. Login does not restart or upgrade OpenCode. VS Code terminal creation APIs do not apply. |
| `opencode-v2.test.ts`, Claude Agent external login | `OpenCodeV2Adapter.kt` already supports `/api/integration/claude-agent/connect/external`. A Kotlin regression verifies the exposed method, `claude-login` method ID and immediate completion without OAuth routes. Existing rejected-external-login coverage remains. |
| `shared/error-classification.ts`, provider connection/setup/usage/icon/model ordering | Unmodified vendored sources recognize Claude Code login errors, offer terminal login and confirmation, link Claude usage, use the Anthropic icon and sort Claude tiers/versions. Browser host regressions cover the login command, unchanged Anthropic login and auth-error/usage-link contracts. |
| Markdown citation selection, follow-up inline code | Unmodified vendored `markdown-citation-selection.ts`, `InlineCodeText.tsx`, renderer/message/follow-up components and CSS provide citation selection highlighting and inline-code labels. No new Kotlin message, endpoint or persistence format is required. |

The released OpenCode review compares complete tags `v2.2.3` through `v2.2.5`,
`082ac6281fd3` through `9bbb8ce18c80`, across 532 files. Evidence under
`build/opencode-compatibility/review-2.2.5/` retains the full diff, both OpenAPI
documents, route inventory and focused contract/runtime/AI/plugin changes. All
134 existing paths and methods remain; four optional artifact paths are added.
Consumed session/message/part, history/inbox, permission/form, provider/model and
SSE payloads remain compatible. Two optional events are added, `artifact.updated`
and `persistent-pty.updated`. Persistent-PTY rename adds `userTitle` and optional
size/title updates; OpenJet uses native IDE terminals and does not consume that API.
Percentage `compaction.buffer` values pass through the JSON config projection;
no Kotlin or vendored UI code performs arithmetic on the projected `reserved` value.
Serve/upgrade arguments are unchanged.

Job persistence moves from KV markers to SQLite's `job` table, and session deletion
cancels owned jobs. OpenJet neither reads nor writes those internal markers.
Schedule leader election and execution under `serve`, local overrides of
Console-supplied providers, CLI loopback proxy bypass, MCP framing/shutdown,
compaction limits, reasoning replay, Claude login error text, tool diagnostics and
the `bash` to `shell` fallback remain server-owned. The direct `@opencode/ai`
refactor and artifact plugin API do not change OpenJet's HTTP contracts. OpenCode
app/TUI/desktop/session-UI styling and terminal changes are not Varro host changes
and require no JetBrains port. V1's unchanged released contract was not reviewed again.

The exact published macOS ARM64 2.2.5 binary passed the Kotlin adapter case and
both managed-service cases with no skips. They use isolated HOME, XDG, database,
workspace and service-state roots, never production sessions or registrations.
Evidence is `build/opencode-compatibility/2.2.5/verification.json` and
`build/upstream-sync-1b96a54-compatibility.log`. Vendor integrity, 111 browser host
tests, TypeScript checking and browser bundling passed. All 490 focused upstream
unit/component tests passed across ten files. Those synthetic tests do not verify
JCEF or live provider login. Logs use the `build/upstream-sync-1b96a54-` prefix.

The final `--full-check` passed `check buildPlugin --rerun-tasks` with published
V1 1.19.5 and V2 2.2.5 enabled. All 750 enabled Kotlin tests passed across 118
suites; only the opt-in existing-database usage benchmark was skipped. All four
released test cases ran without skips. Vendor integrity, 111 browser host tests,
TypeScript checking and bundling passed again. The six compatibility-helper
regressions and vendor-integrity script test passed separately. The package is
`build/distributions/varro-openjet-0.9.10.zip`, SHA-256
`3817685663cd9e70bc6e4ea06b4a54c05ce516c9caabe434b1980e0de7edf6bb`.
Plugin Verifier and the sandbox IDE were not run for this revision.

All four selected Chromium cases passed for delayed citation validation and
selection of validated, missing and PDF references. The initial launch was
blocked by a missing Playwright browser. Installing the pinned headless browser
under ignored build output resolved setup, without changing source or the user's
browser profile. Evidence is `build/upstream-sync-1b96a54-chromium-retry.log` and
`build/upstream-sync-1b96a54-chromium-retry/`, including selected-reference and PDF
paragraph screenshots. These are upstream synthetic-host checks, not JCEF tests.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing quota coordination/validation differences | Varro `provider-limit-service.ts`, quota coordinator and `provider-limits/adapters/`; OpenJet `ProviderQuotaBackend.kt`, `QuotaAdapters.kt`. | Polls and snapshots remain project-local, including Claude Agent, rather than coordinated across hosts. Strict local-Claude payload validation, custom header-probe URLs and partial xAI fallback retain the differences listed in [architecture](architecture.md#remaining-quota-parity-differences). |
| Existing cleanup, permission and state-root differences | Varro `usage-report-service.ts`, `session-cleanup-service.ts`, `auto-approve-judge.ts`, `rest-proxy.ts`, `varro-state-paths.ts`; OpenJet `LocalUsageDatabase.kt`, `ArtifactCleanup.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `OpenCodeV2SessionState.kt`. | Large scans fail at explicit bounds, older IDE attachment-cache copies remain, equivalent reviews may repeat, incomplete multi-root pending snapshots can differ and native-only state roots can separate annotations/pauses/service choices. No user state was migrated. |
| Existing shell-stop limitation | OpenCode shell/job protocol and `DELETE /api/shell/:id`; OpenJet `OpenCodeV2Adapter.kt`. | Stopping agent-owned work can still notify/resume the assistant. The new session-delete cancellation does not add silent shell stop. |
| Optional OpenCode capabilities | OpenCode artifact/schedule/persistent-PTY APIs and app/TUI; OpenJet `OpenCodeV2Adapter.kt`, native terminals and vendored UI. | No dedicated artifact, schedule, image/media or client-MCP management UI is added. Existing retry-countdown/notice and AVIF/Windows-inspector differences remain as recorded in the preceding sync. |
| Native and external integrations not verified | Varro Claude login/quota and citation/follow-up UI; OpenJet `QuotaCredentials.kt`, `TerminalService.kt`, `WebviewHost.kt` and vendored components. | No real Keychain entry, live provider/OAuth, sandbox JCEF interaction, Windows/Linux runtime or Handy check was performed. Credential fixtures stub Keychain and HTTP. |
| Existing dependency advisory | Unchanged `seroval` 1.6.8 override in `webview/package.json`; npm audit advisories GHSA-f86p-h4j2-7qgj and GHSA-9wx9-5pv9-3349. | npm reports moderate serialization injection findings. This sync does not change that override or run an unrelated audit fix. |

No additional required consumed-contract implementation gap remains in the
reviewed delta. The retained differences and unverified native/live flows above
are not a claim of full backend or native UI parity.

## Symlinked-workspace sync, 2026-10-31

Compared the complete ten-file tree diff from Varro
`1b96a544f15db20f5914109da6830a449d9c45e8` through
`627a88c5bad01135891121b6203f2478ed453b6d`, version 0.33.32. Both revisions
remain available locally. The review includes all changed extension sources and
tests, package configuration, lockfile and documentation. Webview/shared sources,
assets, host messages and contributed settings are unchanged, so the vendor hash
remains `d5e33ae1674b44c515849eea31695194d683fcd4c42b23aad9ff9032a19d34cd`.
SDK 1.19.5, client 2.2.5, browser dependencies, Node/npm pins and connection
support floors are unchanged. There is no new released OpenCode range to review.
The user's OpenJet 0.9.11 version selection is preserved.

| Upstream change | Kotlin alignment and coverage |
| --- | --- |
| `src/extension/util/opencode-path-aliases.ts`, `open-code-transport.ts`, `server.ts`; REST and SSE path aliases | `src/main/kotlin/varro/server/OpenCodePathAliases.kt`, `OpenCodeTransport.kt` and `OpenCodeServer.kt` resolve managed macOS/Linux directories at the HTTP boundary and restore IDE paths before response/event projection. Both directory query keys, directory headers and nested JSON path values are translated. Missing descendants use known roots; longest segment-boundary matches avoid rewriting siblings. Multiline output remains unchanged. Windows and attach-only connections bypass translation, even after aliases were learned. The Kotlin helper synchronizes its caches for concurrent REST/SSE callers and preserves unchanged JSON objects. |
| New alias/transport regressions | `OpenCodePathAliasesTest.kt` and `OpenCodeTransportPathsTest.kt` cover real fixture symlinks, request immutability, queries, headers, responses/errors, startup SSE, nested roots, unknown paths, Windows policy and managed-to-attach-only transitions. `OpenCodeV2IntegrationTest.kt` additionally checks that logical and physical paths see the same session on a released server, with IDE-spelled lifecycle events. |
| Kotlin database-maintenance compatibility | `SessionCleanup.kt` and `TranscriptArchive.kt` use the transport's internal `RequestOptions.nativePaths` mode for exact stored-directory identity checks and native requests. These reads cannot replace IDE-spelled session observations. The first full run caught cleanup comparing restored aliases against physical SQLite records; the fix preserves strict inventory validation rather than weakening its safety check. Transport, cleanup and published-server regressions cover the exception. |
| `windows-process-inspector.test.ts` | The Node 26 fixture now drains both buffered writes. OpenJet uses JVM process inspection and no Node inspector helper, so this test-only change needs no Kotlin port. |
| `package.json` and lockfile | `ws` 8.22.1 and the VS Code packager's minimatch override are extension/development dependencies, not OpenJet browser dependencies. No lockfile or Docker toolchain update is needed. |

Vendor integrity, all 111 OpenJet browser host tests and TypeScript checking
passed with Node 24.22.2 and npm 12.2.1. All 141 selected upstream alias,
transport and Windows-inspector tests passed. The six compatibility-helper
regressions and vendor-integrity script regression passed too.

The pinned-release `--full-check` passed `check buildPlugin --rerun-tasks`
against exact published macOS ARM64 V2 2.2.5, with published V1 1.19.5 enabled
as well. All 757 enabled Kotlin tests passed, including seven new focused alias
tests and the expanded released-server fixture. Only the opt-in existing-database
usage benchmark was skipped. The V1 case, V2 case and both managed-service cases
ran without skips. Cleanup inventory through a symlinked transport retained its
strict database match, while chat metadata and lifecycle events retained IDE
paths. Browser bundling, typechecking, vendor integrity and all 111 host tests
passed again. The seven build-script regressions passed separately.

The initial cleanup failure is retained in
`build/upstream-sync-627a88c-full-check.log`; passing evidence is
`build/upstream-sync-627a88c-full-check-retry.log` and
`build/opencode-compatibility/2.2.5/verification.json`. The complete reviewed
upstream patch is `build/upstream-sync-627a88c-review.diff`. The package is
`build/distributions/varro-openjet-0.9.11.zip`. Plugin Verifier and the sandbox
IDE were not run for this revision. Varro `main` still resolved to `627a88c5bad0`
after the source review and browser checks.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing quota differences | Varro `provider-limit-service.ts`, quota coordinator and `provider-limits/adapters/`; OpenJet `src/main/kotlin/varro/host/ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt`. | Polls/snapshots remain project-local. Claude payload validation, custom probe URLs and partial xAI fallback retain the differences in [architecture](architecture.md#remaining-quota-parity-differences). |
| Existing cleanup, permission and annotation differences | Varro `usage-report-service.ts`, `session-cleanup-service.ts`, `auto-approve-judge.ts`, `rest-proxy.ts`, `varro-state-paths.ts`; OpenJet `LocalUsageDatabase.kt`, `ArtifactCleanup.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt`, `OpenCodeV2SessionState.kt`. | Large scans fail at explicit bounds, older IDE attachment-cache copies remain, equivalent reviews may repeat, incomplete cross-root pending snapshots can differ and native-only state roots can separate annotations/pauses/service choices. No user state was migrated. |
| Existing server and optional-client limitations | OpenCode shell/job protocol and `DELETE /api/shell/:id`, optional artifact/schedule/media/watch APIs and app/TUI; OpenJet `OpenCodeV2Adapter.kt` and vendored UI. | Agent-owned shell deletion can notify/resume the assistant. Dedicated optional management controls and retry countdown remain absent; frontend notice collection remains incomplete. None changed here. |
| Accepted codec and Windows startup differences | Varro `remote-images.ts`, thumbnail worker, `process-inspection.ts`, `windows-process-inspector.ts`; OpenJet `ImageThumbnails.kt`, `RemoteImages.kt`, `ProcessIdentity` in `ServerOwnership.kt`. | AVIF can remain unsupported; Windows inspection runs on demand without persistent-helper warm-up. These implementations did not change. |
| Native and live behavior not verified | Varro path aliases and OpenJet `OpenCodePathAliases.kt`, `OpenCodeTransport.kt`, `WebviewHost.kt`. | No sandbox IDE, interactive terminal TUI, native Windows/Linux, live provider/OAuth or Handy check was performed in this sync. Local fixture coverage does not establish those results. |
| Existing dependency advisory | Unchanged `seroval` 1.6.8 override in `webview/package.json`, previously reported as GHSA-f86p-h4j2-7qgj and GHSA-9wx9-5pv9-3349. | The prior serialization-injection findings are not addressed by this dependency-unchanged sync. No unrelated audit fix was run. |

No new required Kotlin host or consumed-contract gap remains in the reviewed
delta. The retained differences and verification limits above remain.
Unrelated backend behavior and the unchanged OpenCode release range were not
reviewed again. Tests use isolated fixture HOME/XDG/database/workspace/service
roots, never production sessions, credentials or service registrations.

## MCP settings and symbol-citation sync, 2026-11-02

Reviewed the complete 61-file diff from Varro
`627a88c5bad01135891121b6203f2478ed453b6d` through
`edbb9635a467979ab2cadaef2ca3b1051108330e`, version 0.33.34. This includes all
changed extension sources and tests, shared protocol, package configuration,
browser sources, E2E fixtures and documentation. Vendor sources are copied
without transformations. SDK 1.19.5, client 2.2.5, support floors, browser
dependencies and toolchain pins are unchanged. There is no new OpenCode release
range to review. The user's OpenJet 0.9.12 version selection is preserved.

| Upstream change | Kotlin alignment and coverage |
| --- | --- |
| `src/extension/opencode-config-service.ts`, `rest-proxy.ts`, `util/webview-message.ts`, `src/shared/protocol.ts`; `GET/POST /varro/mcp-defaults` | `McpDefaults.kt`, `OpenCodeHostServices.kt`, `RestProxy.kt`, `ApiRoutes.kt`, `JsoncEdit.kt` and `ProjectConfigEdit.kt` read global/project/effective defaults, preserve JSONC and connection fields, use V1 merged entries and V2 replacement semantics, remove only redundant project copies, reject dirty buffers/concurrent writes, and expose attached servers as read-only. Regressions cover inheritance, nested-config precedence, conflicting flags, malformed input, no-op writes and concurrent updates. |
| Global/project MCP reloads | `OpenCodeHostServices.kt` checks server-wide active work and pending input before writes. V2 global reloads are unscoped; project-only reloads use `/instance/dispose`, projected to `DELETE /api/debug/location` by `OpenCodeV2Adapter.kt`. The published V1 fixture caught that `/global/dispose` leaves the global-config cache intact. Global V1 changes therefore use `OpenCodeServer.restart(false)`, retaining ownership and idle checks. V1/V2 released fixtures verify that project overrides and restored inheritance reach the server's effective configuration. |
| `src/extension/citation-symbol-search.ts`, `context-provider.ts`, `rest-proxy.ts`; `POST /varro/citation-symbols` | `CitationSymbols.kt`, `IndexedCitationSymbols.kt`, `ContextProvider.kt`, `RestProxy.kt` and `ApiRoutes.kt` implement exact indexed definition lookup with session-directory authorization. Calls share a project-owned bounded cache, two active slots, two abandoned slots, a breaker/cooldown and a two-second response budget. Duplicates coalesce; ambiguous, fuzzy, missing and out-of-root symbols remain inert. Late results survive retries. Non-blocking read actions yield to IDE writes and cancel on disposal. Tests cover strict input bounds, scope, TTLs, pending results and stuck-provider limits. |
| Citation prompt guidance and Markdown rendering | `CitationPaths.kt` now asks for verified symbol-label Markdown links with paths and lines. The unchanged vendor contains symbol hydration, pending-result retry chains, safe labels and inferred-path checks. Native symbol discovery uses JetBrains language plugins, not VS Code workspace-symbol providers. No filename or content scan substitutes for an unavailable index. |
| `src/extension/usage-report-service.ts` | `UsageReport.kt` renders compact token counts and cache rate from summed input/cache counts, including total rows. Output/reasoning are excluded from the denominator; zero input shows a dash. `UsageReportTest.kt` checks weighted totals and local/REST parity. CSV exports retain exact counts. |
| MCP settings, picker, default selection and reconciliation UI | Copied unchanged, including cached settings during refresh, inherited draft/session defaults, explicit selections, running-root/child requirements and protection for connections used by unknown other-project sessions. Existing session-selection persistence remains compatible. New dependencies or host messages are not required. |

The complete upstream patch is retained in
`build/upstream-sync-edbb963-review.diff`. All 916 selected upstream unit tests
passed, covering the new MCP settings and symbol handling plus REST routing,
usage, loader and session-MCP regressions. This is additional UI/reference
coverage, not a replacement for Kotlin released-server verification. Vendor
integrity, TypeScript checking, browser bundling and all 111 OpenJet browser host
tests passed with Node 24.22.2 and npm 12.2.1. The six compatibility-helper,
seven build-script and vendor-integrity script regressions passed.

The pinned-release helper's `--full-check` passed `check buildPlugin --rerun-tasks`
with exact published macOS ARM64 V2 2.2.5 and V1 1.19.5 binaries. All 775 enabled
Kotlin tests passed; only the opt-in existing-database usage benchmark was
skipped. The V1 adapter case, V2 adapter case and both managed-service cases ran
without skips. MCP project overrides, cleared inheritance and V1 global changes
were checked against effective configuration on the released servers. The first
run's V1 stale-cache failure is retained in
`build/upstream-sync-edbb963-full-check.log`; passing evidence is
`build/upstream-sync-edbb963-full-check-retry.log` and
`build/opencode-compatibility/2.2.5/verification.json`.

Plugin Verifier passed against all five configured IDEs, 2025.2.6.3, 2025.3.6.1,
2026.1.5, 2026.2.3 and 263.6800.24, with existing deprecated/experimental API
warnings and unresolved optional dependency notices. It checked the already-built
`build/distributions/varro-openjet-0.9.12.zip`, without rebuilding it. The log is
`build/upstream-sync-edbb963-verifier.log`. The sandbox IDE and upstream
Playwright E2E tests were not run. Varro `main` still resolved to `edbb9635a467`
after the full build.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Deliberate busy-server policy | Upstream `opencode-config-service.ts` delegates refresh to `provider-file-refresh-controller.ts`; OpenJet `OpenCodeHostServices.kt` uses `OpenCodeServer.readRestartBlockers()` before changing `/varro/mcp-defaults`. | OpenJet rejects busy or unverifiable changes for retry instead of saving the file ahead of a deferred refresh. It does not interrupt another project's work or silently queue a later configuration write. V1 global changes require a safe managed restart because released V1 does not invalidate its global cache on disposal. |
| Native symbol/UI behavior not verified | Upstream `citation-symbol-search.ts`, `McpSettingsPanel.tsx` and Markdown hydration; OpenJet `IndexedCitationSymbols.kt`, `WebviewHost.kt` and the unchanged vendor. | Actual language-plugin index results, MCP settings focus/layout and symbol navigation in JCEF still need sandbox interaction. Provider-independent Kotlin tests do not establish native language coverage. VS Code provider plumbing and E2E harness changes do not apply directly to JetBrains. |
| Existing quota differences | Varro `provider-limit-service.ts`, coordinator and `provider-limits/adapters/`; OpenJet `ProviderQuotaBackend.kt` and `quota/QuotaAdapters.kt`. | Polls remain project-local; Claude validation, custom probe URLs and partial xAI fallback retain the differences in [architecture](architecture.md#remaining-quota-parity-differences). None changed here. |
| Existing cleanup, permission and annotation differences | Varro `usage-report-service.ts`, `session-cleanup-service.ts`, `auto-approve-judge.ts`, `rest-proxy.ts`, `varro-state-paths.ts`; OpenJet `LocalUsageDatabase.kt`, `ArtifactCleanup.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt`, `OpenCodeV2SessionState.kt`. | Large scans remain bounded, older attachment-cache copies remain, equivalent reviews may repeat, cross-root pending snapshots can differ and native-only roots can separate annotations/pauses/service choices. No user state was migrated. |
| Existing optional-client and platform limitations | OpenCode shell/job protocol, optional management APIs and app/TUI; Varro image codecs and Windows inspector; OpenJet `OpenCodeV2Adapter.kt`, `ImageThumbnails.kt`, `RemoteImages.kt`, `ServerOwnership.kt` and vendored UI. | Shell deletion can notify/resume the assistant; optional management controls and retry countdown remain absent. AVIF may be unsupported, Windows inspection lacks helper warm-up, and native Windows/Linux, live providers/OAuth and Handy were not exercised. None changed here. |
| Existing dependency advisory | Unchanged `seroval` 1.6.8 override in `webview/package.json`. | Previously reported GHSA-f86p-h4j2-7qgj and GHSA-9wx9-5pv9-3349 are not addressed by this dependency-unchanged sync. No unrelated audit fix was run. |

No new required Kotlin host or consumed-contract gap remains in the reviewed
delta. The explicit host-policy differences and verification limits above remain.
Unrelated backend behavior and the unchanged OpenCode release range were not
reviewed again. Tests use isolated HOME/XDG/database/workspace/service roots,
never production sessions, credentials or service registrations.

## Hex, provider pins and chat-restoration sync, 2026-11-05

Compared all 12 commits and the complete 98-file change inventory from Varro
`edbb9635a467979ab2cadaef2ca3b1051108330e` through
`70529460ef20a1f57ec1e4bda8e2fb5e13541ef7`, version 0.34.0. This includes
the changed extension implementations, shared protocol, package configuration
and affected browser/E2E flows. Upstream verification tooling was checked for
host applicability, not line-by-line audited. Vendor sources are copied without
transformations. SDK 1.19.5, client 2.2.5 and both support floors are unchanged;
there is no new released OpenCode range to review. This sync targets OpenJet
0.10.0. Verification below used version 0.9.12 before the release-version change.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `src/extension/dictation-integration.ts`, `hex-integration.ts`, `handy-integration.ts`, message routing and shared `dictation/request` / `dictation/result` | `src/main/kotlin/varro/host/DictationIntegration.kt`, `HexIntegration.kt`, `HandyIntegration.kt` and `VarroProjectService.kt` implement automatic provider choice, a shared app-level command guard, capture-owner routing, correlated results and host-wide revisions. Hex uses fixed idempotent commands, typed/versioned JSON, bounded discovery/status/command reads and capture-only polling. Unsafe Windows version pointers, malformed replies, refusals, uncertain delivery and remote/disposed hosts cannot activate another app or trigger retries. Kotlin fixtures never access installed apps or microphones. The retired Handy path setting is retained on disk but ignored and removed from the settings UI, matching upstream's automatic discovery. |
| `src/extension/provider-limit-status-bar.ts`, `model-preferences-store.ts`, sidebar visibility and `providerLimits.statusBarDisplay` | `src/main/kotlin/varro/host/PinnedProviderLimits.kt`, `src/main/kotlin/varro/toolwindow/ProviderLimitsWidget.kt`, `src/main/kotlin/varro/store/VarroModelStore.kt`, settings and plugin registration add native pinned quotas. Provider-wide polls reuse the existing backend every 120 seconds with chats closed. Rotation is 15 seconds or all providers; hidden/listed ordering, aliases, tooltip details and visible-chat exclusions match the shared contract. Widget ticks never wait on HTTP. Tests cover concurrent pin edits and persistence, summary units/order/bounds, retries, rotation, scope, model-specific updates, disconnect and stale results. |
| `src/extension/followup-service.ts`, `rest-proxy.ts`; `POST /varro/session/:id/followups` with `cacheOnly` | `src/main/kotlin/varro/host/FollowupService.kt` and `OpenCodeHostServices.kt` return disk-only opening probes without joining local inference, waiting for another host or generating on a miss, even when `allowGenerate` is true. Cached actions still revalidate authoritative history. The new fixture holds admitted inference open and verifies immediate local/remote misses, later cache reuse and exactly one generation. |
| Shared queue `followupTitle`, follow-up action menus and paused admission | `src/main/kotlin/varro/host/QueuedDispatches.kt` validates labels before persistence. Tests verify unchanged prompts, labels across restart/write-ahead admission and rejection without overwriting the previous journal. `OpenCodeV2Adapter.kt` and `OpenCodeV2Projection.kt` already preserve native follow-up metadata. Browser copy/paused-queue controls are copied unchanged. |
| Claude Agent idle provider-wide polling regression | `src/main/kotlin/varro/host/quota/QuotaAdapters.kt` already reads Claude Code OAuth quotas when the statusline is stale, without a model request or credential write. The added Kotlin regression verifies the endpoint, selected token, null model scope, fresh counters and unchanged local file. No adapter behavior change is required. |
| Hidden-completion scroll restoration, follow-up geometry, background-subagent state and settings-load retry | Copied unchanged into `webview/vendor/webview/`. Existing Kotlin session-status/history routes supply the child state. Browser tests cover virtualized/nonvirtualized restoration, hidden/followed/detached views, cached and delayed follow-ups, preserved drafts after Models import failure and completed child activity grouping. No new OpenCode endpoint is consumed. |

Marked advances to 18.1.3, Mermaid to 12.3.0, Tailwind and its Vite plugin to
4.3.4, Iconoir to 7.12.2, Vite to 8.3.6 and Vitest to 5.1.0. Node remains
24.22.2; npm advances to 12.2.2 in both `webview/package.json` and `Dockerfile`.
The lockfile uses those exact tools; no browser dependency is added, and npm
reports no unreviewed install scripts. Upstream's ImageMagick, MSAL, lint,
jsdom and publishing dependencies are not additional OpenJet browser dependencies.

VS Code status-item placement is replaced by the IntelliJ widget extension,
including project-owned state shared with detached windows. Upstream streaming
observer/lifecycle metrics, fixture-file cleanup guards and script-runner
concurrency changes are verification tooling, not new Kotlin host operations.
No production file cleanup, microphone operation or user-state migration ran.

Vendor integrity, TypeScript checking, browser bundling and all 111 OpenJet
browser host tests passed. All 882 selected upstream unit/component tests and
all 56 selected Chromium cases passed. The six compatibility-helper, seven
build-script and one vendor-integrity script regressions passed too. Logs use
the `build/upstream-sync-7052946-` prefix. Chromium fixtures are not native JCEF
or actual dictation-app verification.

The final pinned-release helper run passed `check buildPlugin --rerun-tasks`
against exact published V2 2.2.5, with published V1 1.19.5 enabled too. All
796 enabled Kotlin tests passed, including 21 new regressions. Only the existing
opt-in usage benchmark was skipped. The V1 adapter, V2 adapter and both
managed-service cases ran without skips in isolated fixture state. Evidence is
`build/upstream-sync-7052946-full-check-final.log` and
`build/opencode-compatibility/2.2.5/verification.json`. A source/build hash
comparison found no changes during final verification. The package is
`build/distributions/varro-openjet-0.9.12.zip`, SHA-256
`55a2e4b2eaff771907962e4fc8cf692442155e368645939f6691974195cd410b`.

Plugin Verifier passed against that existing ZIP on all five configured IDEs,
2025.2.6.3, 2025.3.6.1, 2026.1.5, 2026.2.3 and 263.6800.24. It reported
existing deprecated/experimental API warnings and unresolved optional-dependency
notices, with no compatibility errors. Its log is
`build/upstream-sync-7052946-verifier.log`. The packaged JAR contains the new
dictation, polling and native widget classes and widget registration. Varro
`main` still resolved to `70529460ef20` after the final full build.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Native UI and external applications unverified | Varro `src/extension/dictation-integration.ts`, `src/extension/hex-integration.ts`, `src/extension/provider-limit-status-bar.ts` and changed browser components; OpenJet `src/main/kotlin/varro/host/DictationIntegration.kt`, `src/main/kotlin/varro/host/HexIntegration.kt`, `src/main/kotlin/varro/host/PinnedProviderLimits.kt`, `src/main/kotlin/varro/toolwindow/ProviderLimitsWidget.kt`, `src/main/kotlin/varro/host/WebviewHost.kt` and `webview/vendor/webview/`. | Actual Hex/Handy microphone capture, paste delivery, IDE widget placement/visibility and JCEF restoration/focus still need sandbox interaction. No live signed-in quota account, native Windows/Linux run, interactive OAuth or companion check was performed. Windows pointer/discovery rules have deterministic fixtures only. |
| Existing quota coordination/adapter differences | Varro `src/extension/provider-limit-service.ts`, coordinator and `src/extension/provider-limits/adapters/`; OpenJet `src/main/kotlin/varro/host/ProviderQuotaBackend.kt` and `src/main/kotlin/varro/host/quota/QuotaAdapters.kt`. | Pin polling reuses project-local coordination, so separate projects/editors can duplicate requests. Claude validation, custom probe URLs and partial xAI fallback retain the differences in [architecture](architecture.md#remaining-quota-parity-differences). The new stale-statusline regression does not resolve those older differences. |
| Existing busy-server policy | Varro `src/extension/opencode-config-service.ts`, `src/extension/provider-file-refresh-controller.ts`; OpenJet `src/main/kotlin/varro/host/OpenCodeHostServices.kt` and `src/main/kotlin/varro/server/OpenCodeServer.kt`, `/varro/mcp-defaults`. | Busy/unverifiable changes reject for retry instead of saving ahead of deferred refresh. Released V1 global changes require a safe managed restart. This delta does not change MCP behavior. |
| Existing cleanup, permission and annotation differences | Varro `src/extension/usage-report-service.ts`, `src/extension/session-cleanup-service.ts`, `src/extension/auto-approve-judge.ts`, `src/extension/rest-proxy.ts`, `src/extension/varro-state-paths.ts`; OpenJet `src/main/kotlin/varro/host/LocalUsageDatabase.kt`, `src/main/kotlin/varro/host/ArtifactCleanup.kt`, `src/main/kotlin/varro/host/PermissionJudge.kt`, `src/main/kotlin/varro/host/NativeDecisions.kt`, `src/main/kotlin/varro/host/RestProxy.kt`, `src/main/kotlin/varro/host/ProjectNotifications.kt`, `src/main/kotlin/varro/server/OpenCodeV2SessionState.kt`. | Large scans stay bounded, older attachment-cache copies remain, equivalent permission reviews may repeat, cross-root pending snapshots can differ and native-only roots can separate annotations/pauses/service choices. No user state was migrated. |
| Existing optional-client/platform and notice limitations | OpenCode shell/job and optional-management APIs, Varro image codecs, Windows inspector and notices; OpenJet `src/main/kotlin/varro/server/OpenCodeV2Adapter.kt`, `src/main/kotlin/varro/host/ImageThumbnails.kt`, `src/main/kotlin/varro/host/RemoteImages.kt`, `src/main/kotlin/varro/server/ServerOwnership.kt`, `webview/vite.config.mts` and `webview/vendor/webview/`. | Shell deletion can notify/resume the assistant; optional management controls and retry countdown remain absent. AVIF may be unsupported, Windows inspection lacks persistent-helper warm-up and frontend notice collection remains incomplete. The prior KaTeX notice exclusion is retained. |
| Existing dependency advisory | Unchanged `seroval` 1.6.8 override in `webview/package.json`. | Previously reported GHSA-f86p-h4j2-7qgj and GHSA-9wx9-5pv9-3349 are not addressed by these upstream dependency updates. No unrelated audit fix was applied. |

No new required Kotlin host or consumed-contract gap remains in the reviewed
delta. The pre-existing differences and native verification limits above remain.
Unrelated backend behavior and the unchanged OpenCode release range were not
reviewed again. Tests use isolated fixture HOME/XDG/database/workspace/service
roots, never production sessions, credentials or service registrations.

## MCP management and account-switching sync, 2026-11-05

Compared Varro `70529460ef20a1f57ec1e4bda8e2fb5e13541ef7` through
`d5843d5b904296e39c82d15aff96768afaca2fe1`, version 0.34.1. Both commits and
the complete 26-file change inventory were reviewed, including all changed
extension implementations, shared protocol, package configuration and affected
browser/E2E flows. Vendor sources remain unmodified. SDK 1.19.5, client 2.2.5,
browser dependencies, Node/npm pins and support floors are unchanged. There is
no new released OpenCode range to review. The user's staged OpenJet 0.10.1
version is preserved, and release notes are consolidated under 0.10.x.

| Upstream change | Kotlin alignment and regression coverage |
| --- | --- |
| `src/extension/opencode-config-service.ts`, `rest-proxy.ts`, `src/shared/mcp-config.ts`; `POST /varro/mcp-config` | `src/main/kotlin/varro/host/McpConfigSnippet.kt`, `McpDefaults.kt`, `OpenCodeHostServices.kt`, `RestProxy.kt` and `ApiRoutes.kt` validate bounded JSONC, common wrappers, explicit bare-server names, command/args/env, HTTP(S) URLs, string maps and mutually exclusive boolean flags. Imports preserve literal secret references, comments, existing V1/V2 nesting and unrelated settings. Duplicate names anywhere in the selected scope reject the whole batch. Atomic writes reject dirty buffers and concurrent changes; concurrent imports retain both batches. Existing idle-server policy and V1 safe-global-restart behavior apply. |
| Same upstream host files; `POST /varro/mcp-config/open` | `src/main/kotlin/varro/host/McpDefaults.kt` selects the highest-precedence existing config without parsing it, or creates a schema-only scaffold under the config-write lock. Open unsaved files are not overwritten. `src/main/kotlin/varro/host/EditorIntegration.kt` opens the computed path directly through native VFS/editor APIs, including global files outside project roots. Both new endpoints reject attached servers before file access. Tests cover invalid existing JSON, precedence, new files, unsaved buffers and narrow POST-only routes. |
| `src/extension/opencode-config-service.ts`, `src/shared/protocol.ts`; `oauthServers` in MCP defaults | `src/main/kotlin/varro/host/McpDefaults.kt` lists only effective remote-server names whose OAuth is not explicitly disabled, including on-demand servers. V1 shallow merging and V2 replacement remain distinct. Tests check overrides, attached snapshots and exclusion of local/header-only servers and secret values. |
| `McpPicker.tsx`, `McpSettingsPanel.tsx`, `client.ts`; `/mcp/:id/disconnect`, `DELETE /mcp/:id/auth`, `/auth`, `/auth/callback` | Copied unchanged. `src/main/kotlin/varro/server/OpenCodeV2Adapter.kt` already scopes these operations to the selected MCP's native integration and OAuth attempt. New deterministic regressions verify disconnect/delete/new-login ordering, workspace and attempt correlation, deletion failures and no provider/session fallback. The native OpenCode 2.2.5 MCP implementation was checked for integration identity and credential-change reconnect behavior. |
| `ChatInput.tsx`, `chat-input/slash-commands.ts`, picker/settings styles and E2E harness | Copied unchanged. `/mcp` and `/mcps` select servers or open management for an empty catalog. Selection remains separate from sign-in/out/account controls. Browser completion polling, manual-code fallback, tenant-switch guidance, visible credential/connection errors and narrow layouts reuse existing Kotlin auth/status routes. VS Code editor-opening calls are replaced by the native editor helper, not ported literally. |

The only package change outside the upstream release version is the extension's
`oxlint-tsgolint` development dependency. It is not an OpenJet browser dependency,
so no lockfile or toolchain update is required here. The full upstream patch is
retained in `build/upstream-sync-d5843d5-review.diff`.

All 111 OpenJet browser host tests, TypeScript checking, vendor integrity,
953 selected upstream unit/component tests and 18 Chromium cases passed. The
six compatibility-helper tests and vendor-integrity script regression passed
too. Browser coverage includes JSON import into both scopes, empty catalogs,
`/mcp` and `/mcps`, sign-out, account switching, completed browser callbacks and
responsive management/sign-in controls. These are upstream browser fixtures,
not native JCEF or live OAuth verification.

The exact published OpenCode 2.2.5 binary passed the Kotlin adapter and both
managed-service cases without skips through the compatibility helper. The
expanded adapter fixture imports disabled MCPs into project and global files,
checks effective native configuration, creates synthetic credentials for two
tenants of one disabled loopback MCP and a separate MCP, then verifies that the
adapted disconnect/sign-out removes both selected-server accounts without
removing the other server's account. Credentials are created only in the
fixture database and cleaned up there. The first full run read native MCP
registrations before asynchronous initialization settled; the fixture now waits
at most ten seconds for its two integrations rather than weakening assertions.
Passing focused evidence is
`build/upstream-sync-d5843d5-compatibility-retry.log`; the initial failure is
retained in `build/upstream-sync-d5843d5-full-check.log`.

The final pinned-release `--full-check` passed `check buildPlugin --rerun-tasks`
with V2 2.2.5 and V1 1.19.5 enabled. All 809 enabled Kotlin tests passed,
including 13 new regressions and all four released adapter/managed-service
cases without skips. Only the existing opt-in usage benchmark was skipped.
Both released adapters verify imported MCP definitions against effective server
configuration; V1 also exercises global import through its safe restart.
Evidence is `build/upstream-sync-d5843d5-full-check-final.log` and
`build/opencode-compatibility/2.2.5/verification.json`.

The requested six-bullet 0.10.x changelog was shortened after that full run.
Kotlin and browser source hashes remained unchanged; only the release-note XML
changed. `buildPlugin -PskipWebview=true` repackaged that metadata, and archive
inspection confirmed version 0.10.1, the six bullets and the new MCP host class.
The package is `build/distributions/varro-openjet-0.10.1.zip`, SHA-256
`df5f51afc46894b388e8f742faba26a058979b86ed8e73f648f5509bf4d08a02`.
The metadata-only build log is
`build/upstream-sync-d5843d5-changelog-package.log`. Varro `main` still resolved
to `d5843d5b9042` after packaging.

Plugin Verifier passed against that final ZIP on all five configured IDEs,
2025.2.6.3, 2025.3.6.1, 2026.1.5, 2026.2.3 and 263.6800.24, with no
compatibility errors. It reported deprecated/experimental API warnings and
optional-dependency notices. The new configuration opener uses
`FileEditorManager.openFile`, deprecated on 263 but available across the
supported matrix. This verifies API linkage, not actual editor interaction.
The log is `build/upstream-sync-d5843d5-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing busy-server policy applies to imports | Varro `src/extension/opencode-config-service.ts`, `src/extension/provider-file-refresh-controller.ts`; OpenJet `src/main/kotlin/varro/host/OpenCodeHostServices.kt`, `src/main/kotlin/varro/host/McpDefaults.kt` and `src/main/kotlin/varro/server/OpenCodeServer.kt`, `/varro/mcp-config` and `/varro/mcp-defaults`. | Busy/unverifiable imports reject before writing instead of saving ahead of a deferred refresh. V1 global changes require a safe managed restart. This preserves the established OpenJet policy rather than interrupting another project's work. |
| Native UI and live OAuth unverified | Varro `src/webview/components/McpPicker.tsx`, `McpSettingsPanel.tsx` and `ChatInput.tsx`; OpenJet `src/main/kotlin/varro/host/EditorIntegration.kt`, `src/main/kotlin/varro/host/WebviewHost.kt`, `src/main/kotlin/varro/server/OpenCodeV2Adapter.kt` and `webview/vendor/webview/`. | Actual JCEF focus, global/project editor opening, browser/account/tenant consent and real MCP connectivity still need sandbox/live-provider interaction. The released test checks credential isolation and deletion, not an external identity provider's OAuth quality. No native Windows/Linux run or production account was used. |
| Existing quota differences | Varro `src/extension/provider-limit-service.ts`, coordinator and `src/extension/provider-limits/adapters/`; OpenJet `src/main/kotlin/varro/host/ProviderQuotaBackend.kt` and `src/main/kotlin/varro/host/quota/QuotaAdapters.kt`. | Polling stays project-local; Claude validation, custom probe URLs and partial xAI fallback retain the differences in [architecture](architecture.md#remaining-quota-parity-differences). No quota code changed here. |
| Existing cleanup, permission and annotation differences | Varro `src/extension/usage-report-service.ts`, `session-cleanup-service.ts`, `auto-approve-judge.ts`, `rest-proxy.ts` and `varro-state-paths.ts`; OpenJet `src/main/kotlin/varro/host/LocalUsageDatabase.kt`, `ArtifactCleanup.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt` and `src/main/kotlin/varro/server/OpenCodeV2SessionState.kt`. | Large scans stay bounded, old attachment-cache copies remain, equivalent permission reviews may repeat, cross-root pending snapshots can differ and native-only roots can separate annotations, pauses and service choices. No existing user state was migrated. |
| Existing optional-client/platform, notice and advisory limitations | OpenCode shell/job and optional-management APIs, Varro image codecs, Windows inspector and notices; OpenJet `src/main/kotlin/varro/server/OpenCodeV2Adapter.kt`, `ServerOwnership.kt`, `src/main/kotlin/varro/host/ImageThumbnails.kt`, `RemoteImages.kt`, `webview/vite.config.mts`, `webview/package.json` and vendored UI. | Shell deletion can notify/resume the assistant; optional management controls and retry countdown remain absent. AVIF may be unsupported, Windows inspection lacks persistent-helper warm-up, frontend notices remain incomplete and the unchanged seroval advisories from the preceding review remain. None changed in this delta. |

No new required Kotlin host or consumed-contract gap remains in the reviewed
delta. The established host-policy differences and native verification limits
above remain. The unchanged OpenCode release range and unrelated backend
behavior were not reviewed again. Tests use isolated fixture HOME, XDG,
database, workspace and service-state roots, never production sessions,
credentials or service registrations.

## Startup metadata and chat interaction sync, 2026-11-06

Reviewed the complete 33-file diff from Varro
`d5843d5b904296e39c82d15aff96768afaca2fe1` through
`4a3fd53caaad8ac7b912ad2731a2aa79a7dfea28`, version 0.34.3, including all
changed extension sources, shared protocol and package
configuration. Shared sources, host messages, settings, persistence and OpenCode
SDK/client baselines did not change. Tested baselines remain V1 1.19.5 and V2
2.2.5; support floors remain 1.16.0 and 2.0.5. No new released OpenCode range
requires review. Tailwind and its Vite plugin advance to 4.3.5, with the lockfile
refreshed using pinned Node 24.22.2 and npm 12.2.2. No new browser dependency
is needed. The upstream patch is retained in
`build/upstream-sync-4a3fd53-review.diff`.

| Upstream change | OpenJet handling and regression coverage |
| --- | --- |
| `src/extension/server.ts`, `sidebar-provider.ts`, `provider-file-refresh-controller.ts`; startup/version/ownership reads omit active-agent inventory | `src/main/kotlin/varro/server/OpenCodeServer.kt` already separates `readVersionInfo` from activity enumeration. `src/main/kotlin/varro/toolwindow/VarroStatusWidget.kt` reads that metadata; `src/main/kotlin/varro/host/VarroProjectService.kt` uses connection state for provider refresh. Ownership reads use local process state. A new `src/test/kotlin/varro/server/AttachOnlyServerTest.kt` regression records every request during initial and forced metadata reads for both API families, requiring health-only routes without directory queries. Restart and configuration-change safety checks retain their independent activity checks. |
| `src/webview/components/ChatInput.tsx`, `MarkdownRenderer.tsx` | Vendored unchanged. Internal drags suppress the attachment overlay, blur/visibility transitions clear it, and validated absolute file citations export plain paths and encoded file URIs. `ResourceURLs` is an additional VS Code drop format; JetBrains targets use the standard formats according to their native drop handling. There is no new host request. |
| `SettingsPanelBoundary.tsx`, `styles/models.css`, `styles/prompts.css` | Vendored error panels expose reload/back actions and expandable runtime diagnostics, with responsive card layout. The existing `webview/reload` handler remains the host contract. |
| `styles/prompts.css`, question/form browser regressions | Vendored selected answers keep their original font weight and layout. Native selected choices use a faint background and inset outline instead. Answer payloads and Kotlin form handling are unchanged. |
| `message-list/FollowupActionsMenu.tsx` | Vendored focus waits until positioning reveals the menu. Pointer/click propagation no longer closes the menu before its action runs. Existing queue and follow-up routes are unchanged. |
| Extension test stabilization, E2E fixtures, CI and native tray documentation | Test/tooling changes only. Windows PowerShell fixture quoting and macOS watcher deadlines do not alter production behavior. VS Code sidebar fixtures and its tray test setup require no JetBrains runtime port. |

Vendor integrity, all 111 browser host tests, TypeScript checking, browser
bundling, 948 selected upstream unit/component tests and 16 Chromium cases
passed. Chromium covers incoming/outgoing drags, file-path/URI payloads,
settings-error recovery at narrow widths and follow-up menu focus/paused queue
actions, plus stable question typography and selected-answer styling. Logs are
`build/upstream-sync-4a3fd53-upstream-tests.log`,
`build/upstream-sync-4a3fd53-chromium.log` and
`build/upstream-sync-4a3fd53-questions.log`. npm reports no unreviewed install scripts.

`python3 scripts/check-opencode-compatibility.py --version 2.2.5 --full-check`
passed `check buildPlugin --rerun-tasks` against the exact published V2 binary.
All 809 enabled Kotlin tests passed, including the adapter and both managed-service
cases without skips. The optional published V1 fixture and local usage benchmark
were skipped. V2 fixtures use isolated HOME/XDG/database/service-state roots and
a deterministic local provider. The first run exposed an assertion race in the
new metadata regression: startup's independent SSE connection could arrive during
the recorded reads. The final assertion excludes only that event-stream route
and still requires every remaining request to be an unscoped health probe.
Evidence is `build/upstream-sync-4a3fd53-full-check-final.log` and
`build/opencode-compatibility/2.2.5/verification.json`. The plugin archive is
`build/distributions/varro-openjet-0.10.2.zip`.

Three isolated macOS JCEF checks passed in IntelliJ IDEA 2026.2.2, with no
JavaScript page errors. They verify file-citation drag payloads and suppression
of the internal-drop overlay, clearing an external-drop overlay on blur, and
settings import-error recovery with the unsent draft intact. The fixture loads
the upstream synthetic host inside the plugin's real JCEF browser. Drag events
are synthetic; it does not establish operating-system drag interoperability or
exercise the ordinary Kotlin bridge. The error-panel screenshot was inspected.
Results, screenshots and logs are under `build/upstream-sync-4a3fd53/`.
The fixture verifies its IDE PID and plugin location and uses a fresh IDE profile,
HOME, XDG, database, service-state and workspace root. It shut down the fixture
IDE and Vite process; ports 50532 through 50535 had no remaining listeners.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing provider-refresh policy | Varro `src/extension/provider-file-refresh-controller.ts`, `opencode-config-service.ts`; OpenJet `src/main/kotlin/varro/host/VarroProjectService.kt`, `OpenCodeHostServices.kt`, `McpDefaults.kt` and `src/main/kotlin/varro/server/OpenCodeServer.kt`. | OpenJet does not implement the VS Code `providers/watch` controller. Explicit refresh uses existing connection state, while busy/unverifiable MCP edits reject before writing rather than saving for deferred refresh. This delta's metadata optimization adds no file-watching behavior. |
| Native drag interoperability not established | Varro `src/webview/components/MarkdownRenderer.tsx`; OpenJet `webview/vendor/webview/components/MarkdownRenderer.tsx` and `src/main/kotlin/varro/host/WebviewHost.kt`. | Standard file/path payloads are present, but dropping into actual JetBrains editors, terminals or other applications still needs native-target testing. VS Code's proprietary `ResourceURLs` consumer does not apply to JetBrains. |
| Existing quota differences | Varro `src/extension/provider-limit-service.ts`, coordinator and provider adapters; OpenJet `src/main/kotlin/varro/host/ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt`. | Polling remains project-local; Claude validation, custom probe URLs and partial xAI fallback retain the differences in [architecture](architecture.md#remaining-quota-parity-differences). |
| Existing cleanup, permission and annotation differences | Varro `usage-report-service.ts`, `session-cleanup-service.ts`, `auto-approve-judge.ts`, `rest-proxy.ts`, `varro-state-paths.ts`; OpenJet `src/main/kotlin/varro/host/LocalUsageDatabase.kt`, `ArtifactCleanup.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt` and `src/main/kotlin/varro/server/OpenCodeV2SessionState.kt`. | Large scans remain bounded, old attachment-cache copies remain, equivalent paid permission reviews may repeat, cross-root pending snapshots can differ and native-only roots can separate annotations, pauses and service choices. |
| Existing platform and optional-client limits | OpenCode shell/job and optional-management APIs, Varro image codecs, Windows inspector and notices; OpenJet `src/main/kotlin/varro/server/OpenCodeV2Adapter.kt`, `ServerOwnership.kt`, `src/main/kotlin/varro/host/ImageThumbnails.kt`, `RemoteImages.kt`, `webview/vite.config.mts` and vendored UI. | Shell deletion can notify/resume the assistant. Optional management controls and retry countdown remain absent; AVIF may be unsupported, Windows inspection lacks persistent-helper warm-up and frontend notices remain incomplete. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. The existing differences above were not reverified for
full parity. The unchanged OpenCode release range was not reviewed again.
Native Windows/Linux, live providers and OAuth are outside this verification.
Plugin Verifier was not repeated. No production sessions, credentials, databases
or service registrations were used.

## Background jobs, OAuth paste and ownership sync, 2026-11-07

Reviewed Varro `4a3fd53caaad8ac7b912ad2731a2aa79a7dfea28` through
`1697df0afc98`, five commits and 27 changed files, including all extension
sources/tests, shared types and package configuration. The UI and shared sources
are vendored without adaptations. Client 2.2.6 and its lockfile replace 2.2.5;
SDK 1.19.5, support floors, browser dependencies and Node/npm pins are unchanged.
Upstream's Azure MSAL override affects its extension package, not OpenJet's
browser dependency set. Source-checkout instructions, VS Code settings and the
Docker example do not add JetBrains runtime operations.

| Upstream change | Kotlin implementation and regression coverage |
| --- | --- |
| `src/extension/opencode-v2-adapter.ts`, `opencode-v2-background-work.ts`; `/api/experimental/job` and native session ancestry | `src/main/kotlin/varro/server/OpenCodeV2BackgroundJobs.kt`, `OpenCodeV2BackgroundWork.kt` and `OpenCodeV2Adapter.kt` restore shell/subagent/image waiting for owners and workspace-scoped ancestors, exclude cancelled jobs and saved services, preserve active execution and the two-second completion handoff, cache only missing routes, and reject malformed/failed ancestry reads without clearing work. `OpenCodeV2BackgroundJobsTest.kt` covers reload, consumption, cycles/deletion, services, fallback, failures, cancellation, snapshot order, live creation/exit, interruption and reset. The released fixture checks the native route and confirms ordinary managed shells do not become native jobs. |
| Shared `opencode-types.ts`, provider/MCP dialogs and `client.ts`; OAuth `paste` and provider callback `submitOnly` | `OpenCodeV2Adapter.kt` preserves the capability and original redirect URL. Submission-only requests validate the auto attempt and workspace and leave polling/cleanup with the original callback. `OpenCodeV2OAuthPasteTest.kt` covers old responses, code-only rejection, empty input, wrong workspace/attempt, failed and aborted paste retries, concurrent automatic completion and MCP acknowledgement. Existing callback cancellation coverage remains. |
| `src/extension/open-code-process.ts`; hard-linked executable comparisons for leases, markers and replacement recovery | `src/main/kotlin/varro/server/ServerOwnership.kt` checks fresh regular-file identity when normalized paths differ, without caching or content/basename matching. Ownership and retired-lease recovery share the comparison. `ServerOwnershipTest.kt` alternates hard-link paths, then replaces/removes an alias or changes listener/birth identity, requiring rejection without lease mutation or process signals. |
| Released OpenCode `packages/core/src/session/history.ts`; mixed split parts and inline suffixes | `src/main/kotlin/varro/host/TranscriptArchive.kt` uses bounded native history for ambiguous mixed storage instead of dropping inline text. Older servers can regard that text as stale, while 2.2.6 appends it after stored parts. `TranscriptArchiveTest.kt` checks both native hydration contracts and split-only local reads. |

The released OpenCode review compares the complete `v2.2.5` and `v2.2.6` trees,
`9bbb8ce18c80` through `dc2ffcf8bf26`, rather than relying on GitHub's truncated
300-file compare list or its merge-base diff. The 4,771-file inventory includes
site moves and unrelated app/Desktop/TUI implementations. Review covers changed
HTTP/OpenAPI, SSE, configuration, provider/model, permission/form,
session/history/inbox and CLI/service contracts consumed by OpenJet. Other
client UI implementations were classified by changed paths, not reviewed
line-by-line. Full diff, contract extracts and both OpenAPI documents are under
`build/opencode-compatibility/review-2.2.6/`.

All 138 existing HTTP paths and methods remain. The experimental job list is new.
OpenAPI regenerates anonymous schema references, describes SSE JSON content and
removes null from optional query parameters. Native session/message/part and
permission/form shapes and consumed event names remain compatible. Optional MCP
pooling, cache warming, feature flags, Code Mode VM and `websearch.use` policies
remain server-owned and survive configuration reads/edits. Provider-network
retries, Console/MCP deadlines, custom-model limits, PowerShell discovery and npm
upgrade-script fixes need no host port. API cross-site restrictions do not block
Kotlin's native HTTP client. File/media download security headers do not change
host-side byte reads. `serve` arguments and private service registration remain
compatible. The rewritten `opencode run` command, ACP cleanup, PTY retention and
GUI-plugin APIs are not consumed by this host.

The exact published macOS ARM64 2.2.6 binary passed the Kotlin adapter and both
managed-service cases without skips through the compatibility helper, in isolated
HOME/XDG/database/service-state roots with a local deterministic provider.
Status hints now use 2.2.6. Evidence is
`build/opencode-compatibility/2.2.6/verification.json`. V1's SDK did not change;
its released binary and the support-floor binaries were not rerun.

Vendor integrity, TypeScript checking, browser bundling, all 111 browser host
tests, 211 upstream provider/MCP/client component tests and six compatibility-helper
regressions passed. The final helper run passed `check buildPlugin --rerun-tasks`
with 828 enabled Kotlin tests, including 19 new regressions, and no failures.
Only the optional published V1 fixture and existing-database usage benchmark were
skipped; both released V2 suites had no skips. The log is
`build/upstream-sync-1697df0a-full-check-final.log`; the archive is
`build/distributions/varro-openjet-0.10.3.zip`. Node 24.22.2 and npm 12.2.2 match
the pins; npm reports no unreviewed install scripts.

Two isolated macOS JCEF checks passed in IntelliJ IDEA 2026.2.2 with no page
errors. They verify provider redirect rejection/retry while automatic completion
stays pending, and MCP redirect wording/submission with the original URL intact.
Both screenshots were inspected. The fixture verifies the IDE PID and packaged
plugin location and uses separate IDE/HOME/XDG/database/workspace roots with
managed startup disabled. It loads the upstream synthetic host and mocks auth,
so this is dialog/JCEF coverage, not live-account sign-in or ordinary Kotlin
bridge verification. Results and screenshots are under
`build/upstream-sync-1697df0a/`, with the final log at
`build/upstream-sync-1697df0a-native-verified.log`. Initial fixture attempts used
an incorrect Vite renderer import and failed to update mock MCP status; correcting
those fixture assumptions required no product changes. The fixture IDE/Vite
processes shut down, and ports 50534, 50542, 50543 and 50545 had no listeners.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing cancellation limitation | OpenCode shell/job APIs and Varro `opencode-v2-adapter.ts`; OpenJet `OpenCodeV2Adapter.kt`. | Tracking native jobs does not add silent process cancellation or new image/subagent cancellation controls. Shell deletion can still notify/resume the assistant. |
| Existing annotations and provider-refresh differences | Varro `varro-state-paths.ts`, `provider-file-refresh-controller.ts`; OpenJet `OpenCodeV2SessionState.kt`, `VarroProjectService.kt`, `OpenCodeHostServices.kt`, `McpDefaults.kt`. | Native-only roots without legacy links can separate annotations/pauses/service choices. Provider watching and deferred busy refresh remain different. No user state was migrated. |
| Existing quota, cleanup, permission and platform differences | Varro provider-limit adapters/coordinator, cleanup services, permission judge and Windows inspector; OpenJet `ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt`, `ArtifactCleanup.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt`, `ProjectNotifications.kt`, `ServerOwnership.kt`, `ImageThumbnails.kt`, `RemoteImages.kt`. | Project-local quota polling and previously documented validation/fallback differences, bounded scans, retained attachment caches, repeated equivalent permission reviews, cross-root pending snapshots, optional-management/retry-notice gaps, AVIF limits and Windows inspector warm-up remain. See the previous review and [quota differences](architecture.md#remaining-quota-parity-differences). |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. Existing differences were not reverified for full parity.
Live-account OAuth/provider calls, native Windows/Linux and Plugin Verifier were
not run. No production sessions, credentials, databases or service registrations
were used.

## Upstream sync review, 2026-11-08

Reviewed all five commits and 33 changed files from Varro
`1697df0afc98ae927b2d1d2f68103406e2a2705c` through
`0307fac3ca2aa6b5e98dded0d2ba1fae1f5099f4`, version 0.34.4. This includes
the extension backend, shared protocol, config handling and upstream regression
tests. SDK 1.19.5 and client 2.2.6 are unchanged. OpenJet's support floors,
dependencies and toolchain pins stay unchanged. The user's plugin version
selection, 0.10.4, is retained.

| Upstream change | Kotlin alignment and coverage |
| --- | --- |
| `rest-proxy.ts`, `util/webview-message.ts`, shared protocol; reviewed approval patterns and global endpoints | `ApiRoutes.kt`, `RestProxy.kt`, `PermissionService.kt` and `SessionTransfers.kt` add named global-config/approval routes, reject transferred-session writes, validate bounded V2-only pattern reviews, and keep the pending action, session and directory server-authoritative. Tests reject malformed reviews, V1 custom patterns, missing standing scope, foreign requests and widened directories. |
| `opencode-config-service.ts`; config-backed project/global approvals | `ProjectPermissionConfig.kt`, `GlobalPermissionConfig.kt`, `ProjectConfigEdit.kt` and `OpenCodeHostServices.kt` preserve existing legacy syntax and comments, append ordered native rules, support URL-specific native WebFetch patterns, serialize fresh target edits, reject dirty or changed files, and avoid disposing pending V2 instances. Global management requires a local V2 connection and never targets workspace or injected runtime config. |
| `opencode-config-service.ts`; other saved approvals and fresh mutation snapshots | `OpenCodeSavedPermissions.kt` separates origin-unknown rows without adopting or editing them. Removal requires a fresh validated project list, unchanged connection and a second list proving the exact native ID is absent. Stale observations remain visible but cannot authorize writes. Tests cover malformed/foreign rows, failed discovery, stale data, inconclusive deletion and URL/protocol/generation changes. |
| Permission review/settings, background command details and styles | Vendored unchanged. V2 session/project/global reviews retain drafts after failures and can be cancelled without replying. V2 config approvals reply once rather than creating database grants. Background commands expand and copy the complete command without selecting another process. The upstream harness/question changes are VS Code test fixtures, not additional JetBrains host APIs. |

The complete upstream diff is retained at
`build/upstream-sync-0307fac3-review.diff`. Focused Kotlin regressions, vendor
integrity, all 111 browser host tests, TypeScript checking and browser bundling
passed. Seven selected upstream permission, background-dialog, client and host
test files passed all 699 tests. Their results supplement, rather than replace,
the Kotlin released-server checks.

The exact published macOS ARM64 OpenCode 2.2.6 binary passed the Kotlin adapter
and both managed-service cases with no skips. The expanded adapter fixture
checks reviewed project/global config approvals, once replies with no database
grant, removal restoring an ask, URL-specific global rules across separate Git
projects, and deletion of an origin-unknown native approval without changing
session rules. Every fixture uses isolated HOME, XDG, database and server-state
roots and a deterministic local provider. The direct native permission fixture
initializes its location's agent catalog before asking, matching the normal
tool path. Early fixture failures were fixed before the final run.

`python3 scripts/check-opencode-compatibility.py --version 2.2.6 --binary
build/opencode-compatibility/2.2.6/node_modules/@opencode/cli/bin/opencode.exe
--full-check` passed `check buildPlugin --rerun-tasks`. All 844 enabled Kotlin
tests passed. Only the optional published V1 fixture and existing-database usage
benchmark were skipped. The six compatibility-helper tests also passed.
Evidence is `build/opencode-compatibility/2.2.6/verification.json` and
`build/upstream-sync-0307fac3-full-check.log`. The package is
`build/distributions/varro-openjet-0.10.4.zip`.

Three targeted macOS JCEF checks passed in IntelliJ IDEA 2026.2.2 with no page
errors. All screenshots were inspected.

| Targeted native check | Issues found | All cases complete |
| --- | --- | --- |
| V2 reviewed approval, save-error draft retention and cancel without reply | None | Yes |
| Independent global config save and confirmed unknown saved-grant removal | None | Yes |
| Complete multiline background command expansion, copy and collapse | None | Yes |

Evidence is `build/upstream-sync-0307fac3/result.json` and the three PNGs in
that directory. The fixture verified the IDE PID and packaged plugin path and
used separate IDE/HOME/XDG/database/workspace roots with managed startup disabled.
It mounted unchanged upstream components in JCEF with mocked client calls, not
the ordinary Kotlin bridge or live provider operations. Initial fixture runs
used an overlay above portal menus and started before harness initialization;
fixing those test assumptions required no product changes. The fixture IDE and
Vite exited; ports 50534, 50552, 50553 and 50555 had no listeners.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing permission/config differences outside this delta | Varro `opencode-config-service.ts`, permission judge and pending snapshots; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `NativeDecisions.kt` and `RestProxy.kt`. | The project settings response still omits inherited-file provenance, and previously documented repeated equivalent reviews and cross-root pending-snapshot differences remain. Effective rules are still read from OpenCode. This sync does not claim a full permission-system parity audit. |
| Existing runtime/platform differences | Varro state-path, provider-refresh, quota, cleanup, Windows inspection and remote-image services; OpenJet `OpenCodeV2SessionState.kt`, `VarroProjectService.kt`, `ProviderQuotaBackend.kt`, `ArtifactCleanup.kt`, `ServerOwnership.kt`, `RemoteImages.kt` and `ImageThumbnails.kt`. | Annotation-root selection, provider refresh, quota polling/validation, bounded cleanup, process inspection and AVIF differences documented in prior reviews remain. None changed in the reviewed upstream delta. |
| Unverified releases and environments | Unchanged V1 SDK/support floors, native Windows/Linux, live providers and interactive account flows. | The exact V2 2.2.6 checks do not establish released V1, lower-bound, other-platform or live-account behavior. Plugin Verifier was not rerun. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. Existing differences above were not reverified for full
parity. No production sessions, credentials, databases or service registrations
were used. No commit, push or tag was made.

## Upstream sync review, 2026-11-11

Reviewed Varro `0307fac3ca2aa6b5e98dded0d2ba1fae1f5099f4` through
`1d6dace4ade54feaaf1813211c68577fa78be910`, version 0.34.8, including
extension changes, shared protocol, contributed settings and dependency changes.
The V1 SDK remains 1.19.5; the V2 client changes from 2.2.6 to 2.2.7.
Support floors stay V1 1.16.0 and V2 2.0.5. The user's plugin version, 0.10.5,
is unchanged.

| Upstream change | OpenJet implementation and regression coverage |
| --- | --- |
| `followup-cache.ts`, `followup-service.ts`, `rest-proxy.ts`, shared follow-ups and client | `FollowupCache.kt`, `FollowupService.kt`, `OpenCodeHostServices.kt`, `RestProxy.kt` and `ApiRoutes.kt` persist exact action/session links with digest, dismissal, source freshness, hidden-session and same-workspace checks. Cache tests cover reload, idempotence, conflicts, changed actions, retirement and rejection of model-authored session IDs. Forking and paused queue actions use the existing host APIs. |
| `dropped-files-service.ts`, message router/decoder and shared dropped-content policy | `AttachmentStore.kt` and `VarroProjectService.kt` validate and atomically attach pathless folder copies, including empty directories. Tests reject traversal, absolute/Windows paths, duplicate case-insensitive names, oversized trees, content-size mismatches and file/directory collisions without attaching partial trees. OpenJet retains its existing durable IDE attachment cache rather than adopting Varro's temporary owner-marker lifecycle. |
| `startup-diagnostics.ts`, REST allowlist and retrying browser loaders | `StartupDiagnostics.kt` and `ApiRoutes.kt` provide the new bounded diagnostics endpoint. Tests cover V1, sanitization, concurrent inventories and absence of raw error text. The loaders, readiness messages and cancellation/generation handling are vendored unchanged. |
| `open-code-process.ts`, `websearch-preference.ts`, server reload hook | `WebSearchPreference.kt`, `AskAgentConfig.kt`, `OpenCodeProcess.kt` and `OpenCodeTransport.kt` add conservative defaults for owned V2 launches and recheck before reload. Tests cover ancestor configuration above Git roots, explicit opt-outs, malformed data, saved false, read-only database access and V1 exclusion. Released managed-service tests check default, opt-out reload and default restoration. |
| `prompt-trace.ts` and auxiliary inference call sites | `PromptTrace.kt` and matching Kotlin services add application-only tracing without changing inference admission or permission decisions. Tests cover disabled byte preservation, verified ancestry, cycle omission, workspace hashing and cancellation. No new title inference is introduced. |
| `provider-limit-service.ts` credential retries | `ProviderQuotaBackend.kt` retries rejected credentials after five minutes, doubles on actual repeated rejection up to one hour, bypasses on changed credentials and resets after success. Tests cover these transitions. Existing quota-coordination differences outside this delta remain. |
| `attention-notifications.ts`, `native-notifications.ts`, notification window routing | `AttentionNotifications.kt` and `ProjectNotifications.kt` use originating-project focus instead of app-wide activation or macOS occlusion. Sound rechecks the same focus. The existing project-bound Open chat action already retains the correct project/session. VS Code URI callbacks and scratch-window URLs do not apply to the JetBrains notification service. Native desktop-banner clicks remain platform-owned. |
| `usage-report-service.ts`, `usage-original-timing.ts`, OpenCode session generation | `AuxiliaryUsage.kt`, `LocalUsageDatabase.kt`, `UsageArchive.kt`, `UsageReport.kt` and `UsageCsv.kt` read retained usage events and reconcile cumulative token/cost remainders without inventing dates, models, prompts or duration. Scalar auxiliary records survive cleanup in the compatible sidecar table. Tests cover recent auxiliary activity on old sessions, archive-only reads, unknown-date CSV rows, invalid ownership/source, finite-log sync and deduplication. |

The released OpenCode review compares `v2.2.6` with `v2.2.7`, including the
changed-file inventory, HTTP/OpenAPI handlers, generated clients, schemas, SSE,
configuration, credentials, provider/model resolution, session generation/history,
shell/job completion and CLI/service behavior. No consumed HTTP path or method was
removed. Session `/generate` adds token/cost output and `generate` usage events;
Kotlin still returns only the side-question text to the browser and accounts for
native usage separately. Credential-updated IDs remain catalog refresh triggers.
Optional predicate criteria, location-scoped flags, model resolve hooks, provider
implementations, cache warming, MCP refresh locks, Console watches, shell capture
formatting and Windows/zsh spawn fixes remain server-owned. Permissions, questions,
inbox and history record contracts consumed here are otherwise unchanged.

OpenCode 2.2.7 agent listing is no longer an activation barrier. The released
permission fixture now uses the native model catalog's policy-activation barrier
before issuing synthetic direct permission requests. Its initial failure was a
fixture-readiness assumption, not a relaxed permission expectation. The published
adapter test also checks that two side questions add 26 fixture tokens while
preserving history, queued input, model and agent, and that Kotlin local/remote
reports and CSV retain the undated remainder.

Browser vendor integrity, all 111 host tests, TypeScript checking, browser bundling
and the six compatibility-helper regressions passed with Node 24.22.2/npm 12.2.2.
`npm install-scripts ls` reported no unreviewed installers. No new browser package
was required. The lockfile includes the five upstream version updates and their
resolved dependency changes.

The exact published macOS ARM64 OpenCode 2.2.7 binary passed
`OpenCodeV2IntegrationTest` and both `OpenCodeManagedServiceIntegrationTest` cases
without skips, in isolated HOME/XDG/database/server-state roots with a local
deterministic provider. The full helper invocation also runs
`./gradlew check buildPlugin --rerun-tasks`. Evidence is
`build/opencode-compatibility/2.2.7/verification.json` and
`build/upstream-sync-1d6dace4-full-check.log`; the package is
`build/distributions/varro-openjet-0.10.5.zip`. The Varro review diff is
`build/upstream-sync-1d6dace4-review.diff`; the complete OpenCode release diff is
`build/opencode-2.2.7.diff`, with focused contract/runtime extracts alongside it.
The final full build passed all 855 enabled Kotlin tests. Only the optional
existing-database usage benchmark and unchanged published V1 fixture were skipped.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing title implementation difference | Varro `session-title-fallback.ts`; OpenJet `RestProxy.kt` `rename-if-untitled` | OpenJet's fallback accepts the browser-supplied title instead of making an additional title-generation request. The new trace setting therefore has no host title-generation call to annotate. OpenCode's automatic titles remain server-owned. |
| Platform-specific notification routing | Varro `sidebar-provider.ts`, `native-notifications.ts`; OpenJet `ProjectNotifications.kt` | JetBrains owns desktop banners. The project-bound IDE notification's Open chat action routes to the originating project/session; VS Code URI/scratch routing is not used. OS-banner click behavior, detached-window focus, sounds and side-by-side native delivery were not exercised in a sandbox. |
| Existing attachment lifecycle difference | Varro `dropped-files-service.ts`; OpenJet `AttachmentStore.kt`, `ArtifactCleanup.kt` | New folder copies use the existing durable IDE attachment cache, not Varro's temporary owner markers or temporary-content eviction budget. They are not removed by shared-artifact cleanup. Tree validation and failure rollback are covered; native external-folder dragging was not exercised. |
| Existing backend differences outside this delta | Varro permission/config, state-path, quota coordination, cleanup, Windows inspection and image services; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `OpenCodeV2SessionState.kt`, `ProviderQuotaBackend.kt`, `ArtifactCleanup.kt`, `ServerOwnership.kt`, `RemoteImages.kt`, `ImageThumbnails.kt` | Previously documented inherited-config provenance, repeated reviews, cross-root pending snapshots, annotation roots, provider polling/validation, cleanup limits, Windows inspection and AVIF differences remain. This review does not claim to resolve or reverify those older differences. |
| Unverified native UI and external services | Varro composer/follow-up UI, startup loaders and prompt-trace call sites; OpenJet JCEF bridge and auxiliary services | No sandbox IDE flow or screenshot was produced for this sync. Cmd/Ctrl+S interception, image-paste focus, portal menus, background-fork navigation and startup retry rendering remain unverified in JCEF. Live proxy attribution, real provider OAuth/quota recovery and native Windows/Linux were not tested. Plugin Verifier and the unchanged released V1 fixture were not rerun. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. The differences and unverified behavior above are not a
claim of full backend or UI parity. Desktop/TUI/website changes and provider/tool
execution internals belong to the released OpenCode binary, not a JetBrains port.
No production sessions, credentials, databases or service registrations were used.
No commit, push or tag was made.

## Upstream sync review, 2026-11-13

Reviewed Varro `1d6dace4ade54feaaf1813211c68577fa78be910` through
`b227b904108264efc7552d672afe1582e2e238b7`, version 0.34.11. The comparison
covers every changed extension source and test, shared protocol, contributed
configuration and dependency declaration. V1 stays at 1.19.5; V2 moves to 2.3.0.
Support floors remain V1 1.16.0 and V2 2.0.5. The user's plugin version change
to 0.10.6 is preserved. No new browser dependency is required. Node is now
24.22.3 in both the webview manifest and Dockerfile; npm remains 12.2.2.

| Upstream change | OpenJet implementation and regression coverage |
| --- | --- |
| `opencode-permission-config.ts`, `opencode-config-service.ts`; native project/global permission edits | `JsoncEdit.kt` and `ProjectPermissionConfig.kt`, also used by `GlobalPermissionConfig.kt`, edit rule fields in place and append/remove entries without rewriting surviving rules or their comments. Standing approvals preserve additional native fields. Tests cover compact and multiline entries, tabs, CRLF, trailing commas, inline/empty arrays, duplicates, ordered replacements, removal, project/global writes and existing concurrent/dirty-document rejection. Legacy permission syntax is unchanged. |
| `provider-v2-auth-store.ts`, `provider-limit-service.ts`, `provider-limits/adapters/codex.ts`; `codexLoginRequired` | `OpenCodeV2QuotaCredentials.kt` and the attach-only `/api/credential` reader retain the selected credential ID inside the host. `QuotaAdapters.kt` identifies token-sharing connections without quota HTTP or local fallback. `ProviderQuotaBackend.kt` hashes the stable identity before caching, replying or publishing. Tests cover local V1/V2 and attached V2, token rotation, cache reset, changed credentials and secret exclusion. The browser contract test accepts the warning only for unsupported OpenAI provider limits. |
| `opencode-v2-adapter.ts`; `/provider/auth` | `OpenCodeV2Adapter.kt` exposes stable OAuth/external method IDs, omitting key IDs. A regression preserves native order and IDs, so the unchanged vendored provider dialog can select ChatGPT via Codex without relying on a translated label. |
| `opencode-v2-events.ts`; durable `session.step.first-token` and future step events | `OpenCodeV2Events.kt` projects only supported step names. Unhandled durable step events use the existing sequence-only context event, rather than an unknown name that `ServerEvents.kt` would drop. Unit tests check projection and host parsing; the released fixture checks SSE cursor forwarding. |

The vendored UI includes the persistent, dismissible Codex reconnect warning,
provider-dialog method selection, quota presentation, same-ID permission-review
state preservation, and file-icon/completion corrections. It is copied without
source adaptations. The VS Code project-notification URL now uses
`windowId=_blank` so a closed project cannot replace an unrelated workspace.
OpenJet's `ProjectNotifications.kt` already binds its Open chat action to the
originating project/session; VS Code URI routing and the bundled macOS notifier
do not apply to JetBrains notifications.

The released OpenCode review covers `v2.2.7` through `v2.3.0` and the npm
provenance revisions `5f76acb12572` through `73abe63ac232`. Runtime and contract
sources at those published revisions match their respective tags. All 139 HTTP
paths and their methods remain. Changes to the event subscription enum and
session-create documentation do not remove consumed operations. Session history
adds optional `time.firstToken`; permissions, questions/forms, inbox payloads,
credentials and managed `serve` arguments remain compatible. Provider client TLS,
freeform model tools, MCP event watches, shell timeout/background/cancellation
output, durable child reports and restart recovery stay server-owned. Tool inputs
still reach the adapter as objects, transcript content remains authoritative, and
file changes retain `metadata.files`. OpenCode's updater adds package-owner/prefix
checks without changing consumed CLI arguments. Desktop/TUI/client-store/site
changes require no Kotlin port.

The complete release diff is `build/opencode-published-2.3.0.diff`; the Varro
backend review is `build/upstream-sync-b227b904-review.diff`. Published V2
adapter and both managed-service cases passed through the compatibility helper
without skips, using isolated fixture HOME/XDG/database/server-state roots and
a deterministic local provider. The first run failed only on a stale test label
for the Codex reconnect message; the updated fixture also checks opaque warning
identity across cache resets. Evidence is
`build/opencode-compatibility/2.3.0/verification.json`.

The full helper invocation also passed `./gradlew check buildPlugin --rerun-tasks`:
866 Kotlin tests passed, with only the optional existing-database benchmark and
unchanged published V1 fixture skipped. Both released V2 suites passed again
without skips. All 112 browser host tests, TypeScript checking, vendor integrity,
browser bundling, six compatibility-helper regressions, seven build-script
regressions and the vendor-integrity script test passed. npm reported
no unreviewed install scripts. The full build log is
`build/upstream-sync-b227b904-full-check.log`; the plugin is
`build/distributions/varro-openjet-0.10.6.zip`.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Optional first-token timing display, also absent upstream | OpenCode `packages/schema/src/session-message.ts`, `session.step.first-token`; Varro `opencode-v2-events.ts`; OpenJet `OpenCodeV2Events.kt`, `OpenCodeV2Projection.kt` | Cursor accounting is preserved, but native first-token timing is not displayed. This is not a missing Varro host behavior. |
| Existing backend differences outside this delta | Varro permission/config, state-path, quota coordination, cleanup, Windows inspection and image services; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `OpenCodeV2SessionState.kt`, `ProviderQuotaBackend.kt`, `ArtifactCleanup.kt`, `ServerOwnership.kt`, `RemoteImages.kt`, `ImageThumbnails.kt` | Earlier inherited-config provenance, repeated reviews, cross-root pending snapshots, annotation-root selection, cross-host quota polling/validation, cleanup limits, Windows inspector warm-up and AVIF differences remain. This sync does not move user state or claim to resolve those differences. |
| Unverified native UI and external integrations | Vendored `PermissionPrompt.tsx`, `ProviderConnectionDialog.tsx`, `CodexLoginWarning.tsx`; OpenJet `WebviewHost.kt`, `ProjectNotifications.kt`, quota/auth services | No sandbox JCEF interaction or screenshot, real provider login/quota request, desktop notification click, native Windows/Linux run or Docker build was performed. Plugin Verifier and the unchanged published V1 fixture were not rerun. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. The limitations above are not a claim of full backend/UI
parity. No production sessions, credentials, databases or service registrations
were used. No commit, push or tag was made.

## Upstream sync review, 2026-11-15

Reviewed Varro `b227b904108264efc7552d672afe1582e2e238b7` through
`aed79c3bb08e23023b4a5d46831a1e488fab6ad0`, version 0.34.14. The comparison
covers all changed extension sources and tests, shared protocol, contributed
configuration and dependencies. Both revisions are available despite the shallow
upstream fetch reporting a rewritten branch. V1 remains 1.19.5 and V2 remains
2.3.0, so there is no new released OpenCode range to review. Support floors remain
V1 1.16.0 and V2 2.0.5. The user's plugin version change to 0.10.7 is preserved.
DOMPurify and its lockfile entry are updated to 3.4.18. No new browser dependency
or toolchain change is required.

| Upstream change | OpenJet implementation and regression coverage |
| --- | --- |
| `rest-proxy.ts`; known transfers into saved Git worktrees | `SessionLocations.kt` checks the open root's project, Git VCS, fresh server inventory and canonical session identity/location before `SessionTransfers.kt` admits an action. Reads stay on approved roots during verification. Unrelated projects, unknown transfers, malformed/custom inventories, moved-again sessions, connection/root changes and deadlines fail closed. Catalogs can reuse a previously verified presentation flag, but every action verifies again. No history-scope preference or user filesystem state is changed. |
| `opencode-v2-adapter.ts`; `/project`, `/experimental/worktree?projectID=` | `OpenCodeV2Adapter.kt` preserves the native bare arrays from `/api/project` and `/api/worktree`, rather than expecting a session-style `data` envelope. Unit coverage checks exact paths, encoding, missing IDs and absence of destination location queries. |
| `rest-proxy.ts`, `shared/protocol.ts`; `GET /varro/session/:id/location` and session-list worktree flags | `ApiRoutes.kt`, `RestProxy.kt` and `SessionLocations.kt` implement scoped canonical location reads, hidden/stale/foreign-session rejection, optional merge targets and per-project cached V2 presentation inventories. Metadata failures leave history usable without granting execution. The cache is separate from authorization. V1 string inventories retain Git/project checks. |
| `sidebar-provider-actions.ts`, `message-router.ts`, `util/webview-message.ts`; session-bound `vscode/open-folder` | `VarroProjectService.kt` validates the ID/path, rechecks the canonical session and opens the locally available directory in a new IDE window. Retained read-only transfers can still open their verified destination. Attach-only connections retain OpenJet's existing local-project restriction. |
| `rest-proxy.ts`; transferred status snapshots | `SessionTransfers.kt` and `RestProxy.kt` poll retained destinations once per directory and merge only their retained session IDs, preserving complete background status. Regressions reject malformed/failed snapshots and exclude unrelated/hidden sessions without mutating the original snapshot. |
| `usage-report-service.ts`; shorter report introduction | `UsageReport.kt` removes the introductory token-accounting explanations without changing aggregation or attribution. Existing usage-report regressions remain in place. |

The retained-session checks also cover side questions, child controls, follow-up
generation/linking, permission routing and session-owned file references through
`SideQuestions.kt`, `ChildTasks.kt`, `OpenCodeHostServices.kt`, `RestProxy.kt` and
`VarroProjectService.kt`. They do not grant other sessions directory-wide access.

The unchanged vendored UI adds moved/worktree location icons, copy/open-folder
menus and merge/migrate prompt actions. Those actions submit ordinary
permission-controlled prompts and leave the composer draft intact; the Kotlin
host never performs their Git operations. It also clears stale move/worktree
presentation, fixes completion dismissal and retains composer attachments when
asking side questions. Upstream's VS Code window command is replaced with the
JetBrains project-opening API. Playwright/harness and CI version changes do not
add a JetBrains runtime operation.

Review evidence is `build/upstream-sync-aed79c3b-review.diff`. Both full
compatibility-helper runs passed the adapter and two managed-service cases against
the exact published 2.3.0 binary without skips. The new released fixture creates
its own Git repository and registered worktree, moves a fixture session, verifies
host authorization and location/list metadata, and continues the conversation
through the deterministic provider. Evidence is
`build/opencode-compatibility/2.3.0/verification.json`.

The final helper invocation passed `./gradlew check buildPlugin --rerun-tasks`:
879 Kotlin tests passed, with only the optional existing-database usage benchmark
and unchanged published V1 fixture skipped. All 112 browser host tests, TypeScript
checking, vendor integrity, browser build, six compatibility-helper regressions
and the vendor-integrity script regression passed. npm reported no unreviewed
install scripts. `git diff --check` passed. The final build log is
`build/upstream-sync-aed79c3b-final-check.log`; the packaged plugin is
`build/distributions/varro-openjet-0.10.7.zip`.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing backend differences outside this delta | Varro permission/config, state-path, quota coordination, cleanup, Windows inspection and image services; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `OpenCodeV2SessionState.kt`, `ProviderQuotaBackend.kt`, `ArtifactCleanup.kt`, `ServerOwnership.kt`, `RemoteImages.kt`, `ImageThumbnails.kt` | Earlier inherited-config provenance, repeated reviews, cross-root pending snapshots, annotation-root selection, cross-host quota polling/validation, cleanup limits, Windows inspector warm-up and AVIF differences remain. This sync does not move user state or claim to resolve those differences. |
| Existing dependency audit finding | Upstream and OpenJet `package.json` pin the `seroval` override to 1.6.8; npm reports GHSA-f86p-h4j2-7qgj and GHSA-9wx9-5pv9-3349. | The override remains unchanged in this upstream delta. The serializer advisories need a separate dependency/reachability review; this sync does not claim an audit-clean dependency tree. |
| Native UI and external integrations not verified | Varro `SessionLocationIndicator.tsx`, `session-location-actions.ts`, composer/attachment changes; OpenJet `WebviewHost.kt`, `VarroProjectService.kt`, vendored UI | No sandbox JCEF interaction or screenshot, actual merge/migrate Git workflow, real provider request, native Windows/Linux run or Docker build was performed. New-window placement, clipboard/focus and attachment rendering still need interactive verification. Plugin Verifier and the unchanged published V1 fixture were not rerun. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. These limits do not establish full backend/UI parity.
Tests use isolated fixture HOME/XDG/database/server-state roots and a deterministic
local provider, never production sessions or service registrations. No commit,
push or tag was made.

## Upstream sync follow-up, 2026-11-15

Reviewed Varro `aed79c3bb08e23023b4a5d46831a1e488fab6ad0` through
`ad031d16e81cd0fc912e93d5256f488ebeb9a7f1`, version 0.34.15. All four changed
upstream files were reviewed. The only runtime change is in
`src/extension/opencode-v2-adapter.ts`; the other changes add its tests and bump
the upstream package version. Shared protocol, webview sources, contributed
configuration, browser dependencies and toolchain are unchanged. The vendor
content hash is unchanged. V1 remains 1.19.5 and V2 remains 2.3.0, with support
floors of 1.16.0 and 2.0.5. No new released OpenCode diff requires review. The
user's plugin version change to 0.10.8 is preserved.

| Upstream change | OpenJet implementation and regression coverage |
| --- | --- |
| `opencode-v2-adapter.ts`; filtering `/provider` and `/config/providers` over `/api/provider`, `/api/model` and `/api/config` | `OpenCodeV2Adapter.kt` filters Flex-tier and GPT Cyber models only for matching Codex subscription endpoints, after legacy fallback and before choosing defaults. Model body, provider body, model settings/options and provider settings retain upstream's nullish precedence. Cyber matching uses the underlying model ID rather than the display name. OpenAI API-origin overrides follow the subscription hook; unrelated API endpoints and provider aliases keep their own routing. No config, credential or server-catalog object is mutated. |

`OpenCodeV2SubscriptionCatalogTest.kt` adds five regressions covering unknown,
floor and modern server versions, both projected catalogs, restored legacy
models, safe defaults, source immutability, subscription-to-API changes, exact
hosts/path boundaries, explicit overrides, malformed URLs and non-default API
ports. Requests in these fixtures are catalog reads only. The unchanged V1
adapter, HTTP paths, SSE, permissions/forms, session execution and managed
startup need no port. There are no new VS Code-specific changes in this delta.

Review evidence is `build/upstream-sync-ad031d16-review.diff`. The released
compatibility helper passed `./gradlew check buildPlugin --rerun-tasks`, including
the pinned Plugin Verifier checksum gate. All 884 Kotlin tests passed; only the
optional existing-database usage benchmark and unchanged published V1 fixture
were skipped. The adapter and both managed-service cases passed against the exact
published OpenCode 2.3.0 binary without skips, using isolated fixture
HOME/XDG/database/server-state roots and a deterministic local provider.
Evidence is `build/opencode-compatibility/2.3.0/verification.json`.

All 112 browser host tests, TypeScript checking, vendor integrity, browser
bundling, six compatibility-helper regressions and the vendor-integrity script
test passed. No dependency installation or lockfile refresh was needed.
`git diff --check` passed. The full build log is
`build/upstream-sync-ad031d16-full-check.log`; the packaged plugin is
`build/distributions/varro-openjet-0.10.8.zip`.

Plugin Verifier 1.411 also checked that exact ZIP against all five configured IDE
builds, including 2026.1.5. Every target was compatible, with only the existing
deprecated/experimental API warnings. The log is
`build/upstream-sync-ad031d16-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing backend differences outside this delta | Varro permission/config, state-path, quota coordination, cleanup, Windows inspection and image services; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `OpenCodeV2SessionState.kt`, `ProviderQuotaBackend.kt`, `ArtifactCleanup.kt`, `ServerOwnership.kt`, `RemoteImages.kt`, `ImageThumbnails.kt` | Previously documented inherited-config provenance, repeated reviews, cross-root pending snapshots, annotation-root selection, cross-host quota polling/validation, cleanup limits, Windows inspector warm-up and AVIF differences remain. None of these services changed upstream in this delta. |
| Existing dependency audit finding | Varro and OpenJet `package.json` retain the `seroval` 1.6.8 override, described in the preceding review. | The earlier serializer advisories remain outside this change. No dependency version was changed or audit-clean result claimed. |
| Live subscription and native UI not verified | Varro `opencode-v2-adapter.ts`; OpenJet `OpenCodeV2Adapter.kt`, `WebviewHost.kt` and the unchanged model picker | No live Codex login, billed/public-provider request, sandbox JCEF interaction or native Windows/Linux run was performed. Filtering is verified with deterministic catalog fixtures, not a real account. The unchanged released V1 fixture was not rerun. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. These limits do not establish full backend/UI parity.
No production session, credential, database or service registration was used.
A concurrent local commit, `27f5c14`, captured the sync before these final
results were added. Final verification notes remain uncommitted. This session
did not commit, push or tag.

## Upstream sync review, 2026-11-17

Reviewed Varro `ad031d16e81cd0fc912e93d5256f488ebeb9a7f1` through
`1ce62d2b6afba397b1d8fcc788e00839c6519eef`, version 0.34.16. The comparison
covers all changed extension sources and tests, shared protocol, contributed
configuration, dependency declarations and browser sources. Vendored sources
remain unmodified. Marked advances to 18.2.1 and the browser's client types to
2.3.2; the pinned Node 24.22.3 and npm 12.2.2 toolchain is unchanged. No new
browser package is required. The user's OpenJet 0.10.9 version is preserved.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `opencode-v2-adapter.ts`, `opencode-v2-background-work.ts`; `/api/experimental/job` | `OpenCodeV2BackgroundJobs.kt` validates `codemode` jobs, and `OpenCodeV2BackgroundWork.kt` uses their native title or "Code Mode script". Kotlin fixtures cover descendant ownership, reopening, titles, running/completed/error/cancelled states and consumption handoff. Older-server and missing-route fallback remain unchanged. |
| `opencode-v2-events.ts`, `opencode-v2-projection.ts`; status and synthetic child completion | `OpenCodeV2Events.kt` keeps model-idle updates background-busy while jobs remain. `OpenCodeV2Projection.kt` preserves `backgroundPending` only for completed native subagent metadata with a matching running envelope. Focused regressions reject mismatched child IDs and non-completed outcomes. |
| `session-state-manager.ts`, `sidebar-provider.ts`; resumed busy/retry and completion notifications | `AttentionNotifications.kt` tracks resumed busy/retry work instead of discarding it after completion, and deduplicates terminal evidence while accepting a distinct later assistant without prompt admission. `VarroProjectService.kt` already treats retry as busy and forwards that native event to every view; it does not use VS Code's separate queued-message status channel. |
| `rest-proxy.ts`, `sidebar-provider.ts`, `util/webview-message.ts`; `GET/POST /varro/session/:id/reference` | `ApiRoutes.kt`, `SessionLocations.kt`, `SessionTrash.kt`, `RestProxy.kt` and `OpenCodeHostServices.kt` read only a bounded summary through an already-open root. Hidden IDs and recycled trees, malformed summaries, changed connections/roots, cancellation and deadlines reject the lookup. Reads grant no destination access. POST opens the verified local folder in a new IDE frame without selecting or mutating the source conversation. Attach-only connections cannot open a remote path as a local project. |
| `provider-limit-service.ts`, `provider-limits/adapters/zai.ts`; quota and reset-credit recovery | `ProviderQuotaBackend.kt` and `QuotaAdapters.kt` distinguish authentication rejection, transient failures and confirmed zero reset credits. Same-identity snapshots expose stale/retry metadata for at most 15 minutes from their original check. Reset-credit expiry is independent of a fresh quota check. Rejections invalidate other model caches; credential changes bypass old snapshots. A 12-second lookup deadline includes catalog/credential discovery, and retired polls cannot publish late cache updates. Kotlin tests use deterministic HTTP responses, fake clocks, rotated fixture credentials and stalled reads. |
| `open-code-process.ts`, `server.ts`; missing managed runtime config | `ServerOwnership.kt`, `AskAgentConfig.kt`, `OpenCodeProcess.kt` and `OpenCodeServer.kt` record process-owned config paths and owner files. Missing files with absent or matching regular owner evidence can trigger an idle, claim-protected restart when Ask is absent. Existing process/account verification remains required. Malformed, mismatched and symlink evidence cannot request recovery. Caller-provided configuration and foreign files are not rewritten. Owned reloads recreate a removed config even when its desired content is unchanged. Optional inspection/agent reads are bounded; busy services defer recovery to an idle maintenance check. |
| `process-inspection.ts`, `windows-process-inspector.ts`; kernel listener tables | `WindowsListeners.kt` reads fresh IPv4 and IPv6 `GetExtendedTcpTable` listener-only tables through the IDE's JNA. `ProcessIdentity` in `ServerOwnership.kt` uses the old PowerShell path only after a failed native read, never after a successful empty or ambiguous result. Cross-platform memory fixtures verify row layouts, zero state, multiple owners, invalid buffers/PIDs and changing tables. No persistent PowerShell helper or warm-up is introduced. |
| `opencode-config-service.ts`, `McpSettingsPanel.tsx`; MCP defaults during running work | `OpenCodeHostServices.kt` removes its running-session write gate while keeping dirty-buffer and concurrent-change checks. `McpConfigRefresh.kt` and `OpenCodeServer.kt` defer only the optional runtime reload until all server work and decisions are idle. Global V1 refresh still needs a safe managed restart; V2 uses location reload. Kotlin tests verify that edits persist during active work and that failed or superseded reloads remain pending. |

The released OpenCode review covers V2 `a554d70499` through `c0ad18b402`,
2.3.0 through 2.3.2, and V1 `810c0eba1a` through `f4e363c829`, 1.19.5
through 1.19.6. Complete diffs and focused runtime comparisons are retained in
`build/opencode-published-2.3.2.diff`, `build/opencode-runtime-2.3.2.diff`,
`build/opencode-published-1.19.6.diff` and `build/opencode-runtime-1.19.6.diff`.
The review includes HTTP/OpenAPI, generated SDK/client types, SSE, configuration,
provider/model shapes, permissions/forms, session/history/inbox and serve/upgrade
behavior. Unrelated app/TUI, hosted services, localization and release tooling
were classified from the changed-file inventory, not audited line by line.

The OpenAPI comparison removes four unused experimental schedule paths and adds
automation, video-generation and blob paths that OpenJet does not consume.
Of 135 retained paths, 134 are identical; the job route adds Code Mode.
Consumed event, message, inbox, permission and form envelopes remain compatible.
Native nested-permission source remapping and same-source-message denial remain
server-owned. Background compaction defaults, its new `compaction.background`
opt-out, CLAUDE.md discovery, model metadata/caching, UTF-8-safe shell output,
synthetic delivery and Unicode session search require no host-side reconstruction.
Kotlin already filters non-chat models and preserves unknown native config keys.
V1 changes bound inline model-request images without removing transcript
attachments and recognize two more provider overflow diagnostics. Its generated
SDK contract and consumed HTTP/SSE routes are unchanged. Both support floors
remain unchanged.

The exact published V2 2.3.2 binary passed the Kotlin adapter and managed-service
suites without skips through `scripts/check-opencode-compatibility.py`.
The exact published V1 1.19.6 binary passed the focused V1 suite. These fixtures
use isolated HOME, XDG, database and service-state roots, without production
sessions or registrations. V2 evidence is
`build/opencode-compatibility/2.3.2/verification.json`.

The final `--full-check` passed `./gradlew check buildPlugin --rerun-tasks`
with both released binaries enabled. All 903 enabled Kotlin tests passed; only
the opt-in existing-database usage benchmark was skipped. The V1 adapter,
V2 adapter and all three managed-service cases passed without skips. The new
released recovery case removes an adopted service's runtime config with and
without its owner file, then verifies a new managed PID and Ask availability
before startup reports running. The owned-reload case verifies repair without
changing the listener PID. V1 evidence is
`build/opencode-compatibility/1.19.6/verification.json`.

All 112 OpenJet browser host tests, TypeScript checking, vendor integrity and
browser bundling passed with the refreshed lockfile. npm reports no unreviewed
install scripts. Six compatibility-helper regressions and the vendor-integrity
script test passed. The problems-context host fixture now mocks browser state
because the upstream completion parser imports session-reference state.
All 1,953 selected upstream unit tests and 44 Chromium cases passed, covering
turn completion, hidden activity, diff-preview scrolling and external links.
These fixtures do not establish JCEF rendering or live-account behavior.

The final build log is `build/upstream-sync-1ce62d2b-verified.log`; upstream
browser logs use the `build/upstream-sync-1ce62d2b-` prefix. The first full run
caught a missing health/protocol detection step in the new fixture's independent
reload transport. That setup was corrected before the successful runs.
Hashes confirmed all 750 recorded source/build inputs were unchanged during the
final verification. The package is `build/distributions/varro-openjet-0.10.9.zip`.

Plugin Verifier 1.411 checked that exact ZIP against all five configured IDE
builds, IU-252.28539.97, IU-253.33813.55, IU-261.27258.48, IU-262.10968.63
and IU-263.6800.24. Every target was compatible, with deprecated/experimental
API warnings only. Its log is `build/upstream-sync-1ce62d2b-verifier.log`.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing permission/config differences | Varro `src/extension/opencode-config-service.ts`, `auto-approve-judge.ts` and `rest-proxy.ts`; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `RestProxy.kt` and `ProjectNotifications.kt` | Inherited-file provenance is absent from project settings; equivalent actions can repeat paid reviews; incomplete cross-root snapshots can differ in pending-request visibility. Effective rules still come from OpenCode. These older behaviors were not re-audited. |
| Existing annotation-root difference | Varro `src/extension/varro-state-paths.ts`; OpenJet `OpenCodeV2SessionState.kt` | Distinct native roots without compatibility links can separate annotations, pause markers and saved background-service choices across editors. No user state was moved. |
| Existing non-Z.ai quota differences | Varro `src/extension/provider-quota-coordinator.ts`, `provider-limit-service.ts` and `provider-limits/adapters/`; OpenJet `ProviderQuotaBackend.kt` and `quota/QuotaAdapters.kt` | Cross-host polling can duplicate requests and diverge in timing. Local Claude validation, configured header-probe URLs and partial xAI failures retain the differences listed in [architecture](architecture.md#remaining-quota-parity-differences). This sync aligns Z.ai recovery, not every provider fallback. |
| Existing cleanup limits | Varro `src/extension/session-cleanup-service.ts`; OpenJet `ArtifactCleanup.kt` | Previously documented bounded scans and retained attachment caches remain. Copying the new UI does not expand filesystem cleanup scope. |
| Accepted AVIF limitation | Varro `src/extension/remote-images.ts` and thumbnail worker; OpenJet `RemoteImages.kt` and `ImageThumbnails.kt` | Valid AVIF images can report unsupported format instead of showing a preview. The earlier decision to retain the JVM codecs is unchanged. |
| Existing Windows process-inspector difference | Varro `windows-process-inspector.ts` and `extension.ts`; OpenJet `ProcessIdentity` in `ServerOwnership.kt` and `WindowsListeners.kt` | Native listener enumeration is aligned, but executable/account inspection still launches PowerShell on demand rather than warming a persistent helper. Windows startup performance can differ. |
| Existing dependency audit finding | Varro and OpenJet `package.json` retain their previously documented serializer dependency decisions. | This sync is not a dependency-security remediation or a new audit-clean claim. |
| Native UI and external integrations not verified | Varro message virtualization, diff preview, session-reference and quota UI; OpenJet `WebviewHost.kt`, the vendored UI, `WindowsListeners.kt` and native project-opening APIs | No JCEF sandbox interaction, native Windows/Linux run, real-account quota/OAuth request or public provider request was performed. Synthetic Windows tables do not establish native Windows interoperability. Real IDE scrolling, preview layout and cross-project window opening remain unverified. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed delta. The existing differences and verification limits above remain;
this is not a claim of full backend/UI parity. VS Code window-opening APIs are
replaced by native IDE project frames, and its persistent PowerShell helper is
not needed for direct JNA listener reads. No commit, push or tag was made.

## Upstream sync review, 2026-11-19

Reviewed Varro `1ce62d2b6afba397b1d8fcc788e00839c6519eef` through
`28263880823fee8733d06e9ed611bad704c39392`, version 0.35.0. The comparison
covers every changed extension source and test, shared protocol, contributed
configuration, dependency declarations and browser sources. A final remote-ref
check still resolved `main` to that commit. Vendored sources remain unmodified.
The user's OpenJet 0.11.0 version is preserved.

The browser moves to Solid 2.0.3, with explicit `@solidjs/signals`,
`@solidjs/web` and `@solidjs/vite-plugin` dependencies. OpenJet uses the new
compiler and JSX import source, and its host-test store writes use `storePath`
with browser-condition resolution. Vitest advances to 5.1.1. Node stays at
24.22.3; npm advances to 12.2.3 in both the manifest and Dockerfile. The lockfile
was regenerated without bypassing peer validation. npm reports no unreviewed
install scripts.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `local-images.ts`, `rest-proxy.ts`, `util/webview-message.ts`, `shared/local-images.ts`; `POST /varro/local-image` | `ApiRoutes.kt`, `RestProxy.kt` and `LocalImages.kt` validate paths and owning directories before bounded canonical workspace reads. Network/device paths, traversal, external symlinks, non-regular/empty/oversized files and non-raster content are rejected. Relative lookup permits sibling open roots, not closed transferred folders. Preview requests reuse the JVM codecs; original raster reads are explicit. Kotlin fixtures cover parsing, containment, sibling roots, missing roots, size, MIME, conversion, cancellation and disposal. Browser contract tests cover local targets and the route's shared wire types. |
| `rest-proxy.ts`, `sidebar-provider.ts`; retained transferred-session authorization | `SessionLocations.kt` verifies only the canonical session ID, project and exact destination through an open origin. Git/worktree inventories are presentation-only and cannot block a known non-Git or global-project conversation. `SessionTransfers.kt` retains per-session grants without widening catalogs or file access, and reports failed verification with refresh guidance. Regressions cover optional metadata, identity/project/directory mismatches, roots, deadlines, connection drift, stale moves and unrelated-directory rejection. |
| `local-session-summary.ts`; native V2 `session_part` hydration | `LocalSessionSummary.kt`, `LocalV2SessionSummary.kt` and `SessionSummary.kt` read native inline, stored and mixed parts in a read-only SQLite snapshot. Split parts precede inline content; only file-change inputs, context character counts and summary metadata survive. Per-session input-token counts produce the nested context estimate without retaining tool output or file diffs. Schema, ownership, ordinal, row/data/part/message/session bounds and orphan checks fail back to the API. Forks and staged reverts remain API-owned. Older or unidentified servers use API hydration for mixed storage because pre-2.2.6 semantics differ. Kotlin fixtures cover ordering, tokens, context estimates, cost/model/duration inputs, unchanged database bytes and malformed/oversized fallback. |
| `pinned-session-manager.ts`, `sidebar-provider.ts`, `shared/extension-message.ts`; `pinned-sessions/sync` | `VarroStore.kt` serializes pin mutations and notifies after native state changes. `VarroProjectService.kt` broadcasts detached snapshots to every view independently of the original RPC response. `RestProxy.kt` uses the serialized update operation. Store tests cover concurrent mutations, reordered/empty snapshots and listener removal; browser tests validate the new envelope. |
| `streaming-tool-content.ts`; already-deferred bounded events | `StreamingToolContent.kt` retains boolean/string deferral and reconstructs content routes from the current envelope. Kotlin regressions cover input completion, called/progress/success/failure events, foreign embedded paths and missing message IDs. |
| `provider-limit-status-bar.ts`, `provider-limit-service.ts`, `shared/provider-limit-summary.ts`; reset estimates and stale attribution | `PinnedProviderLimits.kt` adds rounded-up countdowns, local absolute reset times and explicit last-successful-snapshot attribution without exposing raw poll notes. The existing `ProviderLimitsWidget.kt` one-second tick refreshes tooltips without new quota polling. Tests cover minute boundaries, days, expired/invalid resets and both stale markers. `ProviderQuotaBackend.kt` already emits the unchanged note prefix. |

Other browser fixes cover same-tick state reads, transfer refreshes without
discarding the active transcript, user interruptions versus provider failures,
superseded usage notices, local image loading, annotation layout, scroll ownership
and reactive callback dependencies. They are reused unchanged. Contributed
settings and OpenCode SDK/client baselines do not change. V1 remains 1.19.6 and
V2 remains 2.3.2, with support floors 1.16.0 and 2.0.5. There is no new released
OpenCode range to review in this delta.

Upstream's VS Code status-item timers are replaced by OpenJet's existing native
widget tick. VS Code workspace and window APIs remain native JetBrains content
roots and project frames. No VS Code placement, command-registration or
PowerShell-inspector change requires a port in this delta.

The final `scripts/check-opencode-compatibility.py --version 2.3.2 --full-check`
run used the exact published 2.3.2 executable and also enabled the published V1
1.19.6 fixture. `./gradlew check buildPlugin --rerun-tasks` passed with 913 enabled
Kotlin tests; only the opt-in existing-database usage benchmark was skipped.
The V1 adapter, V2 adapter and all three managed-service cases passed without
skips in isolated HOME/XDG/database/service-state roots. V2 evidence is
`build/opencode-compatibility/2.3.2/verification.json`; the full log is
`build/upstream-sync-28263880-release-check.log`.

All 114 OpenJet browser host tests, TypeScript checking, vendor integrity and
browser bundling passed. Six compatibility-helper tests, seven build-script
regressions and the vendor-integrity script test passed. The selected upstream
run passed 7,958 unit cases across 307 files, covering all webview/shared tests
and the changed local-image, summary, pin, deferred-tool, quota-widget and proxy
paths. Its Chromium run passed 86 cases for local images, session locations,
annotation layout, commands, composer behavior, scrollbar ownership and activity
handoff. Logs are `build/upstream-sync-28263880-upstream-unit.log` and
`build/upstream-sync-28263880-upstream-e2e.log`.

The final build produced `build/distributions/varro-openjet-0.11.0.zip`,
SHA-256 `2794f802cadcda7fff9026a9426db21b48384a59f46fc0e7dd2aa3e4824c849b`.
All 762 recorded source/build inputs remained unchanged through that build.
Plugin Verifier 1.411 checked that exact ZIP against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible, with deprecated/experimental API warnings only.
Its log is `build/upstream-sync-28263880-release-verifier.log`. An earlier
combined check/verifier invocation reached the shell's two-minute deadline;
the final standalone verifier completed successfully.
No commit, push or tag was made.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Accepted image-codec difference | Varro `src/extension/local-images.ts`, `image-thumbnails.ts` and its WASM worker; OpenJet `LocalImages.kt` and `ImageThumbnails.kt` | Local previews use PNG rather than WebP. The previously accepted JVM-codec choice also applies here: AVIF preview conversion is unsupported, though explicit raster-original reads work. No decoder or licensing change was added. |
| Local-file boundary on attached connections | Varro `src/extension/rest-proxy.ts` and `context-provider.ts`; OpenJet `RestProxy.kt` and `LocalImages.kt` | OpenJet refuses local images for attach-only servers rather than interpreting a remote server's paths as local IDE files. Closed transferred folders do not gain filesystem access from a conversation grant. |
| Retained metadata read and older mixed-history fallback | Varro `src/extension/local-session-summary.ts`; OpenJet `SessionSummaryService.kt`, `LocalSessionSummary.kt`, `LocalV2SessionSummary.kt` and `OpenCodeHostServices.kt` | Successful local V2 summaries still read one session record through the API to preserve shared pause annotations. Mixed histories on older/unidentified servers remain correct through API hydration rather than the local fast path. |
| Existing context-estimate limitation outside the new native fast path | Varro `src/extension/session-summary.ts`; OpenJet `SessionSummary.kt`, `SessionSummaryService.kt` and the V1 path in `LocalSessionSummary.kt` | The new native V2 reader supplies nested context estimates. The older V1-local and API-fallback paths still omit that optional summary field, so the expanded context composition can be absent there. Files, token totals and duration remain available. Those paths did not change upstream in this delta. |
| Existing unrelated backend differences | Varro `opencode-config-service.ts`, `auto-approve-judge.ts`, `varro-state-paths.ts`, quota adapters/coordinator, `session-cleanup-service.ts` and Windows inspector; OpenJet `OpenCodeHostServices.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `OpenCodeV2SessionState.kt`, `ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt`, `ArtifactCleanup.kt` and `ServerOwnership.kt` | The preceding review's permission provenance/review reuse, state-root selection, non-Z.ai quota coordination, cleanup limits and inspector warm-up differences remain. They were not re-audited because those implementations did not change in this delta. |
| Native UI and external integrations not verified | Varro Solid 2 UI and local-image/scroll/transfer fixtures; OpenJet `WebviewHost.kt`, `LocalImages.kt`, `ProviderLimitsWidget.kt`, native project APIs and `Dockerfile` | Chromium and unit fixtures do not establish native JCEF rendering/performance, Windows/Linux filesystem behavior, status-tooltip display or real-IDE transfer behavior. No interactive JCEF sandbox, Docker image build, live provider, account quota/OAuth or production session was used. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes. The listed platform boundaries, accepted codec choice and
verification limits remain; this is not a claim of full backend/UI parity.

## Upstream sync follow-up, 2026-11-19, Varro 0.35.1

Reviewed `28263880823fee8733d06e9ed611bad704c39392` through
`021019d09de812365711d2813bc67d27f584bed6`, Varro 0.35.1. The comparison
covers all changed extension sources/tests, shared protocol, browser sources and
`package.json`. A remote-ref check still resolved `main` to that revision.
Browser dependencies, Node/npm pins, contributed settings and OpenCode baselines
are unchanged. V1 remains 1.19.6 and V2 remains 2.3.2, with unchanged support floors.
No new released OpenCode range needs review. OpenJet remains 0.11.0.

| Upstream change | Kotlin alignment and regression coverage |
| --- | --- |
| `opencode-v2-adapter.ts`, `shared/session-goal.ts`, `util/webview-message.ts`; `GET /session/:id/goal` | `ApiRoutes.kt`, `OpenCodeV2Adapter.kt` and `protocol/SessionGoal.kt` expose only session-scoped GET inspection, translated to `POST /api/rpc/opencode.goal/get` with a location query and `{input:{sessionID}}`. Null output means no goal; malformed output fails rather than fabricating state. Unknown public queries and direct plugin RPCs are rejected. Tests cover scoped requests, strict response parsing, cancellation and unsupported-server errors without retries or fallback mutations. |
| `opencode-v2-events.ts`, shared event names; `rpc.opencode.goal.changed` | `OpenCodeV2Events.kt` projects `session.goal.updated`, and `ServerEvents.kt` admits it to the existing workspace-filtered bridge. Tests retain native identity/directory, active/paused/complete states, reasons and explicit null clearing. Missing or malformed goals do not clear existing state. |
| `opencode-v2-projection.ts`, `opencode-v2-adapter.ts`; native goal kickoff/history/inbox identity | `OpenCodeV2Projection.kt` recognizes canonical goal metadata, reconstructs multiline `/goal` objectives with one-pass entity decoding and preserves native IDs and user attachments. Internal goal continuations have no visible parts. `OpenCodeV2Adapter.kt` treats kickoffs as user parents in history, preceding pagination and delivered inbox state, while internal continuations cannot replace that parent. Tests cover pending/delivered deduplication, direct reads, pagination, failed streamed replies and unchanged unrelated synthetic records. |
| `SessionGoalRow.tsx`; Pause, Resume and Clear | The vendored row uses the existing Kotlin native command adapter, sending only `command: goal` and the action argument, with no picker model/agent override or second prompt. Adapter fixtures verify the exact command-only request sequence. The row hides itself when optional goal inspection is unavailable. |

The UI also moves Markdown tooltip/image/inline-slot children into declarative
Solid portals instead of nested `render()` calls, and initializes image capability
memos before registering composer effects. These unchanged vendor fixes restore
skill-link visibility and skill-only composer startup. They require no JetBrains
window, setting, migration or process change.

The exact published V2 2.3.2 binary passed the Kotlin adapter and all three
managed-service cases without skips through the compatibility helper's
`--full-check`. Published V1 1.19.6 was enabled in the same run. All 920 enabled
Kotlin tests passed; only the opt-in existing-database usage benchmark was
skipped. The expanded V2 fixture confirms that the real location-scoped goal
RPC returns null for a fresh session, with no history/inbox change or provider
call. Fixtures use isolated HOME/XDG/database/service-state roots, not production
state. Evidence is `build/opencode-compatibility/2.3.2/verification.json` and
`build/upstream-sync-021019d0-release-check.log`.

All 115 browser host tests, TypeScript checking, vendor integrity, browser build
and `./gradlew check buildPlugin --rerun-tasks` passed. Six compatibility-helper
regressions and the vendor-integrity script test passed. The selected upstream
run passed 1,566 cases across eight files for goal adaptation, transcript
projection, public routes, the goal row, Markdown, message rendering, composer
and client behavior. Eleven Chromium cases passed for goal controls at narrow
and wide sizes, skill-prompt visibility, selected-skill sends and local images.
These upstream fixtures do not execute Kotlin or a live goal provider. Logs are
`build/upstream-sync-021019d0-upstream-unit.log` and
`build/upstream-sync-021019d0-upstream-e2e.log`.

The package is `build/distributions/varro-openjet-0.11.0.zip`, SHA-256
`db1108b94690acded176e2fd7e804bd2d997b6d3615ba9de5489d799e75b7fe4`.
Plugin Verifier 1.411 checked this exact ZIP against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible, with existing deprecated/experimental API warnings.
The verifier log is `build/upstream-sync-021019d0-verifier.log`.
No commit, push or tag was made.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Optional plugin availability | Varro `opencode-v2-adapter.ts` and `SessionGoalRow.tsx`; OpenJet `OpenCodeV2Adapter.kt` and the same vendored row | Servers or locations without `opencode.goal/get` keep the goal row hidden. No generic plugin-RPC interface, helper prompt or host-owned goal state is substituted. |
| Existing skill-admission difference | Varro `opencode-v2-adapter.ts` native `skills` payload and skill-command fallback; OpenJet `OpenCodeV2Adapter.kt` prompt/command submission | Kotlin still passes attached-skill guidance as prompt text and sends selected skill names through the native command route rather than upstream's registry-checked skill fallback. Tool-driven skill loading can still work, but direct selected-skill admission can differ. This older adapter behavior did not change upstream in this delta; the new Markdown rendering fixes are vendored unchanged. |
| Existing backend/platform differences | The preceding sync review's tables name the corresponding Varro and OpenJet implementations | Accepted AVIF codec limits, attach-only local-file boundaries, older summary/context fallbacks, annotation-root selection, permission/quota/cleanup and Windows inspector differences remain. They were not re-audited in this goal-focused delta. |
| Native UI and external behavior not verified | Varro `SessionGoalRow.tsx`, `MarkdownRenderer.tsx`, `ChatInput.tsx`; OpenJet `WebviewHost.kt` and the Kotlin adapter | Fixture Chromium tests do not establish JCEF layout/focus or live-provider goal execution. No interactive IDE sandbox, production session, live account/provider, native Windows/Linux run or Docker image build was used. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed delta. These statements do not claim full parity for the older
differences or native UI behavior above.

## Upstream sync follow-up, 2026-11-19, ee753860

Reviewed `021019d09de812365711d2813bc67d27f584bed6` through
`ee753860a0b425faf23eef356ec8f2744afd1ba5`, which is still Varro 0.35.1.
The checkout was clean before syncing, and a remote-ref check resolved `main`
to that revision. The complete comparison has no changes under `src/extension`
or `src/shared`, or in `package.json`. Host endpoints, messages, settings,
persistence formats, server adapters, SDK/client baselines and dependencies
are unchanged.
V1 remains 1.19.6 and V2 remains 2.3.2; support floors remain 1.16.0 and 2.0.5.
No new released OpenCode range needs review. OpenJet remains 0.11.0.

| Upstream change | OpenJet integration and review |
| --- | --- |
| `ChatInput.tsx`, `chat-input/slash-commands.ts`, `CompletionMenu.tsx`; session-list server commands | The vendor refresh keeps a command's `serverCommand` identity and uses the new-session command flow for native commands such as `/goal`, not only skills. The captured workspace and acceptance/rejection handling use existing `POST /session` and `POST /session/:id/command` contracts. `OpenCodeV2Adapter.kt` already supports those requests; no Kotlin change is required. Browser regressions cover Enter/button submission and rejected draft restoration. |
| `SessionGoalRow.tsx`, `Tooltip.tsx`, `UserMessageContent.tsx` and styles | Goal icons, row-width description panels, paused labels, completed-goal Close controls and `/goal` chips are vendored unchanged. Close still sends the existing `goal clear` command. Native goal RPC/events and kickoff identities remain the Kotlin implementation from the preceding sync. No new host tooltip, clipboard or window API is involved. |
| `record-snapshots.ts`, `MessageList.tsx`, `ChatInput.tsx`, `ChangedFilesList.tsx`, `SessionListView.tsx`, `state-messages.ts` | Per-record immutable scan snapshots and 128-record chunks bound reactive aggregation. Canonical records still own rows, editing, mutations and anchors; unfinished steps retain live fields. Stream probes subscribe to their lookup/range before untracked scans. Three new OpenJet host regressions check nested updates, unchanged/canonical identity, keyed reorder/removal/reinsertion, and five tail updates over 3,000 records without `HUGE_FAN_IN` diagnostics. |
| `MessageList.tsx`; Thinking-to-text reserve transfer | The UI moves the hidden Thinking row's measured height into the consumable append reserve before releasing the activity-summary anchor, rather than retaining two blank spaces. Session replacement, removal and visible Thinking clear the transfer. This is browser geometry, not Kotlin message truncation or protocol buffering. |

The new snapshot regressions exposed an older test-runner limitation:
`resolve.conditions` alone left Node using Solid's inert server entry for
external imports. `webview/vitest.host.config.mts` now explicitly resolves the
browser development runtime. Existing queued-context and theme fixtures flush
reactive writes before assertions. All 118 host cases pass against real browser
reactivity; no diagnostic is suppressed in the new 3,000-record fixture.
The vendored sources remain unmodified.

The final compatibility-helper `--full-check` passed
`./gradlew check buildPlugin --rerun-tasks` with the exact published V2 2.3.2
binary and the published V1 1.19.6 fixture enabled. All 920 enabled Kotlin tests
passed; only the opt-in existing-database usage benchmark was skipped. The V1
adapter, V2 adapter and all three managed-service cases passed without skips.
Fixtures use isolated HOME/XDG/database/service-state roots. Evidence is
`build/opencode-compatibility/2.3.2/verification.json` and
`build/upstream-sync-ee753860-release-check.log`.

All 118 browser host tests, TypeScript checking, vendor integrity and browser
bundling passed. Six compatibility-helper regressions and the vendor-integrity
script test passed. The upstream run passed 7,481 cases across 303 files,
including all webview/shared tests and the scoped diagnostic-filter tests.
All 49 Chromium cases passed for session goals, session-list commands, inline
chip alignment, activity-collapse handoff, short streaming transcripts, tool
grouping and native-thumb ownership in the browser harness. Logs are
`build/upstream-sync-ee753860-upstream-unit.log` and
`build/upstream-sync-ee753860-upstream-e2e.log`. These are fixture correctness
and work-count results, not native JCEF timing measurements.

The package is `build/distributions/varro-openjet-0.11.0.zip`, SHA-256
`5c2b3319b3c7cd1b4f0da0c7d472147f1403fdb99ac52ca2e54beca568e6b0bf`.
All 769 recorded source/build inputs remained unchanged through final checks.
Plugin Verifier 1.411 checked this exact archive against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible, with existing deprecated/experimental API warnings.
The verifier log is `build/upstream-sync-ee753860-verifier.log`.
No commit, push or tag was made.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Existing skill-admission difference | Varro `opencode-v2-adapter.ts`; OpenJet `OpenCodeV2Adapter.kt` prompt/command submission, described in the preceding review | Kotlin still uses text guidance for attached skills and lacks upstream's registry-checked skill-command fallback. Selected-skill admission can differ. This delta changes browser routing for native commands, not that older adapter behavior. |
| Existing backend/platform differences | Earlier review tables identify the Varro/OpenJet permission, annotation-root, quota, cleanup, local-file, thumbnail and Windows inspector implementations | Previously documented behavior differences remain, including AVIF previews, older summary/context fallbacks and cross-host state/polling differences. None of those upstream host implementations changed in this delta. |
| Native UI and performance not verified | Varro `MessageList.tsx`, `record-snapshots.ts`, goal/tooltip/chip UI; OpenJet `WebviewHost.kt` and the same vendored sources | Automated browser work-count and geometry tests do not establish JCEF scrolling or editor performance. No same-input native IDE performance comparison, interactive JCEF sandbox, live provider/goal execution, native Windows/Linux run or Docker image build was performed. No production session was used. |

No new required Kotlin backend or consumed-contract implementation gap remains
in the reviewed changes. No VS Code-only host change requires a JetBrains port.
The earlier differences and verification limits above remain.

## Upstream sync review, 2026-11-22, Varro 0.36.0

Reviewed `ee753860a0b425faf23eef356ec8f2744afd1ba5` through
`dc2ba8dc9a2a7dac9313b0d7d91cccb37633d346`, including every changed
extension source, shared protocol, contributed configuration and dependency
declaration. DOMPurify moves from 3.4.18 to 3.5.0; no new browser dependency,
setting or migration is required. Vendored sources remain unmodified.
Node/npm pins are unchanged. OpenCode baselines remain V1 1.19.6 and V2 2.3.2,
with support floors 1.16.0 and 2.0.5. There is no new released OpenCode range to
review. OpenJet remains 0.11.0.

| Upstream change | OpenJet implementation and regression coverage |
| --- | --- |
| `followup-usage.ts`, `rest-proxy.ts`, `util/webview-message.ts`, shared protocol; `GET /varro/session/:id/followup-usage` over native inbox/message reads | `FollowupUsage.kt`, `ApiRoutes.kt`, `RestProxy.kt`, `OpenCodeV2FollowupUsage.kt` and `OpenCodeV2Adapter.kt` authorize the canonical session before history, reject hidden/foreign sessions and request bodies, enforce cancellation/connection deadlines and return only bounded usage aggregates. Ten 200-record pages and 8 MiB bound history. Tests cover persisted delivery order, shared prompt intervals, goal kickoffs, compaction exclusion, retries, recorded zero versus missing/active usage, idle abandonment, page/byte limits, repeated cursors, pending inbox IDs, V1 unsupported and retained transfers. |
| `shared/prompt-text-attachments.ts`, `opencode-v2-adapter.ts`, `opencode-v2-projection.ts`, `session-transcript-archive.ts` | `protocol/PromptTextAttachments.kt`, `OpenCodeV2Adapter.kt`, `OpenCodeV2Projection.kt` and `TranscriptArchive.kt` preserve valid pasted text/annotations as length-prefixed prompt blocks, then restore attachment parts for history, direct reads and inbox projection. Tests check UTF-16 lengths, embedded closing tags, malformed/unknown blocks, empty prompts, invalid data URLs, ordinary files, command exclusions and both local/API transcript archives. |
| `auto-approve-judge.ts`, `native-permission-rules.ts`, `opencode-config-service.ts`, native configuration merge | `PermissionJudge.kt`, `NativePermissionRules.kt`, `OpenCodeHostServices.kt`, `ProjectPermissionConfig.kt` and `OpenCodeV2Adapter.kt` check fresh owning-workspace shell rules before inference, preserve last-match deny/ask exceptions, concatenate source arrays and skip only proven redundant project/global grants. Tests retain order/comments, whole-request decisions, config failure fallback and exact no-op documents without treating save suggestions as execution resources. |
| `message-content.ts`, `streaming-tool-content.ts`; task-card child/prompt attribution | `MessageContent.kt` reserves bounded identity fields outside the summary budget. `StreamingToolContent.kt` preserves them in success, progress and failure. Tests exhaust the shared budget before those fields and verify their survival without returning full tool bodies. |
| `sidebar-provider.ts`, `webview-session.ts`; retained hidden editors | `WebviewHost.kt` already retains its JCEF browser across tab switches. Showing-state changes now transfer automation ownership through `VarroProjectService.kt` and `PermissionAutomationLease.kt`, without cancelling document requests or suppressing events. A lease regression rejects hidden-editor automatic requests while preserving readiness and sidebar eligibility. The native view bridge and JetBrains file-editor lifecycle remain unchanged. |
| `attention-notifications.ts`, `native-notifications.ts`, macOS helper assets; window-owned dismissal | `AttentionNotifications.kt` detects focus return once per transition. `ProjectNotifications.kt` expires only its project's IDE notification and observes inter-project focus changes. The user explicitly chose to retain JetBrains' notification transport rather than add platform helpers. OS-banner retraction is an accepted platform difference, detailed below. |

The browser refresh includes workspace/session-owned drafts, restored transcript
state, adaptive history-cache budgets, native file-icon sizing, annotation
capacity after resize, incomplete Markdown deferral and streaming scroll fixes.
Per-follow-up task cards use native child/prompt identities and hide unavailable
usage rather than presenting it as zero. VS Code's immutable panel options and
legacy suspended-editor fallback have no JetBrains equivalent. Upstream lint
tooling, AI-runner diagnostics and notification-helper distribution do not add
OpenJet runtime endpoints.

The final compatibility-helper `--full-check` passed
`./gradlew check buildPlugin --rerun-tasks` with the exact published V2 2.3.2
binary and published V1 1.19.6 enabled. All 941 enabled Kotlin tests passed;
only the opt-in existing-database usage benchmark was skipped. The V1 adapter,
V2 adapter and all three managed-service cases passed without skips. The
expanded released fixture verifies pending/delivered follow-up usage without
provider calls or history changes, plus annotation prompt blocks and restored
file parts. HOME/XDG/database/service-state roots remain isolated.
Evidence is `build/opencode-compatibility/2.3.2/verification.json` and
`build/upstream-sync-dc2ba8dc-final-check.log`.

All 118 browser host tests, TypeScript checking, vendor integrity and browser
bundling passed. Six compatibility-helper regressions and the vendor-integrity
script test passed. The selected upstream run passed 8,585 unit cases across
318 files, including all webview/shared tests and changed host paths.
TypeScript suggestion diagnostics were zero. The Chromium run passed 138 cases
for annotation/chip sizing, Markdown, rendering, tool cards, cold pagination,
held-thumb ownership, delayed diff previews and failed streaming patches.
Four native-Windows font/alignment cases were skipped on macOS. The first
browser invocation hit the shell's two-minute deadline; the rerun completed
with a longer deadline. Logs are `build/upstream-sync-dc2ba8dc-upstream-unit.log`,
`build/upstream-sync-dc2ba8dc-upstream-suggestions.log` and
`build/upstream-sync-dc2ba8dc-upstream-e2e-retry.log`.
These are deterministic fixture checks, not live AI or native JCEF timing results.

The package is `build/distributions/varro-openjet-0.11.0.zip`, SHA-256
`4292cb794834283640eef3954d14c6a1a4c928ee57f4dc7af649cfaca9f913f4`.
Plugin Verifier 1.411 checked that exact archive against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible. Reports include deprecated/experimental API usage
and unresolved optional-module notices; these are not native flow verification.
The log is `build/upstream-sync-dc2ba8dc-verifier.log`.
No commit, push or tag was made.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Accepted notification transport difference | Varro `native-notifications.ts`, `macos-notification-helper.ts` and `native/macos-notifications`; OpenJet `ProjectNotifications.kt` using JetBrains `SystemNotifications` | Returning to the project expires its IDE notification, but the platform API exposes no handle for retracting the OS banner. The user chose to keep this transport. No native helper is installed, no other project's notification is expired, and pending permission/question state is not acknowledged or cleared. |
| Existing backend/platform differences | Earlier review tables identify Varro `opencode-v2-adapter.ts`, permission/config services, state-root discovery, quota coordination, cleanup and Windows inspection; OpenJet `OpenCodeV2Adapter.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `OpenCodeV2SessionState.kt`, `ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt`, `ArtifactCleanup.kt` and `ServerOwnership.kt` | Existing skill-admission, permission provenance/local-review reuse, annotation-root, non-Z.ai quota coordination, cleanup and inspector-warm-up differences remain. They were not re-audited beyond the host changes listed above. The new configured-shell rule checks do not claim to port every older local Auto rule. |
| Existing image and summary boundaries | Varro local/remote image, thumbnail and session-summary implementations; OpenJet `LocalImages.kt`, `RemoteImages.kt`, `ImageThumbnails.kt`, `LocalSessionSummary.kt` and `SessionSummaryService.kt` | Previously accepted AVIF preview limits, attach-only/local-file boundaries and older summary/context fallbacks remain. These upstream host implementations did not change in this delta. |
| Native UI and external integrations not verified | Varro draft, scroll, chip and task-card UI; OpenJet `WebviewHost.kt`, `ProjectNotifications.kt` and native editor APIs | Browser fixtures do not establish JCEF rendering, native scrolling performance, project-focus behavior or OS-notification delivery. No interactive IDE sandbox, live account/provider, native Windows/Linux run or Docker image build is included. Production sessions, credentials and service registrations are not test fixtures. |

No new required Kotlin host or consumed-contract implementation gap remains in
the reviewed changes, apart from the explicitly accepted OS-banner difference.
The older differences and verification limits above remain; this is not a claim
of full backend or native UI parity.

## Upstream sync follow-up, 2026-11-22, 220643ce

Reviewed `dc2ba8dc9a2a7dac9313b0d7d91cccb37633d346` through
`220643ce9a6a0eb7b6b1503372412fd0fed4cde6`, still Varro 0.36.0.
The two commits change browser draft navigation and todo restoration.
There are no changes under `src/extension` or `src/shared`, or in
`package.json`. Host endpoints, messages, settings, persistence APIs,
SDK/client baselines, dependencies and toolchain pins are unchanged.
V1 remains 1.19.6 and V2 remains 2.3.2, with support floors 1.16.0 and 2.0.5.
There is no new released OpenCode range to review. OpenJet remains 0.11.0.
The preceding sync's uncommitted changes were preserved, and vendor integrity
passed before replacement.

| Upstream change | OpenJet integration and regression coverage |
| --- | --- |
| `app-state.ts`, `input-draft-persistence.ts`, `ChatInput.tsx`; shared unsent text across session navigation | The unmodified vendor refresh shares text across root, child, New Chat and picker navigation in one view/workspace. Existing `webview/src/view-state.ts` and `project-storage.ts` keep it per-view, not project-wide. Four new `webview/src/draft-navigation-contract.test.ts` cases exercise actual host-extension storage and Solid state, editor/sidebar navigation, cleared input, reload, workspace isolation, late writes and conflict-preserving legacy draft adoption. Failed sends preserve current workspace ownership and do not install a retry for a different session. |
| `todo-sync.ts`, `session/session-selection.ts`, `open-code-runtime-instance.ts`, `TodoList.tsx` and composer CSS; native todo prefetch and hydration | The vendor refresh overlaps the existing `GET /session/:id/todo` with transcript loading, invalidates stale session/directory/generation results and bounds final consumption after messages arrive. Restored todos avoid entrance/progress animation. `ApiRoutes.kt`, `RestProxy.kt` and `OpenCodeV2Adapter.kt` already expose the required scoped request and cancellation. Review found an older Kotlin filter mismatch: native todo reads could select streaming or non-tool entries. The adapter now matches upstream's filter; two new `OpenCodeV2TodosTest` cases cover newest-first selection, malformed/partial entries, scope, cancellation and empty history without generation or lifecycle requests. |

All 122 browser host tests, TypeScript checking and vendor integrity passed.
The selected upstream run passed 698 unit cases across five files for composer
failure recovery, todo hydration/prefetch, session selection and draft persistence.
Nine Chromium session-management cases passed, including carry/clear behavior at
280 and 800 pixels. TypeScript suggestion diagnostics were zero.
Logs are `build/upstream-sync-220643ce-upstream-unit.log`,
`build/upstream-sync-220643ce-upstream-e2e.log` and
`build/upstream-sync-220643ce-upstream-suggestions.log`.

The final compatibility-helper `--full-check` passed
`./gradlew check buildPlugin --rerun-tasks` against the exact published V2 2.3.2
binary with published V1 1.19.6 also enabled. All 943 enabled Kotlin tests passed;
only the opt-in existing-database usage benchmark was skipped. The V1 adapter,
V2 adapter and all three managed-service cases passed without skips in isolated
HOME/XDG/database/service-state roots. Browser bundling and all 122 host tests
passed again. Six compatibility-helper regressions and the vendor-integrity
script test passed. Evidence is
`build/opencode-compatibility/2.3.2/verification.json` and
`build/upstream-sync-220643ce-final-check.log`.

The package is `build/distributions/varro-openjet-0.11.0.zip`, SHA-256
`5d7f5e4b03eb31770fb82b1f30c8780a7f8dc17e437f4ba653ec73668cd02744`.
Plugin Verifier 1.411 checked that exact archive against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible, with deprecated/experimental API usage and optional
module-resolution notices. Its log is `build/upstream-sync-220643ce-verifier.log`.
No commit, push or tag was made.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Accepted notification transport difference, unchanged | Varro `native-notifications.ts`; OpenJet `ProjectNotifications.kt` and JetBrains `SystemNotifications` | As chosen in the preceding sync, focus return expires the project's IDE notification but cannot retract its OS banner. No notification transport changed in these two commits. |
| Existing backend/platform differences, unchanged | The preceding review names Varro/OpenJet skill admission, permissions, annotation roots, quota coordination, cleanup, image/summary and Windows inspector implementations | Their documented behavior differences remain and were not re-audited in this browser-focused delta. No user state was moved or repaired outside the vendored draft migration. |
| Native UI not verified interactively | Varro `ChatInput.tsx`, `TodoList.tsx` and todo/draft hooks; OpenJet `WebviewHost.kt`, `view-state.ts` and `OpenCodeV2Adapter.kt` | Host/unit/Chromium fixtures do not establish JCEF focus, animation, latency or real-IDE draft restoration. No interactive sandbox, live provider, native Windows/Linux run or Docker build was performed. Tests use isolated fixtures, not production sessions or registrations. |

No new required Kotlin backend or consumed-contract implementation gap remains
in the reviewed delta. There are no new VS Code-only host changes to port.
The preceding accepted differences and the verification limits above remain.

## Upstream sync review, 2026-11-22, Varro 0.36.1

Reviewed `220643ce9a6a0eb7b6b1503372412fd0fed4cde6` through
`e197b0418644ccbf9af24f3813785aefe1462231`, including all changed
extension runtime sources, shared protocol, contributed configuration and
dependency declarations. The five commits add Yesterday usage reporting,
agent-permission fallback, reactive edit/attachment fixes, common-helper
refactoring and recognized slash-command highlighting. No new host endpoint,
message envelope, setting, browser dependency or toolchain pin is required.
SDK/client baselines remain V1 1.19.6 and V2 2.3.2, with support floors
1.16.0 and 2.0.5. No new released OpenCode range needs review.
The user's existing `pluginVersion=0.12.1` edit was preserved.
Vendored sources remain byte-identical to the recorded upstream snapshot.

| Upstream change | OpenJet alignment and regression coverage |
| --- | --- |
| `src/extension/usage-report-service.ts`; calendar-bounded Yesterday in local and API reports | `src/main/kotlin/varro/host/UsageReport.kt` adds an exclusive upper boundary and computes local calendar midnights. The shared aggregator covers `LocalUsageDatabase.kt`, archived statistics and REST fallback without changing database state. `UsageReportYesterdayTest.kt` checks first/last included milliseconds, excluded neighboring days, 23/24/25-hour days and identical V1/V2 local/API reports with and without All time. |
| `src/extension/opencode-v2-adapter.ts`; configured agent permission fallback for `/agent` | `OpenCodeV2Adapter.kt` no longer manufactures an empty configured list when native permissions are omitted/null, preserving the resolved agent restrictions. Explicit empty/configured lists remain authoritative. `OpenCodeV2Projection.kt` leaves unavailable agent permission data absent. Four `OpenCodeV2AgentPermissionsTest` cases cover omitted/null, explicit empty, explicit replacement and unknown data with read-only scoped catalog requests. The vendored completion helper displays only the role when permissions are unknown. |
| `src/webview/components/ChatInput.tsx`, message/attachment components and hooks | The unmodified refresh tracks composer-session input for inline edits and attachment-rail derivations, and reuses toolbar, store reconciliation, abort and dialog helpers. JetBrains `host-bridge.ts`, `view-state.ts`, `receive-channel.ts` and `WebviewHost.kt` retain their native transport and persistence contracts. Existing OpenJet browser contract tests pass against the refactored sources. |
| `src/extension` helper extraction, transport/startup cancellation, permission-write queues, recovery snapshots and quota adapters | Review found behavior-preserving consolidation rather than new endpoints or policies. Kotlin equivalents remain `OpenCodeTransport.kt`, `OpenCodeServer.kt`, `SessionSelections.kt`, `PermissionService.kt`, `SessionTrash.kt`, `PermissionJudge.kt`, `CommitMessageService.kt`, `ContextProvider.kt`, `ProviderQuotaBackend.kt` and `quota/QuotaAdapters.kt`. The extracted TypeScript quota decoders/token selection retain their old behavior; no credentials or providers were contacted for this review. Removed unused functions and VS Code About/launcher helpers do not require Kotlin removals or JetBrains process changes. Shared event-parser consolidation retains the same IDs, sequence fields and envelope semantics. |

The initial OpenJet browser checks passed all 122 host tests, TypeScript
checking and vendor integrity. The targeted Chromium composer/context/edit,
draft/reload and session-navigation run passed 104 cases. TypeScript suggestion
diagnostics were zero. Focused Kotlin usage and agent-permission tests passed.

The full unmodified upstream unit run is not green: 13,246 cases passed,
63 were skipped and five failed across `Chat.test.ts` and `ChatInput.test.ts`.
Failures assert the absence of `STRICT_READ_UNTRACKED` warnings for
`createAppState.publishedSelection`, `createAppState.publishedText` and
`recognizedSlashCommandNames`. These are not Kotlin compatibility results.
No warning was suppressed and no vendor fix or source transform was added.
Evidence is `build/upstream-sync-e197b041-upstream-unit.log`.

The focused warning-only reproduction also failed: three selected cases failed,
21 passed and 744 were excluded by the name filter. It reproduces the warnings
without the full-suite workload. Evidence is
`build/upstream-sync-e197b041-upstream-reactivity-repro.log`.

OpenJet's final compatibility-helper `--full-check` passed
`./gradlew check buildPlugin --rerun-tasks` with exact published V2 2.3.2 and
published V1 1.19.6 enabled. All 948 enabled Kotlin tests passed; only the
opt-in existing-database usage benchmark was skipped. The V1 adapter, V2 adapter
and all three managed-service cases passed without skips in isolated
HOME/XDG/database/service-state roots. Browser bundling, vendor integrity,
TypeScript checking and all 122 host tests passed again. Six compatibility-helper
regressions and the vendor-integrity script test passed.
Evidence is `build/opencode-compatibility/2.3.2/verification.json` and
`build/upstream-sync-e197b041-final-check.log`.

An additional Chromium rendering, scrolling, diff, theme, layout, goal and tool-card
run passed 227 cases, for 331 passing Chromium cases across both selected runs.
These are deterministic browser fixtures, not live AI or native JCEF performance
measurements. Logs are `build/upstream-sync-e197b041-upstream-e2e.log`,
`build/upstream-sync-e197b041-upstream-scroll-e2e.log` and
`build/upstream-sync-e197b041-upstream-suggestions.log`.

The package is `build/distributions/varro-openjet-0.12.1.zip`, SHA-256
`647dfefc19e3404c0511a386d9aed977015d93ea0d7e0c30f0f7991bb80795cd`.
Plugin Verifier 1.411 checked that exact archive against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible, with deprecated/experimental API usage and optional
module-resolution notices. Its log is `build/upstream-sync-e197b041-verifier.log`.
No commit, push or tag was made for this sync.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Unresolved upstream UI regression | Varro `src/webview/components/ChatInput.tsx` and `chat-input/RichComposerArea.tsx`; identical files under `webview/vendor/webview/components/`, hosted by `WebviewHost.kt` | Five upstream tests fail on untracked composer/slash-command reactive reads. The diagnostics warn that these reads will not update, but a native JCEF user-visible failure has not been established. Repository policy prohibits patching the vendor snapshot or suppressing these diagnostics. UI regression verification remains incomplete. |
| Accepted notification transport difference | Varro `native-notifications.ts`; OpenJet `ProjectNotifications.kt` and JetBrains `SystemNotifications` | As previously chosen, focus return expires only the project's IDE notification, not its OS banner. No notification transport changed in this delta. |
| Older backend differences, unchanged | Varro skill admission, state-root discovery, permission provenance/local review, quota coordination and cleanup; OpenJet `OpenCodeV2Adapter.kt`, `OpenCodeV2SessionState.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt` and `ArtifactCleanup.kt` | Previously documented direct-skill, cross-host annotation/polling, permission-review and cleanup differences remain. The reviewed refactors do not resolve those older differences. |
| Platform and interactive verification limits | Varro local/remote images, summaries and Windows inspector; OpenJet `ImageThumbnails.kt`, `LocalImages.kt`, `RemoteImages.kt`, `SessionSummaryService.kt`, `ServerOwnership.kt` and JCEF integration | Accepted AVIF preview limits, attach-only/local-file boundaries, older summary fallbacks and Windows inspector warm-up differences remain. No interactive sandbox, native Windows/Linux run, live provider/quota/OAuth account or Docker build was used. Tests use isolated fixtures, never production sessions or service registrations. |

No new missing Kotlin host or consumed-contract implementation gap was found
after the listed alignment. The unmodified upstream UI failures remain unresolved,
so this review does not claim a fully verified sync or full backend/UI parity.

## Upstream sync follow-up, 2026-11-22, Varro 0.36.2

Reviewed `e197b0418644ccbf9af24f3813785aefe1462231` through
`6321b614c68416cf8671b79b54d1993b4ddc7635`. The five published commits
add plan implementation menus and handoffs, per-agent model selection,
original-agent preservation during prompt editing, the upstream slash-command
highlighter fix and a sticky-preview test correction.

The full changed extension, shared and package/configuration diff was reviewed.
The only extension runtime change avoids appending a duplicate extension to
text-document names. Shared code adds Markdown attachment language detection;
the protocol itself is unchanged. There are no new settings, message envelopes,
server routes or dependency/toolchain changes. SDK/client baselines remain
V1 1.19.6 and V2 2.3.2; support floors remain 1.16.0 and 2.0.5. No new
released OpenCode range needs review. OpenJet remains 0.12.1.

| Upstream behavior | OpenJet alignment |
| --- | --- |
| `RichComposerArea.tsx` passes command names into the highlighter and refreshes catalog-only changes without rebuilding the editor | The upstream fix is vendored verbatim. The five previously failing warning assertions now pass in the complete upstream unit run. No warnings were suppressed and no vendor adaptation was added. |
| `agent-model-selection.ts`, `ChatInput.tsx`, session actions/controls and loaders select agent models and retain the original edited prompt's agent | The browser refresh carries these changes over existing catalog, selection and prompt APIs. Review found that Kotlin's `/agent` merge could replace a resolved model with a null configured value. `OpenCodeV2Adapter.kt` now preserves the resolved model and variant when configuration omits/nulls its model, while honoring configured replacements. `OpenCodeV2AgentModelsTest.kt` first reproduced the missing fallback, then passed all three cases, including native-less configuration and object/string model references. Existing permission fallback coverage remains green. |
| `plan-content.ts` summarizes revised plans through `POST /session/:id/side-question`; plan menus open, edit or hand a plan to a new chat | `OpenCodeV2Adapter.kt`, `OpenCodeV2SideQuestions.kt`, `SideQuestions.kt` and `RestProxy.kt` already implement the consumed contracts with canonical location checks, bounded generation and cancellation. The new UI reuses them. Single-turn and V1 plans use final response text instead of requesting V2 summarization. No new endpoint or helper-session fallback was added. |
| `plan-handoff.ts` stores message-keyed, bounded handoff markers separately from skipped-plan session state | The unmodified browser code uses project storage through `webview/src/project-storage.ts`, `VarroProjectService.kt` and `VarroStore.kt`. Per-view draft handling remains separate. Agent-model recollection is browser-instance memory, matching upstream rather than introducing host persistence. |
| `tool-output-document-provider.ts` preserves existing filename extensions; `pasted-text.ts` detects Markdown attachments | `VarroProjectService.kt` already forwards title/language to `EditorIntegration.openText()`. Its read-only `LightVirtualFile` preserves the sanitized supplied title without appending a suffix and selects Markdown from the language value. The VS Code URI-provider filename implementation itself does not apply to JetBrains. |
| `MessageList.tsx` avoids tracking scroll-anchor layout during structural message derivation | Vendored unchanged with the plan-action styling and split controls. No Kotlin transport or JCEF-specific scroll workaround was added. |

Verification:

- Vendor integrity passed before and after replacement. The recorded snapshot
  has no local upstream changes. Missing native macOS optional dependencies were
  restored with `npm ci --include=optional`, without changing the lockfile.
- All 126 OpenJet browser host tests and TypeScript checking passed.
- The unmodified upstream unit run passed 13,301 cases, with 63 skipped.
  This includes the highlighter regressions and the formerly failing five cases.
  Evidence is `build/upstream-sync-6321b614-upstream-unit.log`.
- The selected Chromium run passed 128 cases and failed four theme variants of
  `e2e/tests/plans.spec.ts` at its border-color count assertion. It expects three
  distinct computed border colors across every action button but observes four
  with the new split controls. Later alignment/clipping assertions in those four
  cases were not reached. The other selected plan, composer, inline-edit, draft,
  reselect and scrolling cases passed. No upstream test or vendor CSS was edited.
  Evidence is `build/upstream-sync-6321b614-upstream-e2e.log`.

The compatibility helper's `--full-check` passed
`./gradlew check buildPlugin --rerun-tasks` with published V2 2.3.2 and
V1 1.19.6 enabled. All 966 enabled Kotlin tests passed; the existing opt-in
database benchmark was the only skipped test. The V1 adapter, V2 adapter and
all three managed-service cases passed without skips in isolated
HOME/XDG/database/service-state roots. Browser bundling, TypeScript checking,
vendor integrity and all 126 host tests passed again. Six compatibility-helper
regressions and all three vendor-integrity script tests passed. Upstream
TypeScript suggestion diagnostics were zero.
Evidence is `build/opencode-compatibility/2.3.2/verification.json`,
`build/upstream-sync-6321b614-full-check.log` and
`build/upstream-sync-6321b614-upstream-suggestions.log`.

The package is `build/distributions/varro-openjet-0.12.1.zip`, SHA-256
`cf36b237c2e04ae51b986e83e7057546f22101b49086167736a50b4b80e067e2`.
Plugin Verifier 1.411 checked that exact archive against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
All five were compatible, with deprecated/experimental API usage and optional
module-resolution notices. This is static compatibility verification, not an
interactive IDE run. Evidence is `build/upstream-sync-6321b614-verifier.log`.
A concurrent local commit, `0fa48f0`, captured the source refresh and Kotlin
alignment while verification was running. This session did not create a commit,
push or tag; final documentation changes remain uncommitted.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Unresolved upstream Chromium assertion | Varro `e2e/tests/plans.spec.ts`, `MessageRows.tsx` and `messages.css`; identical component/styles under `webview/vendor/webview/`, hosted by `WebviewHost.kt` | All four themes fail the border-color count assertion for split plan controls. This is not a Kotlin failure; a native JCEF rendering defect has not been established. Those cases do not verify their subsequent layout assertions. |
| Existing dependency audit findings | Locked `seroval` 1.6.8 and its Solid dependents in `webview/package-lock.json` | A fresh npm audit reports four moderate package entries rooted in Seroval serialization advisories, GHSA-f86p-h4j2-7qgj and GHSA-9wx9-5pv9-3349. This delta changes no dependencies. Exploitability in the client-only JCEF build was not assessed, and no unrelated audit upgrade was applied. Evidence is `build/upstream-sync-6321b614-npm-audit.json`. |
| Accepted notification transport and older backend differences | Varro native notifications, skill admission, state roots, permission review, quota coordination and cleanup; OpenJet `ProjectNotifications.kt`, `OpenCodeV2Adapter.kt`, `OpenCodeV2SessionState.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt` and `ArtifactCleanup.kt` | Previously documented differences remain unchanged. Focus return expires project-owned IDE notifications, not OS banners. This refresh does not claim to resolve those older differences. |
| Interactive/platform verification limits | Plan previews/menus, summaries and JCEF; OpenJet `EditorIntegration.kt`, `WebviewHost.kt` and V2 generation | No native IDE sandbox, Windows/Linux run, live model/provider/quota/OAuth account or Docker build was exercised. Browser tests use deterministic fixtures and compatibility tests use isolated state, never production sessions or service registrations. |

No new missing Kotlin host or consumed-contract implementation gap remains
after the agent-model fallback alignment. The outstanding upstream Chromium
assertions leave UI verification partial; this is not a claim of full native
UI or backend parity.

## Upstream sync follow-up, 2026-11-22, 5ba2e501

Compared the complete trees at `6321b614c68416cf8671b79b54d1993b4ddc7635`
and `5ba2e501dd4baca99de8d4d571d382d9c0f2489f`, still Varro 0.36.2.
Only `e2e/tests/plans.spec.ts` differs. It compares the action buttons'
top-border colors instead of the combined four-sided border-color property,
which includes the split button's intentionally darker divider.
The sticky-preview expectation mentioned by the latest commit was already
present in the previous recorded tree.

There are no changes to extension runtime sources, shared protocol, browser
runtime sources, configuration, package declarations or lockfile. No endpoints,
messages, settings, persistence or server contracts changed. SDK/client baselines
remain V1 1.19.6 and V2 2.3.2, with support floors 1.16.0 and 2.0.5.
There is no new released OpenCode range to review or required Kotlin change.
The preceding staged sync, including its Kotlin agent-model fallback and three
regression cases, was preserved. OpenJet remains 0.12.1. No commit, push or tag
was made by this session.

The regular sync script refreshed the recorded revision. Its source hash remains
`c70fe5590ddc3308b9e9f61ecf173435aafdf90590616328b5540aa3eae1ab8a`;
vendored source bytes are unchanged and integrity checks passed before and after
replacement. No vendor patch or source transformation was used.

All ten Chromium plan cases passed, including the four previously failing theme
variants and their subsequent alignment/clipping assertions at 480px and 280px.
The old plan-style assertion failures are resolved in this revision.
Evidence is `build/upstream-sync-5ba2e501-plans-e2e.log`.
OpenJet's 126 browser host tests and TypeScript checking passed. Six
compatibility-helper regressions and three vendor-integrity tests also passed.
The full unmodified upstream unit run passed 13,301 cases with 63 skipped.
Upstream TypeScript suggestion diagnostics were zero. Evidence is
`build/upstream-sync-5ba2e501-upstream-unit.log` and
`build/upstream-sync-5ba2e501-upstream-suggestions.log`.

The compatibility helper's `--full-check` passed a fresh
`./gradlew check buildPlugin --rerun-tasks` against published V2 2.3.2,
with published V1 1.19.6 also enabled. All 966 enabled Kotlin tests passed;
only the existing opt-in database benchmark was skipped. The V1 adapter,
V2 adapter and all three managed-service cases passed without skips in
isolated HOME/XDG/database/service-state roots. Bundling, TypeScript checking,
vendor integrity and all 126 host tests passed again. Evidence is
`build/opencode-compatibility/2.3.2/verification.json` and
`build/upstream-sync-5ba2e501-full-check.log`.

The rebuilt package is `build/distributions/varro-openjet-0.12.1.zip`, SHA-256
`cf36b237c2e04ae51b986e83e7057546f22101b49086167736a50b4b80e067e2`.
It is byte-identical to the preceding sync's archive. Its existing five passing
Plugin Verifier results therefore still apply, against IU-252.28539.97,
IU-253.33813.55, IU-261.27258.48, IU-262.10968.63 and IU-263.6800.24.
Verifier was not rerun for this test-only refresh. The retained evidence is
`build/upstream-sync-6321b614-verifier.log`, with the deprecated/experimental
API usage and optional module-resolution notices documented above.

Remaining differences and verification limits:

| Status | Upstream files/endpoints and OpenJet code | User-visible impact |
| --- | --- | --- |
| Accepted notification and older backend differences, unchanged | Varro native notifications, skill admission, state roots, permission review, quota coordination and cleanup; OpenJet `ProjectNotifications.kt`, `OpenCodeV2Adapter.kt`, `OpenCodeV2SessionState.kt`, `PermissionJudge.kt`, `NativeDecisions.kt`, `ProviderQuotaBackend.kt`, `quota/QuotaAdapters.kt` and `ArtifactCleanup.kt` | The preceding documented differences remain. In particular, focus return expires project-owned IDE notifications but cannot retract OS banners. This test-only delta changes none of those behaviors. |
| Existing dependency advisories, unchanged | Locked Seroval and Solid dependencies in `webview/package-lock.json` | The preceding Seroval audit findings remain. No dependency changed, no audit upgrade was applied and client-only JCEF exploitability was not assessed. |
| Interactive/platform coverage | Varro plan controls and transcript UI; OpenJet `EditorIntegration.kt`, `WebviewHost.kt` and server adapters | Chromium checks are deterministic browser fixtures, not an interactive native IDE sandbox or Windows/Linux test. No live provider/quota/OAuth account or Docker build was exercised. Released compatibility tests use isolated state, never production sessions or service registrations. |

No new missing, partial or uncertain Kotlin host or consumed-contract
implementation gap remains in the reviewed test-only delta. This does not
claim full backend or native UI parity for the older differences above.

## Verification

The opt-in integration test launches a released CLI with isolated HOME, XDG directories and database. Its provider is a local deterministic HTTP fixture. It checks authenticated health and SSE, catalogs, pending input, prompt streaming, reopened history, helper and stateless generation, archive/restore, saved-permission editing when supported, permission and form acknowledgement, fork, tail deletion, session deletion, and read-only V1 import. Steering checks interrupt a stalled turn, resume pending steering, and verify after completion and 0, 75 and 250 ms sampling delays that queued input stays parked. It has been run against OpenCode 2.0.6, 2.0.25, 2.0.26, 2.0.28, 2.0.29, 2.0.32, 2.1.1, 2.1.2, 2.1.3, 2.1.4, 2.2.0, 2.2.1 and 2.2.3; the steering regression has been run against 2.0.26, 2.0.28, 2.0.29, 2.0.32, 2.1.1, 2.1.2, 2.1.3, 2.1.4, 2.2.0, 2.2.1 and 2.2.3. From 2.1.2, the fixture also verifies that native free-model exclusions survive both projected provider catalogs. From 2.2.0, it verifies decision endpoint discovery, signed-out server-owned limits, non-chat model filtering and the renamed text-generation route and selected-model admission error. From 2.2.3, it verifies confirmed cleanup with usage and parent-transcript preservation.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeV2IntegrationTest'
```

The adapter, steering and managed-service checks also passed on published 2.2.5, 2.2.6, 2.2.7, 2.3.0 and 2.3.2,
including the offline-maintenance restoration case, as recorded in the latest
sync review above.

For a sync or version bump, use the pinned-release helper instead:

```sh
python3 scripts/check-opencode-compatibility.py --version 2.3.2
```

It runs both released suites, verifies the installed and served version, rejects
missing/skipped/failed tests, forces a fresh Gradle run, and writes a JSON result.
Gradle also tracks binary paths, contents, and expected versions as test inputs,
so ordinary opt-in runs cannot reuse a previously skipped result.

The focused V1 fixture checks the current release's catalog variants and retired
sharing behavior. Supply an exact installed executable without changing global
CLI installations:

```sh
VARRO_OPENCODE_V1_TEST_BINARY=/absolute/path/to/opencode \
VARRO_OPENCODE_V1_TEST_VERSION=1.19.6 \
  ./gradlew test --tests 'varro.server.OpenCodeV1IntegrationTest'
```

It has passed on published 1.19.3, 1.19.4, 1.19.5 and 1.19.6. It uses a separate HOME, XDG roots, database, workspace and listener. It does not
establish V1 streaming, live-provider or support-floor parity.

Unit tests cover 2.0.5 health discovery, V1 fallback, HTML and authentication failures, credential redaction, pagination, transcript identity, permission retry, annotations, native config edits and read-only import with cleanup. IDE rendering and interactive OAuth sign-in still need manual verification in a sandbox IDE.

`OpenCodeManagedServiceIntegrationTest` checks private registration, coexistence with an isolated
global-service fixture, second-host restoration and managed restart. It has been run against
OpenCode 2.0.26, 2.0.28, 2.0.29, 2.0.32, 2.1.1, 2.1.2, 2.1.3, 2.1.4, 2.2.0, 2.2.1 and 2.2.3. Both services use fixture-only HOME, XDG roots and database. The additional offline-maintenance restoration case has been run on 2.2.3. Set
`VARRO_OPENCODE_DESKTOP_TEST_BINARY` to another released V2 binary to check version coexistence.
It also retires a fixture listener, launches a moved-port replacement with the same private
service password, and checks recovered ownership, follower restoration and lifecycle stop
without changing the separate global service.

Recovery-claim unit fixtures cover V1 1.19.0, V2 2.0.5 and V2 2.0.26 health routes, including
publication ordering and cancellation. Ownership fixtures cover retired-process and PID-reuse
recovery, unknown and foreign accounts, credential and HTTP PID mismatches, conflicting claims,
changed records and listener reinspection. These are not released V1 or V2-floor CLI runs, nor
Windows/Linux or real-IDE verification. Status update hints track upstream's tested SDK/client
baselines, V1 1.19.6 and V2 2.3.2; that does not claim every Kotlin flow was tested on both releases.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeManagedServiceIntegrationTest'
```
