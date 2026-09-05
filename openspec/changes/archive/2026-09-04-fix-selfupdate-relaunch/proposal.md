## Why

Agent self-update reverted intermittently: `agent-update` applied cleanly (extract + switch + marker)
but the new version never came up, so after 60s the watchdog rolled back. The new `agent-update.log`
pinned it:

```
schtasks run exit=0 (task=wdb-agent)
revert -> 0.2.10 (new 0.2.11 never cleared marker within 60s)   # no boot line at all
```
vs an identical retry:
```
schtasks run exit=0 (task=wdb-agent)
[0.2.11] boot … marker cleared (update committed)
committed 0.2.11 after 1s
```

Root cause: the watchdog relaunches the new version with **`schtasks /run /tn wdb-agent`**, but that is
a **no-op when Task Scheduler still considers the task's previous instance "running"** (single-instance
policy). The old agent was launched *by* that logon task and had just `exitProcess`'d; if Task
Scheduler hasn't yet marked the task idle, `/run` returns exit 0 and starts **nothing** → the new
version never boots → the marker is never cleared → revert. It's a timing race, so it "sometimes works".

## What Changes

- **The watchdog relaunches the agent directly**, not via Task Scheduler: `ProcessBuilder` on the
  versioned launcher stub `AgentInstallLayout.launchCmd` (`cmd /c <base>\agent\launch.cmd`, which reads
  `current-version` and runs `versions/<v>/wdb-agent.exe run --name …`), started detached (no
  `waitFor`). This is not subject to Task Scheduler's single-instance tracking; the existing
  single-instance `agent.lock` serializes the handoff with the outgoing process.
- Applied to **both** relaunch points in `superviseUpdate` — the initial launch of the new version and
  the post-revert launch of the previous version.
- **Fallback:** if `launch.cmd` is missing (older installs), fall back to `schtasks /run` as today.
- The **logon autostart task is unchanged** — it still starts the agent at boot/logon.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

_None — `skip_specs: true`._ Bug fix: the `agent-lifecycle` self-update already requires the update to
commit when the new agent is healthy and to roll back otherwise. This makes the implementation honor
that reliably; no requirement text changes.

## Impact

- **Code:** `wdb-agent` `AgentSelfRestart.superviseUpdate` only (relaunch mechanism) + `wdbAgentVersion`
  bump to 0.2.12.
- **Rollout note:** the fix runs in the **watchdog**, which is spawned from the *previous* version. So
  it takes effect for updates made **from** a fixed (0.2.12+) agent; the first roll onto 0.2.12 still
  uses the old racy watchdog (retry until it commits, or manual install). From then on, rolls are
  reliable.
- No protocol/client changes.
