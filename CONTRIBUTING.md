# Contributing

Start with the [architecture guide](docs/architecture.md) for the component map, bridge protocol, and persistence boundaries. For problems running the plugin, see [troubleshooting](docs/troubleshooting.md).

## Set up a checkout

Local development needs JDK 21 and the Node/npm versions pinned in [`webview/package.json`](webview/package.json). Volta selects those Node/npm versions automatically. Use the repository's Gradle wrapper.

npm 12 blocks dependency install scripts by default. The webview's `allowScripts` approves the locked esbuild and optional fsevents installers. After dependency updates, review `npm install-scripts ls` before approving new versions. OpenJet has no Git dependencies and does not need upstream's Git allowlist.

From the repository root:

```bash
./gradlew buildPlugin
./gradlew runIde
```

The first command installs webview dependencies with `npm ci`, builds browser assets, and writes the plugin ZIP to `build/distributions/`. The second launches the configured sandbox IDE. The first build downloads the IntelliJ Platform and can take some time.

The vendored UI is committed, so a normal checkout does not need an upstream sync. To exercise chat in the sandbox, install OpenCode and configure a provider as described in [getting started](README.md#getting-started).

Docker can provide the build toolchain instead:

```bash
./scripts/build.sh
./scripts/build.sh verify
```

The Docker build writes the ZIP to `dist/`. Install it in a local IDE for UI verification.

## Choose where to make a change

| Change | Location |
| --- | --- |
| OpenCode startup, transport or workspace scoping | `src/main/kotlin/varro/server/` |
| Host messages, API routing or IDE integration | `src/main/kotlin/varro/host/` |
| Provider quota polling and parsing | `src/main/kotlin/varro/host/quota/`, `ProviderQuotaBackend.kt` |
| Persistent state | `src/main/kotlin/varro/store/` |
| Settings and IDE actions | `src/main/kotlin/varro/settings/`, `actions/`, `src/main/resources/META-INF/` |
| JetBrains browser adaptation | `webview/src/host-bridge.ts`, `webview/src/project-storage.ts` |
| Upstream chat UI and shared protocol | `webview/vendor/`, refreshed by the sync script |

Keep JetBrains-specific behavior in the host or bridge where possible. Sync replaces vendored directories, so a direct vendor edit must be accounted for during the next sync. Changes to the host contract may need matching Kotlin handling and browser contract coverage.

Generated assets in `src/main/resources/webview/` are ignored. Commit source changes rather than the generated bundle.

## Run checks

From the repository root:

```bash
./gradlew test
./gradlew check buildPlugin
```

`test` runs Kotlin tests and the webview host tests. `check buildPlugin` runs the checks and packaging used by CI. Kotlin reports are under `build/reports/tests/test/`.

For browser or vendored-source changes, run these from `webview/`:

```bash
npm run test:host
npm run typecheck
npm run build
```

Host tests cover project storage, editor context, session permission modes, quota events, and native bridge batching. Gradle's `check` also runs TypeScript checking and vendor integrity. Packaging verifies vendor integrity before bundling.

For Docker build-script changes, run `python3 scripts/test-build.py`. It checks cleanup scope, builder selection, argument forwarding and failure handling with a fake Docker executable. Also run `./scripts/build.sh clean` to verify the image build and Gradle cache-cleanup configuration against Docker.

For the released OpenCode V2 adapter, set `VARRO_OPENCODE_TEST_BINARY` to a V2 executable when running Gradle tests. `OpenCodeV2IntegrationTest` uses an isolated database and a local fixture provider. It skips when the variable is absent. See [V2 support](docs/opencode-v2-support.md).

Add regression coverage when changing persistence, request routing, protocol handling, or recovery behavior. Match the existing Kotlin tests in `src/test/kotlin/varro/` and browser tests in `webview/src/`.

For Kotlin-only iteration after an initial webview build:

```bash
./gradlew test -PskipWebview=true
```

This skips browser dependency installation, bundling, and host tests. `clean` removes browser assets, so rebuild without the flag before packaging or launching a sandbox after a clean.

Run `./gradlew verifyPlugin` locally when checking IntelliJ API compatibility. Do not run Plugin Verifier in Docker.

CI lints workflows, Python release helpers and shell syntax, runs script regressions, and uses `python3 scripts/check-opencode-compatibility.py --version <version> --full-check` for one Gradle check/build invocation with the isolated published V2 suites enabled. Pull requests, main pushes and manual CI runs do not run IDE verification. Tags select the successful main CI artifact for that exact commit and run the IDE matrix against that ZIP before publishing. See [CI and releases](docs/ci-and-releases.md) for version matching, handoff and rerun rules.

To verify an already-built ZIP without compiling or packaging it again, use `./gradlew verifyPlugin -PverificationArchive=/absolute/path/to/varro-openjet-<version>.zip -PskipWebview=true`. Add `-PverificationIde=2025.2.6.3` to check one IDE. Without `verificationArchive`, local `verifyPlugin` retains its normal build-then-verify behavior.

After workflow or release-script changes, run `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12`, `python3 scripts/test-release-artifact.py` and `python3 scripts/test-opencode-compatibility.py`. Release-gate fixtures cover commit/repository/workflow isolation, both tag/CI event orderings, immediate deferral without CI polling, draft ownership and duplicate-event suppression, cancelled CI, rerun attempts, expired artifacts, package identity and checksum mismatches. Network writes are mocked and checked for no automatic replay. Tests never publish a release or upload to Marketplace.

For the release helper files, run `ruff check --select E4,E7,E9,F,I,EXE,SIM,PLW scripts/{check-opencode-compatibility,test-opencode-compatibility,release-artifact,test-release-artifact}.py`. CI uses pinned Ruff 0.17.1 on Python 3.12. Keep shebang-bearing CLI files executable.

The verification matrix in `build.gradle.kts` must include the exact EAP build checked by Marketplace, not just an earlier build of the same branch. Database grid APIs and plugin dependencies can move between EAP builds. Keep the older branches in the matrix when adding a compatibility adapter. Plugin Verifier cannot validate reflective lookups, so test those against the actual IDE APIs and exercise database context capture in the target IDE before publishing.

## Verify in the IDE

Use `./gradlew runIde` and exercise the flow you changed. For bridge or persistence changes, useful checks include:

- Open the tool window and an editor chat tab; confirm session model and permission selections sync between them.
- Leave a draft, restart the sandbox, and check that the draft and editor route return.
- Add a source selection and an unsaved edit as context. Switch back to chat and confirm the source editor remains the context.
- For layout changes, check narrow and wide views and switch the IDE theme.

Use **Open Varro Developer Tools** from Find Action to inspect browser errors. The [troubleshooting guide](docs/troubleshooting.md#logs-and-bug-reports) explains how to collect host logs.

For large-transcript rendering measurements inside the IDE, use the [JCEF scrolling benchmark](docs/scrolling-benchmark.md). It supports manual scrolling, automatic bottom-follow, and native-input trace capture against synthetic fixtures.

The same guide documents opt-in production bridge timing with `-PhostBenchmarkDirectory=build/host-benchmark/run-1`. It measures real browser startup and native batch round trips across multiple chat views. Ordinary builds collect no timing samples.

`npm run watch` from `webview/` rebuilds browser assets as files change. It does not reload the sandbox or copy assets into an already-running plugin. Relaunch through Gradle to package the current resources.

## Refresh upstream sources

From `webview/`:

```bash
npm run sync
```

The script reads [`webview/upstream.json`](webview/upstream.json), fetches its configured ref, and replaces the configured vendor directories and asset files. The current ref is `main`. It excludes upstream test files and records the resolved commit in `webview/vendor/UPSTREAM.json`.

The source must support Varro host API version 1. Sync copies sources without adaptations and records
a content hash. Run `npm run sync:check` in `webview/` after syncing or before packaging. Add host
features through `src/host-bridge.ts` and context providers, never by editing vendor files or adding
source transforms.

To copy from a local Varro checkout instead:

```bash
VARRO_SOURCE=/absolute/path/to/varro npm run sync
```

A local sync copies the checkout's current files, including uncommitted edits. The recorded commit identifies its HEAD, not those edits.

Review the vendor diff for protocol changes and new host requests. Sync updates version specifiers for existing dependencies in `webview/package.json`; check upstream for any new dependencies the webview needs. When dependencies change, run `npm install` in `webview/` with the pinned toolchain and include the updated lockfile. Keep toolchain versions aligned with `Dockerfile`.

### Required backend compatibility and alignment

Sync copies the browser UI and shared sources, not upstream's `src/extension` backend. A sync request includes the Kotlin backend review, required alignment, and compatibility checks below unless the user explicitly requests a UI-only refresh. A successful build or `sync:check` does not establish parity with the Kotlin host.

Before reporting a sync complete:

1. Record the previous commit from `webview/vendor/UPSTREAM.json` before syncing. Compare it with the new revision, including upstream `src/extension`, `src/shared`, and configuration changes in `package.json`. If the previous revision or its diff is unavailable, report that limitation rather than assuming no backend changes.
2. Trace new or changed endpoints, messages, settings, persistence, server adapters, and platform behavior to their equivalents in `src/main/kotlin/varro/`. Check backend fixes even when they have no vendor diff. Implement required equivalents and regression coverage during the sync, without a separate request to include backend work. Preserve JetBrains-specific behavior where the VS Code change does not apply.
3. Compare upstream's `@opencode-ai/sdk` and `@opencode/client` baselines with OpenJet's status hints and current version references. Sync records their specifiers in `webview/vendor/UPSTREAM.json`, and a Kotlin regression test rejects status-baseline drift. If a baseline changes, review the complete released OpenCode diff from the previous OpenJet-tested version, including HTTP/OpenAPI routes, SSE events, configuration, provider/model shapes, permissions/questions, session/history/inbox records, and serve/upgrade behavior. Port consumed contract changes; keep support floors unchanged unless a new API requires raising them.
4. Run the Kotlin adapter and managed-service tests against the exact published V2 version. The helper installs a pinned CLI under ignored build output, verifies its version, runs the tests with isolated fixture state, rejects skipped/missing tests, and writes `build/opencode-compatibility/<version>/verification.json`:

   ```sh
       python3 scripts/check-opencode-compatibility.py --version 2.2.5
   ```

   An existing matching executable can be supplied with `--binary /absolute/path/to/opencode2`. Never point fixture tests at production sessions, databases, credentials, or service registrations. Upstream's TypeScript compatibility results are useful review evidence, not a substitute for Kotlin tests. When the V1 SDK changes, also review its released contract and run the affected Kotlin V1 tests; do not infer V1 parity from a V2 run.
5. Align tested-version hints and current documentation after the released checks pass. Preserve historical results and lower-bound fixtures. Add any remaining missing, partial, uncertain, or blocked behavior to the sync report, with its upstream files/endpoints, corresponding OpenJet code, and user-visible impact. Ask only for product choices, destructive migrations, or work beyond the upstream changes, not whether to include required backend alignment.

If no gaps remain in the reviewed changes, state that explicitly and name any review or verification limitations. If required alignment or released compatibility checks are blocked, report the sync as incomplete. Do not claim full backend parity for behavior that was not checked.

Run browser checks, then `./gradlew check buildPlugin` from the root, and verify the affected flows in the sandbox.

## Submit a change

Describe the behavior changed, how to reproduce or exercise it, and which checks you ran. Include screenshots for visible UI changes. Update the README or architecture guide when defaults, workflows, or host behavior change.

Release tagging and packaging are documented in [CI and releases](docs/ci-and-releases.md).
