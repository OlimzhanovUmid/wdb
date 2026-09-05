## Context

See proposal.md — Why. This refines a single behaviour from the archived `bootstrap-windows-debug-bridge` change: `Supervisor` currently calls `freeLoopbackPort()` on every launch (design D5/D23), so the JDWP port changes each run. `MachineStatus.jdwpPort` already carries the port; `AgentConfig`/`AgentPaths` already persist per-machine settings (machine id, name, desired state). Debugging goes through the existing tunnel unchanged.

## Goals / Non-Goals

**Goals:**
- A stable JDWP port across app restarts so an open tunnel and IDE run-config keep working.
- Configurable per machine; a safe default when unset.
- Never fail to launch because the fixed port is taken — fall back and surface it.

**Non-Goals:**
- No change to the tunnel transport, discovery, or deploy.
- No multi-debugger / re-attach-after-detach handling (JDWP `server=y` still accepts one connection per launch).
- No IntelliJ plugin (separate future change).

## Decisions

### D1 — Default fixed JDWP port, overridable and persisted per machine
Add a JDWP port to `AgentConfig` (default e.g. `5005`, the conventional JVM debug port), settable via `wdb-agent install --jdwp-port <n>` / `run --jdwp-port <n>` and persisted in the agent data dir like the machine name. `Supervisor` uses it instead of always allocating a free port. *Alternative:* a fixed port hard-coded with no override — rejected; operators may need to avoid a conflict or run more than one agent context.

### D2 — Ephemeral fallback on bind conflict, surfaced in status
Before launching, the agent checks whether the configured port is bindable on loopback; if not, it allocates an ephemeral port (the current `freeLoopbackPort()` behaviour) and sets a `jdwpPortIsFallback` (or similar) flag in `MachineStatus`. This keeps launch resilient and makes the degraded state visible so `debug` (which reads the port from status) still connects. *Alternative:* fail the launch on conflict — rejected; a demo screen must not go dark because a debug port is busy.

### D3 — `MachineStatus` gains an additive field; no protocol major bump
Add an optional boolean (default false) to `MachineStatus` for the fallback flag. It is additive JSON tolerated by the existing `ignoreUnknownKeys` decoder, so an older client still parses status; the protocol major version is unchanged.

## Risks / Trade-offs

- **Fixed port collides with another process on the box** → ephemeral fallback (D2); the operator sees the fallback flag in `status` and can pick a free `--jdwp-port`.
- **A stale tunnel still points at an old ephemeral port after a prior fallback run** → once the fixed port is free again, the next launch returns to it; `status` always reports the truth, and reopening `debug` re-reads it.
- **Default 5005 is a well-known port** → only bound on loopback and only reachable through a forward (unchanged from the existing loopback-only guarantee), so no new LAN exposure.

## Open Questions

- Whether to also expose the fixed port in discovery answers (currently only in `status`) — deferred; `debug` already resolves it via `status`.
