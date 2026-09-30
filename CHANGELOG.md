# Changelog

## 0.5.0

- Synced the chat UI with Varro 0.30.15 at `ff2bebfc1ecd`.
- Added right-click image compression options in the composer, with size previews and an action to restore the original image.
- Gave steering messages distinct styling and turn labels. Sent steering messages cannot be edited; send another steering message to correct an instruction.
- Preserved explicit steering and queue delivery modes when reloading OpenCode V2 conversations.
- Fixed turn timers during steering and retries.
- Added a skipped-plan notice with actions to reopen or implement the plan.
- Improved prompt navigation and composer history.
- Added "Mark all as read" for filtered lists of completed, plan-ready, and failed sessions.
- Fixed bottom-follow scrolling when toggling inline file diffs, while preserving the scroll position when reading earlier messages.

Earlier release notes are in [`plugin.xml`](src/main/resources/META-INF/plugin.xml).
