# OpenCode V2 support

OpenJet supports V1 from 1.16.0 and V2 from 2.0.5. The webview and shared sources were synced from Varro's `main` branch at `61aa4b2b4ccb`, version 0.33.18. Tested baselines are V1 1.19.4 and V2 2.1.3. The Kotlin adapter handles released-server contracts, including generated skill, shell and synthetic transcript records. Synthetic text appears as automatic-action notices; canonical subagent completion metadata produces linked subagent activities. Pending and running V2 patch calls render as active edits. Assistant history preserves automatic retry metadata for recovery notices. History attachments use deferred references with Kotlin-generated thumbnails and on-demand original reads.

V2 sessions support `/pause` and a Resume action in the transcript. Pausing parks queued messages and interrupts with `resume=false`. Pause markers persist in the shared Varro session annotations, and session summaries and usage reports exclude paused time. Commit-message generation uses the stateless endpoint when the server advertises it, falling back to a helper session when the endpoint or selected model is unavailable before generation starts.

## Connection and startup

The webview now emits validated static module-preload hints and caches versioned assets. Historical sync reviews below record the absence of those optimizations at the time of each review. Windows process-inspector warm-up remains a separate difference. Both API families now enforce request deadlines through response-body reads, including cancellation after headers arrive.

`OpenCodeTransport` validates JSON health responses before selecting the protocol. V2 uses `/api/info`, with `/api/status` for 2.0.5. V1 uses `/global/health`. An HTML fallback page is not a healthy API response.

OpenJet prefers the private service registration under Varro's shared `servers/opencode-service/opencode/service.json`. With no explicit command and before private selection, it can also adopt a live global service registered in `$XDG_STATE_HOME/opencode/service.json`. It checks the loopback URL, PID and server version. New managed V2 launches use `--service` and a process-only `XDG_STATE_HOME` pointing to that private service directory, separate from Desktop's registration. Configuration, provider credentials and session history keep their existing locations. Nested tool CLIs inherit the private service. Do not execute the same session concurrently in independent servers.

Startup waits for private registration credentials before probing new V2 launches, within the same cancellation-aware health deadline. Verified private selection survives lease/marker restoration and does not fall back to Desktop on recovery or restart. Existing running global servers are reused without migration; their next managed relaunch uses the private service. Managed launches redact generated passwords before retaining startup output. HTTP requests and SSE use the same host-only authorization, including `OPENCODE_SERVER_PASSWORD` and `OPENCODE_SERVER_USERNAME` for external servers.

Recovering registered windows acquire the shared launch claim before reading registration or
probing health, so an owner's replacement credentials are fully published first. Explicit V2
service discovery can recover ownership of a moved-port replacement using an exact matching
password from a private retired Varro lease. Recovery verifies the sole listener, executable,
birth identity, account evidence and authenticated HTTP PID, with `/api/status` fallback for
2.0.5. It rejects known foreign users, preserves the original coordination key, and publishes
a fresh owner without the retired injected config. Uncertain identity and credential-only
companions retain attach-only behavior and cannot grant restart or maintenance rights.

After an authentication failure, OpenJet retries credentials stored for the server URL in the IDE password safe, then prompts for a username and password if needed. It saves credentials only after a successful health check. Cancellation and failed verification leave the server disconnected.

The default CLI search prefers `opencode2` over `opencode`. An explicit command takes precedence. Package-manager updates use `@opencode/cli` for V2 and `opencode-ai` for V1.

## Adapter responsibilities

- `OpenCodeV2Adapter.kt` translates catalogs, configuration reads, session operations, prompts, helper generation, permissions, forms, provider authentication and MCP operations.
- `OpenCodeV2Projection.kt` maps native history into Varro messages and parts. Control records update selection context without becoming transcript rows. Skill and shell records appear as tool activity.
- `OpenCodeV2Events.kt` maps native SSE events into the shared webview event vocabulary. History and streamed content use the same part IDs.
- `OpenCodeV2BackgroundWork.kt` tracks running shells through execution completion and the follow-up turn. Status snapshots reconcile with newer events; abort stops waiting shells before interrupting the session.
- `OpenCodeV2BackgroundServices.kt` persists service choices under `background-services/` in the shared V2 state directory. Services remain visible without keeping a session busy, and ordinary abort does not stop them. `BackgroundProcessJudge.kt` uses the session model and stateless generation to classify lifetime, with a 20-second deadline and at most four concurrent reviews per adapter. Invalid verdicts preserve the current choice. Reviews run again after 5, 10, 20 and 30 minutes, then every 30 minutes; manual choices always win.
- `OpenCodeV2SessionState.kt` persists Varro metadata and fallback timestamps for older servers. Capable servers use native archive state without migrating or deleting old annotations. These files are compatible with Varro's annotations.

Background-process list, stop, service-choice and output routes check session ownership before exposing or mutating a shell. List responses exclude private metadata and log paths. Initial log reads probe size and return only the last 64 KiB; subsequent reads use byte cursors with the same chunk limit. Saved choices use Varro-compatible owner-directory locks and atomic writes. Stopping a shell can notify and resume the assistant through OpenCode's normal completion flow.

