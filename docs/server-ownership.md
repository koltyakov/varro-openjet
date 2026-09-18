# Shared OpenCode server ownership

Varro OpenJet uses the same version 1 ownership records as Varro. Ownership belongs
to the Varro product family, independently of the IDE, project, profile, or plugin
ID. Connecting to an arbitrary OpenCode server does not authorize stopping it.

The shared per-user directories are:

- macOS: `~/Library/Application Support/Varro/servers/`
- Linux: `$XDG_STATE_HOME/varro/servers/`, or `~/.local/state/varro/servers/`
- Windows: `%LOCALAPPDATA%\Varro\servers\`, or `~/AppData/Local/Varro/servers/`

Each configured port uses `varro-opencode-server-<port>.json`, a `.managed`
recovery marker, and a `.claim` coordination file. The lease stores the actual
listening port, including fallback ports. A valid legacy temporary-directory lease
or surviving marker remains the coordination point until retired.

OpenJet validates the listening PID, executable, and process birth identity before
claiming or stopping a server. macOS uses `ps`'s resolved launch path when `lsof`
reports a nonexistent executable after an upgrade. Linux identities include the
boot ID and process start ticks. Windows uses process creation ticks and
case-insensitive executable paths.

A live host retains ownership during normal attachment. Closing a project
relinquishes its lease and leaves the server available to other Varro hosts.
Another host can recover a relinquished lease or one whose host has exited.
Explicit restart can transfer a live lease. Stopping the process holds the claim
file and revalidates persisted ownership, so a retained child-process reference
does not authorize termination after a transfer.

Records use unique temporary files and atomic replacement, with retries for
transient replacement failures. POSIX records are owner-readable and writable.
Inspection failures preserve records. Malformed leases do not authorize recovery
from a marker over the existing file. Lease passwords remain host-only and survive
handoff.

Restart preflight checks global status, the session inventory, and pending questions
and permissions. On v2, deleted historical directories are skipped after checking
global status. Busy sessions and observed pending attention still block restart
even when their directory has been deleted. Other inspection failures prevent the
restart. Idle maintenance uses the same checks.

`ServerOwnershipTest` covers cross-host handoff, crashed-host recovery, legacy
marker recovery, PID reuse, inspection failure, and the macOS executable fallback.
`RestartPreflightTest` covers deleted directories and failed attention reads.
