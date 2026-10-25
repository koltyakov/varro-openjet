![Varro: OpenJet](https://raw.githubusercontent.com/koltyakov/varro-openjet/main/assets/banner.jpg)

# Varro: OpenJet

Run [OpenCode](https://opencode.ai) in JetBrains IDEs with [Varro](https://github.com/koltyakov/varro)'s chat UI. Use your existing providers, models, agents, commands, skills and MCP servers.

![Varro OpenJet in a JetBrains IDE](https://raw.githubusercontent.com/koltyakov/varro-openjet/main/assets/demo.jpg)

## Getting started

Requires a JetBrains IDE from 2025.2 onward with a JCEF-enabled runtime, plus OpenCode v2 2.0.5+ or v1 1.16.0+.

Install OpenCode using its [installation guide](https://opencode.ai/v2/docs/cli), or npm:

```bash
npm install -g @opencode/cli
```

Download the plugin ZIP from [GitHub Releases](https://github.com/koltyakov/varro-openjet/releases). In the IDE:

1. Open **Settings > Plugins > Install Plugin from Disk** and select the ZIP.
2. Restart and open the **Varro** tool window on the right.

Varro finds OpenCode on `PATH`, reuses a local service or starts one when needed. Set an explicit CLI path, server port or startup policy in **Settings > Tools > Varro**.

Automatic CLI updates are enabled by default for Varro-managed servers. You can disable them in settings.

## Using Varro

Chat in the tool window, an editor tab or a detached window. **Open in Window** requires IDE 2026.1+; on older IDEs, drag an editor tab into a separate window. Drafts are saved per view.

- Add context by dropping files into the composer or choosing **Add to Varro Context** in the editor or Project view.
- Queue follow-ups while a task runs. On v2, use `/btw` for a side question without interrupting it.
- Choose models and agents in chat. Set session or project permission rules in Permissions.
- Generate commit messages from the commit toolbar. Generation does not stage files or commit changes.
- Restore deleted sessions from the recycle bin for up to seven days.

### Settings

Open **Settings > Tools > Varro** for server startup, models, fonts and chat options.

- The active file or selection is attached automatically. Disable this under **Chat > Automatically attach the active file or editor selection**. IDE problems have a separate toggle.
- Permission mode starts at `auto`. `default` follows OpenCode's rules; `full` allows actions without confirmation.
- Notifications are off by default. Enable desktop alerts or sounds under **Notifications**.
- To share model preferences across JetBrains IDEs, open **Shared OpenJet settings** and choose **Use this IDE's settings to initialize sharing**. Appearance and session selections stay local.

### Database context

With Database Tools installed, Varro can attach loaded table metadata, SQL and selected rows. Use **Add to Varro Context** in a result grid to save a snapshot, or drag tables from Database Explorer into chat. Type `@` to search tables. Context capture uses data already loaded in the IDE and excludes connection URLs and credentials.

### Dictation

Start [Handy](https://github.com/cjpais/Handy), then use the composer's microphone to start or stop dictation. Right-click to cancel. Set its executable path under **Settings > Tools > Varro > Dictation** if needed. Disable Handy's **Auto Submit** to review text before sending. Dictation is unavailable on remote IDE hosts.

### Provider limits and usage

Quota badges show remaining allowances and reset times when available. Supported providers include Anthropic/Claude Code, OpenAI Codex, GitHub Copilot, OpenRouter, Gemini, Antigravity, Ollama Cloud, OpenCode Go, Z.ai, MiniMax, Kimi and xAI. Availability depends on the provider and account type.

Open usage reports from the tool-window menu for prompt counts, tokens, cache usage and assistant duration by provider and model.

### Network and credentials

OpenCode handles model requests. Attached context goes to the selected provider. Quota polling contacts providers directly; credentials stay in the IDE host, not the chat webview.

Remote Markdown images ask before loading by default. Loading one contacts the image server from the IDE host. Change this under **Remote Markdown images** in settings.

Enable prompt completion with **Use for prompt completion** in the Models menu. It requires v2 and an API-key model, not an OAuth subscription, and sends your unfinished draft and recent chat directly to the provider. Tab accepts a suggestion; Escape dismisses it.

Follow-up suggestions are enabled by default after completed Build responses when V2 supports stateless generation. They send the current request and final answer for extra inference, which may consume paid tokens or subscription quota. Disable **Suggest follow-up actions after Build responses** under **Settings > Tools > Varro > Models** to opt out. The session's model and reasoning are used unless you assign **Use for follow-ups** in Models. Hover an action to preview its prompt; clicking sends it without changing your permissions, draft or attachments.

## Documentation

- [Contributing and building](CONTRIBUTING.md)
- [Troubleshooting](docs/troubleshooting.md)
- [OpenCode v2 support and compatibility](docs/opencode-v2-support.md)
- [Architecture](docs/architecture.md)
- [CI and releases](docs/ci-and-releases.md)

## License

[MIT](LICENSE). The vendored [Varro](https://github.com/koltyakov/varro) UI is also MIT-licensed. [OpenCode](https://opencode.ai) supplies the agent runtime.