History reads include admitted user inputs still in the inbox, marked with their queue or steer delivery mode. Pagination filters control records and reads preceding context to recover assistant parent IDs. Aggregate response budgets and repeated-cursor checks bound those reads.

Resuming pending steering uses `/api/session/:id/interrupt?resume=true`, not a prompt wake.
It consumes existing steering without admitting a new message or unpausing queued input.

Interrupted idle records remain in history with `MessageAbortedError`, including interruptions
before the first assistant response. Live execution interruptions emit the same error before
idle. Unread interruptions therefore retain red session markers after reload without appearing
as successful completions or provider failures.

Text and reasoning use separate type-local part ordinals so streamed content reconciles with interleaved history. Windows location requests uppercase absolute drive letters. Directory headers encode Unicode and percent escapes, with additional protection for V1's double-decoded directory query.

Authentication forms preserve prompt conditions, defaults and hidden fields. The UI hides those fields, while the adapter submits applicable defaults and converts boolean and numeric answers for OAuth and API keys. OAuth callbacks identify attempts by ID and workspace. Cancellation stops polling and attempts server-side cleanup. Configured model costs use the same pricing shape as catalog models, and partial limits preserve catalog values. Local provider-disable policies and permission overrides target the workspace's `.opencode` directory when an ancestor has a `.opencode` config.

On V2 2.1.2 and newer, native catalog exclusions for `opencode` and `opencode-go`
are authoritative. Configured-model fallback cannot restore excluded free models.
Returned models still receive configured overrides; custom providers and older
servers retain fallback. The policy uses validated health and clears on protocol reset.

OpenCode 2.0.25 external credential methods retain their catalog position alongside key and OAuth methods. The adapter posts the chosen method ID and typed form answers to `/api/integration/:id/connect/external`, then reports completion only after acknowledgement. It does not start or poll an OAuth attempt. Custom string fields remain text inputs even when the server supplies suggested options. Older servers keep their existing key and OAuth flows.

Ordinary sends in Default mode preserve session-scoped Always approvals; explicit mode changes can still reset rules.

Permission and form replies retain the owning session. The adapter removes pending requests only after the server acknowledges the reply. Native permission configuration keeps its ordered `permissions` array. Model routing edits preserve native `agents` keys when the target file uses V2 configuration.

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

## Verification

The opt-in integration test launches a released CLI with isolated HOME, XDG directories and database. Its provider is a local deterministic HTTP fixture. It checks authenticated health and SSE, catalogs, pending input, prompt streaming, reopened history, helper and stateless generation, archive/restore, saved-permission editing when supported, permission and form acknowledgement, fork, tail deletion, session deletion, and read-only V1 import. Steering checks interrupt a stalled turn, resume pending steering, and verify after completion and 0, 75 and 250 ms sampling delays that queued input stays parked. It has been run against OpenCode 2.0.6, 2.0.25, 2.0.26, 2.0.28, 2.0.29, 2.0.32, 2.1.1, 2.1.2 and 2.1.3; the steering regression has been run against 2.0.26, 2.0.28, 2.0.29, 2.0.32, 2.1.1, 2.1.2 and 2.1.3. From 2.1.2, the fixture also verifies that native free-model exclusions survive both projected provider catalogs.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeV2IntegrationTest'
```

For a sync or version bump, use the pinned-release helper instead:

```sh
python3 scripts/check-opencode-compatibility.py --version 2.1.3
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
VARRO_OPENCODE_V1_TEST_VERSION=1.19.4 \
  ./gradlew test --tests 'varro.server.OpenCodeV1IntegrationTest'
```

It has passed on published 1.19.3 and 1.19.4. It uses a separate HOME, XDG roots, database, workspace and listener. It does not
establish V1 streaming, live-provider or support-floor parity.

Unit tests cover 2.0.5 health discovery, V1 fallback, HTML and authentication failures, credential redaction, pagination, transcript identity, permission retry, annotations, native config edits and read-only import with cleanup. IDE rendering and interactive OAuth sign-in still need manual verification in a sandbox IDE.

`OpenCodeManagedServiceIntegrationTest` checks private registration, coexistence with an isolated
global-service fixture, second-host restoration and managed restart. It has been run against
OpenCode 2.0.26, 2.0.28, 2.0.29, 2.0.32, 2.1.1, 2.1.2 and 2.1.3. Both services use fixture-only HOME, XDG roots and database. Set
`VARRO_OPENCODE_DESKTOP_TEST_BINARY` to another released V2 binary to check version coexistence.
It also retires a fixture listener, launches a moved-port replacement with the same private
service password, and checks recovered ownership, follower restoration and lifecycle stop
without changing the separate global service.

Recovery-claim unit fixtures cover V1 1.19.0, V2 2.0.5 and V2 2.0.26 health routes, including
publication ordering and cancellation. Ownership fixtures cover retired-process and PID-reuse
recovery, unknown and foreign accounts, credential and HTTP PID mismatches, conflicting claims,
changed records and listener reinspection. These are not released V1 or V2-floor CLI runs, nor
Windows/Linux or real-IDE verification. Status update hints track upstream's tested SDK/client
baselines, V1 1.19.4 and V2 2.1.3; that does not claim every Kotlin flow was tested on both releases.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeManagedServiceIntegrationTest'
```
