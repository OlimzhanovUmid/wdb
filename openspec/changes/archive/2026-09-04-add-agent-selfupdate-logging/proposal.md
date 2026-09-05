## Why

When `agent-update` rolls back, there is no trail on the wall to explain why. A 0.2.9 roll to wall 1
applied cleanly (extract + `switchTo` + marker) yet the agent came back on 0.2.8 — a watchdog revert —
and we could not tell whether the scheduled task launched the wrong version, `detectInstallBase()`
returned null, or the new agent missed the 60s health deadline. `wdb logs` streams the *app's* stdout,
not the agent/watchdog process output, and there is no remote filesystem or Task Scheduler access. The
self-update is effectively a black box exactly when it fails.

## What Changes

- **Add an append-only `agent-update.log`** under the install base (`<base>/agent/agent-update.log`)
  that records the self-update lifecycle with timestamps:
  - `SelfUpdater.apply` — verify (size/sha ok or mismatch), extract `versions/<ver>`, `switchTo`
    (new + previous), marker written; and any thrown error.
  - `productionRestart` — resolved previous version, watchdog exe path and whether it exists, spawn
    result.
  - `superviseUpdate` — marker present at entry, `schtasks /run /tn <task>` exit code, each health
    poll (marker present/absent), and the outcome (committed vs reverted-to-previous + reason/deadline).
  - Agent healthy start (`Main.runAgent`) — `detectInstallBase()` result, the running version, the
    listening port, and whether the marker was cleared.
- All writes are **best-effort** (never throw, never block startup) and **no-op when the install base
  is unknown** (dev / manual runs).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

_None — `skip_specs: true`._ Diagnostics only: this adds an operator log, not a behavior or
requirement change to `agent-lifecycle` self-update. The rollback logic is untouched; we are only
making it observable so the next roll can be diagnosed.

## Impact

- **Code:** `wdb-agent` only — a small `AgentInstallLayout.log(event)` helper plus log calls in
  `SelfUpdater`, `AgentSelfRestart` (`productionRestart` / `superviseUpdate`), and `Main.runAgent`.
- **Version:** bump `wdbAgentVersion` 0.2.9 → 0.2.10 (this build also carries the committed
  binary-screenshot transport). Takes effect only once a 0.2.10 agent runs on a wall.
- **Rollout:** because self-update is the thing under suspicion, the first 0.2.10 agent may need a
  manual/local install; after that the log explains subsequent rolls.
