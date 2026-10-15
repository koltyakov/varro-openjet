# OpenCode V2 support

OpenJet supports V1 from 1.16.0 and V2 from 2.0.5. The webview and shared sources were synced from Varro's `main` branch at `23d8197ff4f7`, version 0.33.4. The Kotlin adapter handles released-server contracts, including generated skill, shell and synthetic transcript records. Synthetic text appears as automatic-action notices. Pending and running V2 patch calls render as active edits. Assistant history preserves automatic retry metadata for recovery notices. History attachments use deferred references with Kotlin-generated thumbnails and on-demand original reads.

V2 sessions support `/pause` and a Resume action in the transcript. Pausing parks queued messages and interrupts with `resume=false`. Pause markers persist in the shared Varro session annotations, and session summaries and usage reports exclude paused time. Commit-message generation uses the stateless endpoint when the server advertises it, falling back to a helper session when the endpoint or selected model is unavailable before generation starts.

## Connection and startup

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
- `OpenCodeV2SessionState.kt` persists metadata and timestamp overrides that the released server cannot patch. These files are compatible with Varro's annotations.

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

OpenCode 2.0.25 external credential methods retain their catalog position alongside key and OAuth methods. The adapter posts the chosen method ID and typed form answers to `/api/integration/:id/connect/external`, then reports completion only after acknowledgement. It does not start or poll an OAuth attempt. Custom string fields remain text inputs even when the server supplies suggested options. Older servers keep their existing key and OAuth flows.

Ordinary sends in Default mode preserve session-scoped Always approvals; explicit mode changes can still reset rules.

Permission and form replies retain the owning session. The adapter removes pending requests only after the server acknowledges the reply. Native permission configuration keeps its ordered `permissions` array. Model routing edits preserve native `agents` keys when the target file uses V2 configuration.

V2 has no session-sharing or LSP service. Sharing controls are hidden for V2 sessions. Tail deletion uses file-preserving staged revert and commit; arbitrary single-message deletion returns an error. Unsupported operations report an explicit error.

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

## Verification

The opt-in integration test launches a released CLI with isolated HOME, XDG directories and database. Its provider is a local deterministic HTTP fixture. It checks authenticated health and SSE, catalogs, pending input, prompt streaming, reopened history, helper and stateless generation, permission and form acknowledgement, fork, tail deletion, session deletion, and read-only V1 import. Steering checks interrupt a stalled turn, resume pending steering, and verify after completion and 0, 75 and 250 ms sampling delays that queued input stays parked. It has been run against OpenCode 2.0.6, 2.0.25, 2.0.26, 2.0.28 and 2.0.29; the steering regression has been run against 2.0.26, 2.0.28 and 2.0.29.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeV2IntegrationTest'
```

For a sync or version bump, use the pinned-release helper instead:

```sh
python3 scripts/check-opencode-compatibility.py --version 2.0.29
```

It runs both released suites, verifies the installed and served version, rejects
missing/skipped/failed tests, forces a fresh Gradle run, and writes a JSON result.
Gradle also tracks binary paths, contents, and expected versions as test inputs,
so ordinary opt-in runs cannot reuse a previously skipped result.

Unit tests cover 2.0.5 health discovery, V1 fallback, HTML and authentication failures, credential redaction, pagination, transcript identity, permission retry, annotations, native config edits and read-only import with cleanup. IDE rendering and interactive OAuth sign-in still need manual verification in a sandbox IDE.

`OpenCodeManagedServiceIntegrationTest` checks private registration, coexistence with an isolated
global-service fixture, second-host restoration and managed restart. It has been run against
OpenCode 2.0.26, 2.0.28 and 2.0.29. Both services use fixture-only HOME, XDG roots and database. Set
`VARRO_OPENCODE_DESKTOP_TEST_BINARY` to another released V2 binary to check version coexistence.
It also retires a fixture listener, launches a moved-port replacement with the same private
service password, and checks recovered ownership, follower restoration and lifecycle stop
without changing the separate global service.

Recovery-claim unit fixtures cover V1 1.19.0, V2 2.0.5 and V2 2.0.26 health routes, including
publication ordering and cancellation. Ownership fixtures cover retired-process and PID-reuse
recovery, unknown and foreign accounts, credential and HTTP PID mismatches, conflicting claims,
changed records and listener reinspection. These are not released V1 or V2-floor CLI runs, nor
Windows/Linux or real-IDE verification. Status update hints track upstream's tested SDK/client
baselines, V1 1.19.0 and V2 2.0.29; that does not claim every Kotlin flow was tested on both releases.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeManagedServiceIntegrationTest'
```
