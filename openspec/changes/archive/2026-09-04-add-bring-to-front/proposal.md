## Why

The app on a wall machine often ends up **behind** other windows — after a redeploy, an RDP session,
or the operator opening something else — so a "run/hot-run/mirror" leaves nothing visible on the wall.
There is no way to raise it from the IDE/MCP without walking to the machine.

## What Changes

- **New "bring to front" control op:** the agent raises the running app's top-level window to the
  foreground on the wall machine — find the window by the running app's PID (JNA User32), restore it if
  minimized, and force it forward (reliably, past Windows' foreground-lock). Returns success, or an
  error when nothing is running / no window is found.
- **Client:** `WdbClient.bringToFront(target, host)`.
- **Plugin:** a per-machine toolbar action ("Bring to front", gated to a RUNNING app) that raises the
  window.
- **MCP:** a `bring_to_front` tool `{machine}`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: adds a per-machine action to bring the running app's window to the foreground.
- `mcp-server`: adds a tool to bring the running app's window to the foreground.

## Impact

- **Code:** `wdb-protocol` (one `BringToFrontRequest`), `wdb-agent` (a `win` helper alongside JobObject
  using JNA User32 + a `Supervisor.runningPid()` accessor + one dispatch branch), `wdb-client`
  (`bringToFront`), `wdb-plugin` (toolbar action), `wdb-mcp` (tool). Agent version bump + roll (self-
  update is now reliable).
- **Platform:** Windows-only (the walls). On a non-Windows agent the op is a no-op error; JNA
  `jna-platform` is already a dependency.
