# Session cleanup and usage export

The following actions are under **Tools > Varro** and in Find Action:

- **Export All-Time Usage to CSV** saves daily UTC totals by provider, model and recorded reasoning setting. Missing settings appear as `unknown`. The export includes token breakdowns and reported costs, not conversation text. It reads local history and the statistics archive without starting OpenCode or changing either database. Attach-only connections cannot export a different machine's local usage.
- **Delete Old OpenCode Sessions** asks how many calendar months to retain, whether to preserve trimmed transcripts, then asks for confirmation. Cancellation before confirmation changes nothing. Age uses last activity, not creation time. Cleanup spans all projects, archived sessions and dormant V1 records in the matching local database.
- **Compact OpenCode Database** separately confirms offline SQLite compaction. Cleanup never compacts automatically. Back up first and close other OpenCode clients.

Cleanup and compaction require a connected, verified local managed server. Active execution, running shells, background services and pending decisions prevent offline maintenance. Maintenance reserves the shared Varro launch claim, stops OpenCode, closes all SQLite work and restores OpenCode, including after cancellation or failure. A failed restart remains an error.

## Preservation before deletion

Cleanup processes older sessions first. Each recursively deleted tree is one unit. A newer, active or changed descendant protects its parent. Old children of a retained parent can still be removed separately. Sessions exactly at the cutoff stay. Retention uses UTC calendar months and clamps month-end dates.

For every unit, cleanup validates timestamps, directories, child membership and activity, commits the usage archive, optionally commits parent transcripts, then validates the unit again before calling OpenCode's delete API. It confirms every member's disappearance before removing any files. A missing-location error is not deletion confirmation. An archive or server failure stops later units; completed archives and deletions remain saved.

Dormant V1 cleanup runs separately with OpenCode stopped. A SQLite write transaction spans final source validation, preservation and deletion. Original V1 completion timing needed by retained V2 copies is saved independently. Unknown deletion triggers and cross-version foreign-key dependencies stop the operation. V2 records are not rewritten. Cancellation before dispatch rolls back the live transaction; an already-dispatched deletion must settle before restart.

Other clients can still change history. OpenCode has no conditional recursive-delete API, so do not resume candidates from another client during cleanup's final validation/deletion interval.

## Archive files

The statistics archive is `opencode-stats.db` beside `opencode.db`. The optional plaintext transcript archive is `opencode-transcripts.db`. A custom database such as `/data/chat.sqlite` uses `/data/chat-stats.db` and `/data/chat-transcripts.db`. Keep these sidecars with backups.

Usage reports and CSV exports merge complete live and archived snapshots by original session identity. Newer snapshots win; ties prefer V2, then live history. Imported copies do not double-count usage. Matching original completion timing and Varro pause boundaries preserve durations. Ordinary reads do not migrate old archives.

Statistics retain scalar identity, model/reasoning routes, timestamps, token counts and costs. They do not retain prompts, responses, tools, reasoning or image bytes. The independent timing table survives replacement of a usage snapshot.

Optional transcripts retain delivered parent prompts and final assistant responses, including available failed/interrupted partial replies. Pasted-text and chat-annotation contents are included. Other attachments retain names and MIME types with an omitted-bytes marker. Subagent transcripts, intermediate commentary, tools, reasoning and media bytes are omitted. These are reading copies, not resumable sessions, full backups or entries in a new archive viewer.

Unknown, malformed, incomplete or oversized parent history stops deletion rather than saving a partial transcript. History and transcript trees are bounded to 64 MiB, with at most 100,000 messages per session and 500,000 separately stored parts. Native V2 pagination handles forks and reverts; ordinary V2 records use read-only local projections with separately stored assistant parts taking precedence. JDBC progress handlers enforce cancellation/deadlines and connections close before retry or restart.

## Files and disk space

OpenCode owns its database/media deletion. OpenJet also journals authorized shared Varro artifacts before deleting sessions:

- `.opencode/attachments/<session-id>/` copies in verified project directories;
- legacy `storage/session_diff/<session-id>.json` files;
- structured references to generated `tool_*` files under OpenCode's managed `tool-output` directory;
- generated Varro temporary dropped files with a valid owner marker.

One read-only remaining-history scan protects files referenced by live V1/V2 messages, parts or queued input. No file is removed after an incomplete scan. Shared files and interrupted work remain journaled for the next run. Symlinked paths, arbitrary prose paths, external attachments, source files, repositories and worktrees are not deletion targets. OpenJet's pre-existing IDE-system attachment cache is not part of these shared Varro artifact formats and is left intact.

About reports physical V1/V2 session counts, the oldest stored session's age, database-plus-WAL size and reusable SQLite space. Deleting rows usually frees pages without shrinking the file. Confirmed compaction needs at least twice the larger of database-plus-WAL size or allocated SQLite pages, plus 64 MiB free. It obtains an exclusive lock, checkpoints WAL, runs `VACUUM`, checks integrity and restores OpenCode. Session content and archive databases are unchanged.

The Kotlin implementation retains its existing bounded usage scans, including the 1,000,000-message local scan and 250,000-message report aggregation limits. Very large histories can fail explicitly instead of matching upstream's unbounded all-time report path. CSV exports are limited to 100,000 daily rows and 64 MiB. No partial export is saved on a read or validation failure.
