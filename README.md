# Varro OpenJet

A port of [Varro](https://github.com/koltyakov/varro) - the OpenCode workbench for VS Code - to JetBrains IDEs.

Varro OpenJet runs [OpenCode](https://opencode.ai) inside IntelliJ IDEA, WebStorm, PyCharm, GoLand and the other IntelliJ-platform IDEs. It adds project-aware chat, parallel sessions, plan and change review, model and permission controls, and commit-message generation.

OpenCode stays responsible for agents, providers, models, commands, skills and MCP servers. Varro OpenJet reads that configuration and provides an IDE interface for it, so the same setup works in the OpenCode TUI, in VS Code through Varro, and here.

## How the port works

Varro's chat interface is ~80,000 lines of Solid and Tailwind, and it has no VS Code dependencies at all: it talks to its host through exactly four `window` globals and ordinary `postMessage` events.

```
window.__initialWebviewState   boot snapshot, inlined by the host
window.__initialTheme          theme kind
window.__sendToExtension(msg)  webview -> host
window.__vscodeWebviewState    synchronous key/value store
```

So this port **reuses the upstream webview verbatim** and reimplements only the host - the ~42,000-line VS Code extension layer - in Kotlin against the IntelliJ Platform.

```
┌─ IntelliJ Platform ──────────────────────────────────────┐
│                                                          │
│  VarroProjectService ── OpenCodeServer ──► opencode serve│
│         │                    │  REST + SSE               │
│         │                    │                           │
│    ┌────┴─────┐         RestProxy ── allowlist           │
│    │ Webview  │              │                           │
│    │  Host    │◄─────────────┘                           │
│    │ (JCEF)   │                                          │
│    └────┬─────┘                                          │
│         │  window.__varroHostSend / __varroReceive       │
│  ┌──────▼───────────────────────────────┐                │
│  │  Varro webview (vendored, unchanged) │                │
│  └──────────────────────────────────────┘                │
└──────────────────────────────────────────────────────────┘
```

The upstream sources live in [`webview/vendor/`](webview/vendor/), vendored by [`webview/scripts/sync-upstream.mjs`](webview/scripts/sync-upstream.mjs). See [docs/architecture.md](docs/architecture.md) for the full design and [docs/porting-status.md](docs/porting-status.md) for what is and is not implemented yet.

## Building

The build runs in Docker, so no JDK, Gradle or Node installation is needed on the host:

```bash
./scripts/build.sh          # incremental build against the working tree
./scripts/build.sh clean    # clean build inside the image
./scripts/build.sh verify   # tests plus the IntelliJ plugin verifier
./scripts/build.sh shell    # interactive shell in the build container
```

The installable plugin lands in `dist/varro-openjet-<version>.zip`.

A cold build downloads the IntelliJ Platform (over a gigabyte). `docker-compose.yml` keeps it in a named volume, so only the first build pays that cost.

<details>
<summary>Building without Docker</summary>

With a JDK 21 and Node 22+ on the host (Gradle comes from the wrapper):

```bash
./gradlew buildPlugin       # -> build/distributions/*.zip
./gradlew runIde            # launch a sandbox IDE with the plugin installed
./gradlew test
```

</details>

### Webview toolchain

Node and npm are pinned to exact versions, in three places that must agree:

| Where | What it pins |
| --- | --- |
| `webview/package.json` → `volta` | the host toolchain, applied automatically by [Volta](https://volta.sh) |
| `webview/package.json` → `engines` | a hard floor for anyone without Volta |
| `Dockerfile` → `NODE_VERSION` / `NPM_VERSION` | the container toolchain, verified after install |

npm serializes `package-lock.json` differently between versions, so an unpinned
toolchain rewrites the lockfile on every build — the host and the container
fighting over it. With Volta installed, `cd webview` switches you to the pinned
pair automatically; without it, `engines` will at least fail loudly.

Both the Gradle task and the Docker build use `npm ci`, which installs the
committed lockfile exactly and fails when it has drifted from `package.json`.
After deliberately changing a dependency, run `npm install` once to update the
lockfile and commit it.

### Refreshing the vendored webview

```bash
cd webview
npm run sync                       # clone/refresh upstream and re-vendor
VARRO_SOURCE=/path/to/varro npm run sync   # or vendor from a local checkout
```

The pinned upstream revision is recorded in `webview/vendor/UPSTREAM.json`.

## Installing

### From the CLI

```bash
./scripts/install.sh              # build, then install into every supported IDE
./scripts/install.sh --no-build   # install the artifact already in dist/
./scripts/install.sh --list       # show detected IDEs and which have it installed
./scripts/install.sh --ide IntelliJIdea2026.2
./scripts/install.sh --uninstall
```

This unpacks into the IDE's own plugins directory
(`~/Library/Application Support/JetBrains/<IDE>/plugins` on macOS,
`~/.local/share/JetBrains/<IDE>/plugins` on Linux). Updating is the same command:
the old copy is removed first, so no stale jar can linger on the classpath.

Only IDEs inside the plugin's declared build range are targeted, since older ones
would just report an incompatible plugin at startup. `--all` overrides that.

A JetBrains IDE reads its plugins directory only at startup, so **restart the IDE**
afterwards. On macOS:

```bash
osascript -e 'quit app "IntelliJ IDEA"' && sleep 3 && open -a 'IntelliJ IDEA'
```

### From the IDE

**Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick the zip from `dist/`.

### First run

1. Install the OpenCode CLI: `npm install -g opencode-ai` (1.16.0 or newer).
2. Configure a provider: `opencode auth login`.
3. Open the **Varro** tool window on the right.

Varro OpenJet starts OpenCode on `127.0.0.1:4096` the first time the tool window
needs it, and adopts a server that is already listening so several IDE windows can
share one.

## Requirements

- An IntelliJ-platform IDE **2025.2 or newer** (build 252-262), with the JCEF runtime. Built and verified against IntelliJ IDEA 2026.2.2.
- The [OpenCode CLI](https://opencode.ai/docs) 1.16.0 or newer, on `PATH` or set in **Settings | Tools | Varro**.
- A configured OpenCode provider.

## Settings

**Settings | Tools | Varro** mirrors upstream's `varro.*` settings: server port and CLI path, automatic start and update, default permission mode, sessions-pane side, chat and code font sizes, and the models used for the auto-approve judge and commit messages.

Font sizes default to `0`, meaning "follow the IDE", rather than duplicating a number the user already chose in the IDE settings.

## Actions and shortcuts

| Action | Default shortcut |
| --- | --- |
| Focus Varro Chat | `Ctrl+Alt+V` / `Cmd+Alt+V` |
| Add to Varro Context | `Ctrl+Shift+K` / `Cmd+Shift+K` |
| New Varro Session | - |
| Search Varro Sessions | - |
| Stop Varro Run | - |
| Restart OpenCode Server | - |
| Generate Commit Message with Varro | in the commit toolbar |

`Shift+Escape` hides the tool window while focus is inside the chat.

## Security notes

- The plugin talks only to a local OpenCode server on `127.0.0.1`. It sends nothing to any other host itself; OpenCode makes the provider requests using the credentials configured with the OpenCode CLI.
- The plugin never reads, stores or forwards API keys.
- Requests from the webview pass an explicit route allowlist ([`ApiRoutes.kt`](src/main/kotlin/dev/koltyakov/varrojet/host/ApiRoutes.kt)) before reaching OpenCode, so the host cannot be used as an open proxy onto the OpenCode API.
- Terminal commands the chat can request are restricted to a fixed allowlist of OpenCode authentication, install and upgrade commands.
- Commit-message generation never mixes staged and unstaged changes, never stages, and never commits.

## Credits

- [Varro](https://github.com/koltyakov/varro) - the original VS Code extension, whose webview this reuses.
- [OpenCode](https://opencode.ai) - the agent this is a front end for.

## License

[MIT](LICENSE). The vendored webview is MIT-licensed upstream Varro code.
