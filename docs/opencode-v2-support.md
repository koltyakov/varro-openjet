# OpenCode V2 support

OpenJet supports V1 from 1.16.0 and V2 from 2.0.5. The webview and shared sources were synced from the local Varro checkout's `v2` branch at `d6cccc26808f`. The Kotlin adapter follows that branch's released-server contracts, including its generated skill and shell transcript records. Pending and running V2 patch calls render as active edits.

## Connection and startup

`OpenCodeTransport` validates JSON health responses before selecting the protocol. V2 uses `/api/info`, with `/api/status` for 2.0.5. V1 uses `/global/health`. An HTML fallback page is not a healthy API response.

With no explicit command, OpenJet can adopt a live service registered in `$XDG_STATE_HOME/opencode/service.json`. It checks the loopback URL, PID and server version. Managed launches capture the generated password before retaining startup output. HTTP requests and SSE use the same host-only authorization, including `OPENCODE_SERVER_PASSWORD` and `OPENCODE_SERVER_USERNAME` for external servers. Startup output redaction handles split chunks.

The default CLI search prefers `opencode2` over `opencode`. An explicit command takes precedence. Package-manager updates use `@opencode/cli` for V2 and `opencode-ai` for V1.

## Adapter responsibilities

- `OpenCodeV2Adapter.kt` translates catalogs, configuration reads, session operations, prompts, helper generation, permissions, forms, provider authentication and MCP operations.
- `OpenCodeV2Projection.kt` maps native history into Varro messages and parts. Control records update selection context without becoming transcript rows. Skill and shell records appear as tool activity.
- `OpenCodeV2Events.kt` maps native SSE events into the shared webview event vocabulary. History and streamed content use the same part IDs.
- `OpenCodeV2SessionState.kt` persists metadata and timestamp overrides that the released server cannot patch. These files are compatible with Varro's annotations.

History reads include admitted user inputs still in the inbox. Pagination filters control records and reads preceding context to recover assistant parent IDs. Aggregate response budgets and repeated-cursor checks bound those reads.

Permission and form replies retain the owning session. The adapter removes pending requests only after the server acknowledges the reply. Native permission configuration keeps its ordered `permissions` array. Model routing edits preserve native `agents` keys when the target file uses V2 configuration.

V2 has no session-sharing or LSP service. Sharing controls are hidden for V2 sessions. Tail deletion uses file-preserving staged revert and commit; arbitrary single-message deletion returns an error. Unsupported operations report an explicit error.

## V1 history import

Use **Tools > Varro > Import OpenCode v1 Session into v2** while connected to V2. Import reads the local database through a read-only SQLite transaction. It honors `OPENCODE_DB`, otherwise using the standard XDG OpenCode database.

The importer copies the selected conversation and same-workspace descendants with new session and message IDs. It retains original records in import metadata, converts embedded images and completed tool output, and records unfinished tools as interrupted results. External file references stay in the preserved records and appear as attachment labels. The import does not call prompt or tool-execution endpoints. New copies use destination permission defaults.

Limits are 100 sessions, 10,000 messages and 100,000 parts per session, 32 MiB of history, and a ten-second SQLite query deadline. If a tree import fails, OpenJet deletes copies already created by that import and reports cleanup failures.

Usage reports read both V1 and V2 tables. For an imported conversation and its original, the report selects the newer complete session rather than counting both copies.

## Verification

The opt-in integration test launches a released CLI with isolated HOME, XDG directories and database. Its provider is a local deterministic HTTP fixture. It checks authenticated health and SSE, catalogs, pending input, prompt streaming, reopened history, helper generation, permission and form acknowledgement, fork, tail deletion and session deletion. It has been run against OpenCode 2.0.6.

```sh
VARRO_OPENCODE_TEST_BINARY=/absolute/path/to/opencode2 \
  ./gradlew test --tests 'varro.server.OpenCodeV2IntegrationTest'
```

Unit tests cover 2.0.5 health discovery, V1 fallback, HTML and authentication failures, credential redaction, pagination, transcript identity, permission retry, annotations, native config edits and read-only import with cleanup. IDE rendering and interactive OAuth sign-in still need manual verification in a sandbox IDE.
