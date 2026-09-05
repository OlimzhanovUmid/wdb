# Design — reliable self-update relaunch

## Context

`superviseUpdate` (the watchdog, running from the previous version's binary) currently relaunches the
new version with `ProcessBuilder("schtasks", "/run", "/tn", TASK_NAME).start().waitFor()`, then waits
≤60s for the new agent to clear the pending-update marker (which `Main.runAgent` does once listening),
else `revertToPrevious()` + `schtasks /run` again. The log proved `schtasks /run` returns 0 but starts
nothing while Task Scheduler still holds the task's prior instance as running — the outgoing agent was
that instance and had just exited. `AgentInstallLayout.launchCmd` (`<base>/agent/launch.cmd`) is the
stub the task itself runs: it reads `current-version` and launches `versions/<v>/wdb-agent.exe run …`.

## Decisions

### D1 — Relaunch by spawning the launcher stub directly

Add `relaunch(layout)`: if `layout.launchCmd` exists, `ProcessBuilder("cmd", "/c",
layout.launchCmd.toString()).start()` — **detached, no `waitFor`** (it starts the long-running agent;
the watchdog must keep polling). Else fall back to `ProcessBuilder("schtasks", "/run", "/tn",
TASK_NAME).start()` (older installs without a stub). Spawning the stub is not gated by Task Scheduler's
single-instance policy, so it always starts the process; the single-instance `agent.lock` (Main.kt,
≤15s wait) serializes the handoff if the outgoing process is still releasing.

### D2 — Use it at both relaunch points

`superviseUpdate` calls `relaunch(layout)` for the initial launch of the new version and again after
`revertToPrevious()`. The health-poll loop and marker semantics are unchanged.

### D3 — Keep the logon task as-is

`Install.kt` still registers the `wdb-agent` LogonTrigger task pointing at `launch.cmd` for boot/logon
autostart. Only the *update relaunch* stops using `schtasks /run`.

### D4 — Logging unchanged

The existing `agent-update.log` calls stay; the "schtasks run exit=…" line becomes "relaunch via
launch.cmd" (or the schtasks fallback) so the trail still shows how the new version was started.

### D5 — Version + rollout

`wdbAgentVersion` → **0.2.12**. Because the watchdog runs from the *previous* version, the fix only
governs rolls made from a 0.2.12+ agent. The first roll onto 0.2.12 uses the outgoing agent's old
racy watchdog — retry until it commits (an identical retry already succeeded), or manual-install
0.2.12. After that, self-update is reliable.

## Non-Goals

- Changing the pending-update marker / health-deadline / rollback logic (unchanged).
- Touching the logon autostart task or `Install.kt`.
- Any Task Scheduler policy change (e.g. MultipleInstances) — sidestepped by not using `/run` for the
  relaunch.

## Risks

- **Stub missing** (pre-launcher installs) → falls back to `schtasks /run` (today's behavior, no
  regression).
- **Detached child inheriting handles / console** — `cmd /c launch.cmd` starts its own process; the
  watchdog does not wait on it, and the agent's own single-instance lock + shutdown hooks manage
  lifecycle as before.
