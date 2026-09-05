## Context

See proposal.md — Why. Two operational gaps: no `wdb` binary (driven via Gradle), and manual agent updates (we hand-copied to wall-02). The agent already: installs a Task Scheduler logon task (D11), bundles a JBR via jpackage (D13), receives app JARs with sha integrity (app-deployment), persists per-machine state, and reports `agentVersion` in status. Bootstrap D11 named an `agent/<version>/` + `current` junction layout but deferred it; this change implements it because self-update needs it.

## Goals / Non-Goals

**Goals:**
- A single `wdb.exe` to drive the wall.
- Operator-triggered agent update across N machines, no hand-copying.
- A bad agent build must never brick a machine (health-gated rollback).

**Non-Goals:**
- Updating the bundled JBR runtime (rare; stays a manual reinstall).
- Scheduled/automatic updates (operator-triggered only).
- Changing app deployment or any other capability.

## Decisions

### D1 — `wdb.exe` via a `:wdb-cli:packageCli` jpackage task
Mirror `:wdb-agent:packageAgent`: `installDist` the CLI, then `jpackage --type app-image --runtime-image <JBR>` into `wdb-cli/build/jpackage/wdb/wdb.exe`. The CLI jar already gets a `Class-Path` manifest pattern; add the same to `wdb-cli`. Pure build tooling — no spec change. `--win-console` (the CLI is a console tool).

### D2 — Versioned install layout with a launcher stub + version file (no junction)
`install` copies the app-image under `<base>/agent/versions/<version>/`, writes a `current-version` text file, and writes a `launch.cmd` stub. The Task Scheduler task runs `cmd /c <base>/agent/launch.cmd`; the stub reads `current-version` and starts `versions/%WDBV%/wdb-agent.exe`. Switching versions is a plain write to `current-version` — no locks, no reparse points. *Alternatives:* copy-over-in-place — rejected, the running exe is locked on Windows; a `current` junction (`mklink /J`) the task launches through — **rejected after it repeatedly failed on a real box**: Windows would not let a process re-point the junction it is executing through, and even a sibling process couldn't reliably re-point it during the handoff. The launcher-stub + version-file makes the switch a trivial, unlockable file write.

### D3 — Update payload is the full app-image (zip); simple and robust
An agent build = the whole jpackage app-image (`wdb-agent.exe` + `app/` + `runtime/`) packed as a single zip (~200 MB; ~2–4 s on gigabit). `agent-update` sends the zip with sha integrity; the agent extracts it into `agent/<newVersion>/` and re-points `current`. No knowledge of jpackage internals, no cfg/runtime surgery — extraction + junction only, which is why it is robust. *Alternative:* ship only the `lib/` jars (~5 MB) and reuse the box's runtime — smaller, but requires copying the launcher/cfg and junctioning the runtime into the new version dir (fragile, jpackage-internal). Deferred as a later optimization, mirroring how app push started whole-JAR with the blob cache deferred (D8-equivalent). A runtime change is therefore carried automatically by a full-image update.

### D4 — Applier switches the version file, watchdog relaunches and rolls back
On a verified update the applying agent extracts the app-image into `agent/versions/<newVersion>/`, switches `current-version` to it (a plain file write — safe even though the applier is the running agent), writes a `pending-update` marker (previous + new), spawns a watchdog from the previous version's binary (`agent/versions/<prev>/wdb-agent.exe`, a stable known-good process), and exits so its lock/ports free. The watchdog waits ~2 s, then `schtasks /run` (the launcher stub reads the now-new `current-version` and starts the new exe). The Job Object binds the app to the old agent, so the app is killed on the old agent's exit and relaunched by the new agent per persisted desired-state. Real-box hardenings: the starting agent retries the single-instance lock (waits for the old to release) and the TCP/UDP sockets use `SO_REUSEADDR`, so the handoff doesn't lose a lock/port race.

### D5 — Health-gated rollback via a watchdog spawned from the old (known-good) binary
Rollback without a live supervisor is the hard part, and a "new agent checks itself on startup" scheme fails if the new binary crashes *before* reaching the check — Task Scheduler restart-on-failure would just relaunch the broken new binary forever. So the revert decision is made by a process that does **not** run the new code: before handing off, the old (known-good) agent spawns a short-lived **watchdog from its own binary** (`wdb-agent --supervise-update <previousVersion> <deadlineSeconds>`).

Sequence: the updating (old) agent writes `agent/<new>/`, writes a `pending-update` marker naming the previous version, re-points `current` → new, spawns the watchdog, runs `schtasks /run` (Task Scheduler launches the new binary via `current`), and exits. The **new** agent, once healthy (server bound + announcing), **deletes the marker** = commit. The **watchdog** (old binary, survives the handoff) polls: marker gone → commit, exit; deadline reached with marker still present → re-point `current` → previous, `schtasks /run`, exit. Because the watchdog is the old binary, it reverts correctly even if the new binary is completely broken. The single-instance lock + the task's `IgnoreNew` policy keep the watchdog's `schtasks /run` from producing a second live agent.

*Alternatives:* new-agent startup self-check (rejected — can't fire if the new binary crashes first); old agent stays fully alive as supervisor (rejected — two agents contend for the port and Job Object).

### D6 — Protocol: an agent-build upload path, additive
Add an agent-update stream (kind or control message) carrying `{version, entries[{name,sha256,size}]}` + blobs, distinct from app `push` (which targets a deployment). Additive to the protocol; no major bump. `MachineStatus.agentVersion` (already present) is the verification signal.

## Risks / Trade-offs

- **Junction swap races the running exe** → we never touch the running version's dir; only re-point `current`, which the *next* launch reads. The current process keeps its own file handles until it exits.
- **New agent crash-loops before clearing the marker** → restart-on-failure reverts via the startup check (D5); the previous version is retained until commit.
- **`schtasks /run` needs the task to exist** → self-update requires the versioned install (D2); a manually-run (non-installed) agent updates the files but relies on the operator/logon to relaunch — documented.
- **Runtime drift** (new agent jars need a newer JBR) → out of scope; a jar-only update assumes runtime compatibility. A runtime bump is a manual reinstall.

## Open Questions

- Exact health deadline for the commit marker (seconds) — tune in implementation.
- Whether to keep more than one previous agent version — one previous is enough for rollback; GC the rest (mirror the deployment store).
