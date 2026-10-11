# OpenJet agent instructions

## Upstream sync

Follow [Refresh upstream sources](CONTRIBUTING.md#refresh-upstream-sources) for every upstream sync. Vendoring the UI does not port upstream's backend into the Kotlin host.

Compare upstream host, shared protocol, and configuration changes between the previous and new revisions against the Kotlin implementation. Before reporting the sync complete, list each missing, partial, or uncertain backend behavior, its upstream files or endpoints, the corresponding OpenJet code, and its user-visible impact. Ask the user which gaps they want implemented before porting them, unless the user already authorized that backend work. Report explicitly when no gaps were found, and disclose anything not reviewed or verified. A successful build or vendor integrity check is not proof of backend parity.
