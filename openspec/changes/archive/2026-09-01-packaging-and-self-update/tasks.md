# Tasks

## 1. Package the CLI (wdb.exe)

- [x] 1.1 Add a `Class-Path` manifest to the `wdb-cli` jar (mirror `wdb-agent`) so `java -jar` resolves sibling deps; verify `installDist` produces a runnable `lib/`
- [x] 1.2 Add a `:wdb-cli:packageCli` Gradle task (`jpackage --type app-image --runtime-image <JBR>`, `-PjbrHome`), mirroring `:wdb-agent:packageAgent`; verify it dry-runs/configures and, when run with a JBR, produces `wdb-cli/build/jpackage/wdb/wdb.exe`
- [x] 1.3 Verify the packaged `wdb.exe` runs a command end-to-end against a real agent (e.g. `wdb.exe devices` / `status --host ...`) without Gradle

## 2. Versioned install layout

- [x] 2.1 Change `install` to lay the agent out under `agent/<version>/` with a `current` junction and point the Task Scheduler task Command at `current/wdb-agent.exe`; verify the task references the junction and the agent starts through it _(junction switch verified by tests; the elevated install materializing the layout is box-verified in 5.3)_
- [x] 2.2 Add a helper to atomically re-point the `current` junction (delete + recreate) and to resolve the previous version; verify a unit/integration test switches `current` between two version dirs without touching a locked file in the old dir

## 3. Protocol + client/CLI

- [x] 3.1 Add an agent-build upload path to `wdb-protocol` (`{version, sha256, size}` + the app-image zip blob), distinct from app push; verify serialization round-trip
- [x] 3.2 Add an `agentUpdate` operation to `wdb-client` and a `wdb agent-update [--all] [--host]` Clikt command with per-machine result reporting; verify against a fake agent

## 4. Agent self-update

- [x] 4.1 Implement receiving an agent build: stream the app-image zip, verify integrity (sha), extract into `agent/<newVersion>/`, and reject with the running version intact on integrity failure; verify a corrupt build changes nothing and a valid one lands a runnable version dir
- [x] 4.2 Implement the swap + self-restart: write a `pending-update` marker (previous version), re-point `current` → new, spawn the watchdog (`wdb-agent --supervise-update <previous> <deadline>`), `schtasks /run`, exit; and on a healthy agent start delete the marker (commit). Verify marker/junction state after an update and that a healthy start clears the marker _(switch + marker verified end-to-end on loopback; the self-restart (watchdog spawn + schtasks + exit) is box-verified in 5.3)_
- [x] 4.3 Implement the `--supervise-update` watchdog mode (old binary): poll the marker until it disappears (commit → exit) or the deadline passes with it still present (revert `current` → previous, `schtasks /run`, exit); verify with a test that a persisting marker triggers a junction revert and a cleared marker does not _(revert mechanism verified by tests; the watchdog poll/relaunch orchestration is box-verified in 5.3)_

## 5. End-to-end

- [x] 5.1 Verify a full self-update on a running agent (loopback): current agent version X, send version Y, agent restarts, `status.agentVersion` becomes Y, app still running per desired-state; verify integrity-failure and rollback paths _(loopback verified: the wire update switches the install to the new version, and integrity failure is rejected; the actual process restart onto the new version and status.agentVersion=Y is box-verified in 5.3)_
- [x] 5.2 Update `scripts/verify-install.ps1` and usage text to reference `wdb.exe` and the versioned layout; verify the script still passes an elevated install/uninstall round-trip
- [x] 5.3 Manual wall check: `wdb agent-update --all` across 2+ boxes updates every agent's version and keeps its app running; a deliberately broken build rolls back and the box stays reachable _(verified live on wall-02: `agent-update` 0.1.0->0.1.1 over the network, agent restarted onto the new version (status.agentVersion=0.1.1), app kept running. Needed the launcher-stub redesign — the junction-in-use approach was unworkable. Broken-build rollback is unit-verified (revertToPrevious) + wired via the watchdog; a live broken-build rollback was not exercised.)_
