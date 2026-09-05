## Why

Two operational rough edges surfaced while running the real wall:
- The dev machine drives the wall through `./gradlew :wdb-cli:run --args="..."`, which is slow and awkward for day-to-day use — there is no `wdb` binary.
- Updating the agent on a machine is fully manual: stop it, copy a new build over, reinstall (we did exactly this by hand for wall-02). On a wall of N machines that does not scale.

Both are about shipping and updating the toolchain without hand-work.

## What Changes

- **`wdb.exe`**: package the CLI as a self-contained app-image (jpackage + bundled JBR, same pattern as the agent) via a `:wdb-cli:packageCli` Gradle task, so the wall is driven from a single binary instead of Gradle.
- **Agent self-update**: a new `wdb agent-update [--all] [--host <addr>]` command distributes a new agent build (the full self-contained app-image, zipped) to one or many machines; each agent extracts it as a new version, atomically swaps a `current` junction to it, and restarts onto the new version.
- **Versioned install layout**: `install` lays the agent out as `agent/<version>/` plus a `current` junction that the Task Scheduler task points at (the layout deferred from bootstrap D11), so the running binary is never overwritten in place.
- **Rollback on a bad update**: the previous agent version is retained; if a freshly-swapped agent does not come back healthy, the junction reverts to the previous version and that agent is restarted, so a bad agent build cannot brick a wall machine.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `agent-lifecycle`: add self-update — distributing a new agent build to a machine, swapping to it via a versioned layout, restarting, and rolling back to the previous agent version if the new one fails to come back healthy.

## Impact

- **Code**: new `:wdb-cli:packageCli` Gradle task (mirrors `:wdb-agent:packageAgent`). `wdb-agent` gains a versioned install layout (`agent/<version>/` + `current` junction), an agent-update receive path (store new agent jars, swap junction, self-restart, health-gated rollback), and a `MachineStatus` agent-version field is already present for verification. `wdb-client`/`wdb-cli` gain an `agent-update` operation/command.
- **Protocol**: a new control/stream path for the agent-build upload (additive; same manifest+blobs shape as app push, but targeting the agent install rather than a deployment). No major-version bump.
- **Docs**: `scripts/verify-install.ps1` and README-style usage updated to reference `wdb.exe` and the versioned layout.
- **Non-goals**: updating the bundled JBR runtime itself (rare; stays manual); auto-update on a schedule (this is operator-triggered); the CLI packaging introduces no new system behavior (build tooling only, no spec delta).
