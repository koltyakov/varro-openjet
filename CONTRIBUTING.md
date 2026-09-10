# Contributing

Start with the [architecture guide](docs/architecture.md) for the component map, bridge protocol, and persistence boundaries. For problems running the plugin, see [troubleshooting](docs/troubleshooting.md).

## Set up a checkout

Local development needs JDK 21 and the Node/npm versions pinned in [`webview/package.json`](webview/package.json). Volta selects those Node/npm versions automatically. Use the repository's Gradle wrapper.

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

The Docker build writes the ZIP to `dist/`. Install it in a local IDE for UI verification. See [building](README.md#building) for the other build modes.

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

Host tests cover project storage, editor context, session permission modes, and quota events. Type checking is a separate command; Gradle's `check` task does not invoke it.

Add regression coverage when changing persistence, request routing, protocol handling, or recovery behavior. Match the existing Kotlin tests in `src/test/kotlin/varro/` and browser tests in `webview/src/`.

For Kotlin-only iteration after an initial webview build:

```bash
./gradlew test -PskipWebview=true
```

This skips browser dependency installation, bundling, and host tests. `clean` removes browser assets, so rebuild without the flag before packaging or launching a sandbox after a clean.

Run `./gradlew verifyPlugin` locally when checking IntelliJ API compatibility. Do not run Plugin Verifier in Docker.

## Verify in the IDE

Use `./gradlew runIde` and exercise the flow you changed. For bridge or persistence changes, useful checks include:

- Open the tool window and an editor chat tab; confirm session model and permission selections sync between them.
- Leave a draft, restart the sandbox, and check that the draft and editor route return.
- Add a source selection and an unsaved edit as context. Switch back to chat and confirm the source editor remains the context.
- For layout changes, check narrow and wide views and switch the IDE theme.

Use **Open Varro Developer Tools** from Find Action to inspect browser errors. The [troubleshooting guide](docs/troubleshooting.md#logs-and-bug-reports) explains how to collect host logs.

`npm run watch` from `webview/` rebuilds browser assets as files change. It does not reload the sandbox or copy assets into an already-running plugin. Relaunch through Gradle to package the current resources.

## Refresh upstream sources

From `webview/`:

```bash
npm run sync
```

The script reads [`webview/upstream.json`](webview/upstream.json), fetches its configured ref, and replaces the configured vendor directories. The current ref is `main`. It excludes upstream test files and records the resolved commit in `webview/vendor/UPSTREAM.json`.

To copy from a local Varro checkout instead:

```bash
VARRO_SOURCE=/absolute/path/to/varro npm run sync
```

A local sync copies the checkout's current files, including uncommitted edits. The recorded commit identifies its HEAD, not those edits.

Review the vendor diff for protocol changes and new host requests. Check dependency changes in upstream against `webview/package.json`; sync copies sources but does not update this project's dependencies. When dependencies change, run `npm install` in `webview/` with the pinned toolchain and include the updated lockfile. Keep toolchain versions aligned with `Dockerfile`.

Run browser checks, then `./gradlew check buildPlugin` from the root, and verify the affected flows in the sandbox.

## Submit a change

Describe the behavior changed, how to reproduce or exercise it, and which checks you ran. Include screenshots for visible UI changes. Update the README or architecture guide when defaults, workflows, or host behavior change.

Release tagging and packaging are documented in [CI and releases](README.md#ci-and-releases).
