# Troubleshooting

Start with the message shown in the Varro tool window. Server startup, missing browser runtime, and missing browser assets have separate error states.

## OpenCode CLI is missing

Check that OpenCode runs in your terminal:

```bash
opencode --version
```

If it is not installed, follow the [OpenCode installation guide](https://opencode.ai/docs) or install through npm:

```bash
npm install -g opencode-ai
```

If the terminal finds OpenCode but Varro does not, open **Settings > Tools > Varro** and set **CLI path** to the executable's absolute path. Use `command -v opencode` on macOS or Linux, or `where.exe opencode` on Windows, to locate it. The setting accepts a command name or executable path, not a shell command with arguments.

GUI-launched IDEs may inherit a different `PATH` from your terminal. Varro searches the inherited path and common global install locations, including OpenCode, Bun, Volta, pnpm, and Homebrew locations. It does not enumerate every Node version managed by nvm, fnm, or asdf. An explicit CLI path is useful for those installations.

After correcting the path, use **Restart OpenCode Server** from Find Action. If the executable is a script or shim, its interpreter must also be available to the IDE process.

## OpenCode update required

The current minimum CLI version is 1.16.0. Use the upgrade command suggested by the error, or update with the package manager used to install OpenCode. For an npm installation:

```bash
npm install -g opencode-ai@latest
opencode --version
```

Restart the server after upgrading. Updating the executable does not change the version of an already-running server.

Varro checks an existing server's reported version too. If another terminal or IDE project started that server, restart it from its owner. Varro's restart action does not stop an adopted running server.

Background updates apply only while a managed server is running and idle. They do not repair a startup blocked by an old CLI version.

## Server does not start or connect

1. Check **Settings > Tools > Varro > Port**, which defaults to `4096`.
2. If automatic startup is disabled, either enable it or start OpenCode yourself on that port:

   ```bash
   opencode serve --port 4096
   ```

3. Read the tool-window error and IDE log for startup output. A missing executable, early process exit, and incompatible server require different fixes.
4. After changing the port or CLI path, use **Restart OpenCode Server**. If Varro reports that the server is externally managed, restart it from its owner or restart the IDE after changing the connection settings.

Varro connects to a healthy OpenCode server already listening at the configured address. When a managed launch reports a port-in-use failure, it can try subsequent ports. Check the server status for the actual URL before investigating a connection on the default port.

If chat cannot authenticate with a provider, run `opencode auth login` or use `/connect` in chat. Provider authentication is separate from starting the local server.

## Embedded browser is unavailable

The message **Varro needs the embedded browser** means JCEF is missing, disabled, or unsupported by the current runtime.

Open Find Action, select **Choose Boot Java Runtime for the IDE**, and choose a runtime with JCEF. Restart the IDE. Use a supported IDE build from the range in [getting started](../README.md#getting-started).

## Webview bundle is missing or chat is blank

The message **The Varro webview bundle is missing** usually means a development build skipped browser compilation. From the checkout root, rebuild without `-PskipWebview=true`:

```bash
./gradlew buildPlugin
```

Or use `./scripts/build.sh`. Install the newly built ZIP and restart the IDE. A Gradle `clean` removes compiled browser assets, so a clean followed by a build with `skipWebview` cannot produce a complete plugin.

If the browser opens but remains blank, use **Open Varro Developer Tools** from Find Action and inspect the Console. Also inspect the IDE log for JCEF or `varro` errors. Record whether the problem affects both the tool window and editor chat tabs.

## Provider quotas are missing or stale

Chat and quota polling use different paths. Chat goes through OpenCode; quota polling runs in the Kotlin host and contacts provider endpoints directly. A working chat connection does not establish that quota polling has credentials or network access.

- Confirm that the provider supports quota reporting. See [provider limits and usage](../README.md#provider-limits-and-usage) for the supported adapters.
- Check the provider's existing login or API-key configuration. Supported adapters can refresh OAuth tokens, but invalid or revoked credentials need a new login.
- Check the IDE's HTTP proxy and certificate settings if the quota error concerns connectivity or TLS.
- After a failed poll, a badge may show the last successful snapshot for up to 15 minutes. Rate-limit responses trigger backoff, so repeated requests may not immediately produce a new poll.

Antigravity requires a detected local language-server port or both `ANTIGRAVITY_BASE_URL` and `ANTIGRAVITY_CSRF_TOKEN` in the IDE's environment. The URL must use a loopback IP address. Setting these only in a terminal after the IDE starts does not update the IDE's environment.

Quota caches belong to each project. Different project windows may temporarily display snapshots from different polls.

## Sessions or preferences look wrong

Check the session history scope and visible filters first. The catalog can show the current directory, descendants, or project scope. Deleted sessions are hidden from the catalog and retained in the recycle bin for 7 days; restore them from there before they expire.

Model preferences are shared across projects in one IDE. A session's selected model and permission mode belong to its project. Drafts and editor routes belong to individual views. These differences can explain why one setting follows you to another project while another does not.

After reconnecting, queued sends are reconciled with OpenCode history. If a send's result is uncertain, the host does not automatically retry it. Check the transcript before manually resending.

Before resetting state, identify the affected store in [persistence and recovery](architecture.md#persistence-and-recovery). Clearing JCEF's browser cache does not reset project-backed preferences. Varro state includes drafts, queue recovery, and recycle-bin records; deleting all of it is not a targeted fix for a display problem. If a specific store needs repair, close the IDE and keep a backup before editing it.

## Logs and bug reports

Use the IDE's Help menu or Find Action to locate **Show Log in Finder** on macOS or **Show Log in Explorer** on Windows. On Linux, look for **Show Log in Files**. Inspect `idea.log` near the time of the failure for `varro`, OpenCode startup, or JCEF messages.

For browser-side failures, open **Open Varro Developer Tools** and capture the relevant Console error. Host API calls use the Kotlin bridge, so the browser Network panel alone does not show every OpenCode or quota request.

Include these details in a [bug report](https://github.com/koltyakov/varro-openjet/issues):

- IDE product and full build number, operating system, and runtime from Help > About.
- Varro plugin version and `opencode --version` output.
- The exact error, reproduction steps, and whether the issue occurs in a tool window or editor chat.
- Whether Varro started the server or connected to one already running.
- Relevant log excerpts and screenshots, with credentials and private prompt content removed.
