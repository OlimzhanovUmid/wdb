## Why

Today the agent enables JDWP on a fresh **ephemeral** port on every app launch (see the archived `bootstrap-windows-debug-bridge` design D5/D23 and its open question). This surfaced during the live wall-02 debug run: when the app restarts (e.g. an operator closes the window → auto-restart), the JDWP port changes, so an open `debug` tunnel and the IDE's "Remote JVM Debug" run-config silently go stale ("handshake failed - connection prematurely closed"). For a demo wall where apps restart routinely, a debug session should survive a restart without reconfiguring the IDE.

## What Changes

- The agent launches the app with JDWP on a **fixed, per-machine loopback port** by default, stable across app restarts — so a `debug` tunnel and the IDE run-config stay valid after a restart.
- The fixed port is **configurable** (via `install`/`run`, persisted per machine); a sensible default is used when unset.
- If the fixed port is already in use when launching, the agent **falls back to an ephemeral port** and marks that in `status` (a warning/flag) rather than failing to launch.
- `status` continues to report the actual JDWP port in use (fixed or fallback), so `debug` always tunnels to the right port.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `port-tunnel`: the "App is always debuggable on the agent's loopback" requirement changes from an ephemeral per-launch port to a fixed, configurable per-machine port with an ephemeral fallback surfaced in status.

## Impact

- **Code**: `wdb-agent` — `AgentConfig`/`AgentPaths` gain a persisted JDWP port setting; `Supervisor` uses the configured fixed port instead of always allocating a free one, with ephemeral fallback on bind conflict; `MachineStatus` gains a flag indicating a fallback port is in use; `Install`/`Main` accept a `--jdwp-port` option.
- **Protocol**: `MachineStatus` gains an optional field (backward compatible — additive, tolerated by `ignoreUnknownKeys`); no major-version bump.
- **Docs/UX**: `debug` behaviour is unchanged (it reads the port from status); the win is that a repeat `debug` after a restart lands on the same fixed port.
- **Non-goals**: no change to the tunnel transport, discovery, or deploy; no multi-debugger support; the IntelliJ plugin remains a separate future change.
