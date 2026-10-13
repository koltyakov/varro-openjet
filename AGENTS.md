# OpenJet agent instructions

## Upstream sync

Follow [Refresh upstream sources](CONTRIBUTING.md#refresh-upstream-sources) for every upstream sync. Vendoring the UI does not port upstream's backend into the Kotlin host.

A request to sync with Varro includes Kotlin backend compatibility checking and alignment by default, not just vendoring the UI. Compare upstream host, shared protocol, configuration, and tested OpenCode versions between the previous and new revisions against the Kotlin implementation. Implement the required host changes and fixes, align tested-version hints and current documentation, and add regression coverage as part of the same sync. Do not ask whether to include required backend alignment. Ask only when the port needs a product decision, a destructive migration, or work outside the upstream changes.

When an OpenCode SDK/client baseline changes, review the corresponding released OpenCode diff and run the Kotlin adapter and managed-service integration tests against that exact published V2 binary in isolated HOME/XDG/database/server-state roots. Use `python3 scripts/check-opencode-compatibility.py --version <version>`; skipped tests or upstream TypeScript results are not Kotlin compatibility verification. Preserve the V1/V2 support floors unless a new consumed API requires raising one. Never use production sessions or service registrations for tests.

Before reporting the sync complete, list any remaining missing, partial, uncertain, or blocked backend behavior, its upstream files or endpoints, the corresponding OpenJet code, and its user-visible impact. Distinguish platform-specific changes that do not apply to JetBrains. Report explicitly when no gaps remain in the reviewed changes, and disclose anything not reviewed or verified. If required alignment or compatibility checks are blocked, report the sync as incomplete rather than silently deferring them. A successful build or vendor integrity check is not proof of backend parity.
