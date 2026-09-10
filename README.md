# Varro OpenJet

[Varro](https://github.com/koltyakov/varro) for JetBrains IDEs. Run [OpenCode](https://opencode.ai) in IntelliJ IDEA, WebStorm, PyCharm, GoLand, or another IntelliJ-platform IDE.

The plugin reuses Varro's chat UI, with a Kotlin backend for editor integration, settings, persistence, and provider limits. It uses your existing OpenCode configuration for providers, models, agents, commands, skills, and MCP servers.

The port is still in progress. See [porting status](docs/porting-status.md) for supported features and known gaps.

## Getting started

You'll need:

- A JetBrains IDE in the supported build range, 252 through 262, with a JCEF-enabled runtime. The plugin is built against IntelliJ IDEA 2026.2.2.
- OpenCode CLI 1.16.0 or newer, available on `PATH` or configured in Settings > Tools > Varro.
- An OpenCode provider set up with `opencode auth login`.

If you haven't installed OpenCode yet, follow its [installation guide](https://opencode.ai/docs) or use npm:

```bash
npm install -g opencode-ai
opencode auth login
```

Build the plugin using the instructions below, then open Settings > Plugins > Install Plugin from Disk and select the ZIP. Restart the IDE and open the Varro tool window on the right.

Varro starts OpenCode at `127.0.0.1:4096` when needed. If a server is already listening, it connects to that server instead.

### Installing from a checkout

The install script can build the plugin and install it into your detected JetBrains IDEs:

```bash
./scripts/install.sh                         # build and install
./scripts/install.sh --no-build              # install the ZIP already in dist/
./scripts/install.sh --list                  # list detected IDEs
./scripts/install.sh --ide IntelliJIdea2026.2 # install into one IDE
./scripts/install.sh --uninstall
```

Run the same command to update, then restart the IDE. The script targets IDEs within the plugin's supported build range.

## Using Varro

Chat in the tool window or open a session in an editor tab. Editor tabs support IntelliJ's usual split and move controls. Drafts and session routes are saved per view.

To add context, drop files or directories into the composer, or choose **Add to Varro Context** from the editor or Project view. The current-document chip toggles automatic context and remembers your choice per project. Switching to a chat tab keeps the last source editor as context, including unsaved edits.

| Action | Shortcut |
| --- | --- |
| Focus Varro Chat | `Ctrl+Alt+V` / `Cmd+Alt+V` |
| Add to Varro Context | `Ctrl+Shift+K` / `Cmd+Shift+K` |
| Hide the tool window | `Shift+Escape` |

Other actions are under Tools > Varro and in Find Action. The tool-window options menu includes new editor chats, the file-diff toggle, settings, usage reports, and About. Commit-message generation is available in the commit toolbar.

Settings are under **Settings > Tools > Varro**. You can configure the server path and port, permission mode, chat layout, fonts, and commit-message model. A font size of `0` follows the IDE's font settings.

### Provider limits and usage

Quota badges show remaining allowances and reset times when the provider exposes them. The Kotlin adapters support Anthropic/Claude Code, OpenAI Codex, GitHub Copilot, OpenRouter, Gemini, Antigravity, Ollama Cloud, OpenCode Go, Z.ai, MiniMax, Kimi, and xAI. They use existing credentials and the IDE's HTTP proxy and certificate settings. No Node.js helper is required.

When a poll fails, Varro may show the last successful snapshot for up to 15 minutes. Quota caches are project-local. Antigravity needs a detected local language-server port or the `ANTIGRAVITY_BASE_URL` and `ANTIGRAVITY_CSRF_TOKEN` environment variables, with a loopback IP address in the URL.

Usage reports open as Markdown documents and cover retained history across projects. `/stats` supports daily, weekly, monthly, and all-time accounting. The current report implementation handles up to 250 sessions; use `opencode stats` for larger histories.

### Network and credentials

Chat requests go through the local OpenCode server. Provider-limit polling contacts provider quota endpoints directly, using credentials held in the IDE host. Credentials are not sent to the webview. Supported OAuth adapters can refresh expired tokens.

Webview API requests pass through a [route allowlist](src/main/kotlin/varro/host/ApiRoutes.kt), and terminal setup commands use a fixed allowlist. Commit-message generation reads staged or unstaged changes without mixing them, staging files, or creating commits.

## Building

### Docker

Docker provides the JDK, Gradle, and Node toolchain:

```bash
./scripts/build.sh          # build the working tree
./scripts/build.sh clean    # clean build inside the image
./scripts/build.sh verify   # tests and IntelliJ plugin verifier
./scripts/build.sh shell    # open a shell in the build container
```

The plugin ZIP is written to `dist/varro-openjet-<version>.zip`. The first build downloads the IntelliJ Platform, which is over a gigabyte. Later builds reuse the Docker volume cache.

### Local build

Install JDK 21 and the Node/npm versions listed in [`webview/package.json`](webview/package.json). [Volta](https://volta.sh) selects the pinned Node/npm versions automatically. Gradle uses the included wrapper.

```bash
./gradlew buildPlugin       # build/distributions/varro-openjet-<version>.zip
./gradlew runIde            # launch a sandbox IDE
./gradlew test
```

The build uses `npm ci`. If you change webview dependencies, run `npm install` in `webview/` and commit the updated lockfile. Keep the Node/npm versions in `webview/package.json` and `Dockerfile` in sync.

## Development

The chat UI runs in IntelliJ's embedded JCEF browser. Kotlin handles the host messages and connects to OpenCode over REST and SSE. See [architecture](docs/architecture.md) for the component map and bridge protocol.

Upstream UI sources live in `webview/vendor/`. To refresh them, run these commands from `webview/`:

```bash
npm run sync                              # fetch upstream and update vendored sources
VARRO_SOURCE=/path/to/varro npm run sync    # use a local Varro checkout
```

The pinned revision is recorded in `webview/vendor/UPSTREAM.json`.

`npm run test:host` tests the JetBrains webview bridge and quota event contract. The Kotlin quota backend is covered by `./gradlew test` from the repository root.

## License

[MIT](LICENSE). The vendored [Varro](https://github.com/koltyakov/varro) UI is also MIT-licensed. [OpenCode](https://opencode.ai) supplies the agent runtime.
