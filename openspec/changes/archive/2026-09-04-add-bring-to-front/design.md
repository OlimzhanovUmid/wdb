# Design — bring the app window to the front

## Context

`Supervisor` holds the running app as `running: RunningApp?` with `.pid`. The agent already uses JNA
(`dev.wdb.agent.win.JobObject`, `DisplayAwake`) and depends on `jna` + `jna-platform`, so
`com.sun.jna.platform.win32.User32`/`WinUser`/`WinDef` are available. `AgentServer.dispatchControl` is a
`when` over `ControlRequest`; `WdbClient` funnels control ops through `expectOk`. The walls are Windows.

## Decisions

### D1 — Protocol: `BringToFrontRequest`

`@Serializable @SerialName("bring-to-front") data object BringToFrontRequest : ControlRequest`. Reply is
the existing `OkResponse` / `ErrorResponse`.

### D2 — Agent win helper (`win/BringToFront.kt`)

`fun bringToFront(pid: Long): Boolean` (JNA User32), Windows-guarded:

1. `EnumWindows` → collect top-level windows whose `GetWindowThreadProcessId` == `pid` and that are
   visible (`IsWindowVisible`) with a non-empty title; pick the best candidate (has a title / largest).
   None → return false.
2. Raise it reliably past the foreground lock:
   - `if (IsIconic(hwnd)) ShowWindow(hwnd, SW_RESTORE)`.
   - Toggle top-most to pull it above others without needing foreground rights:
     `SetWindowPos(hwnd, HWND_TOPMOST, 0,0,0,0, SWP_NOMOVE|SWP_NOSIZE|SWP_SHOWWINDOW)` then
     `SetWindowPos(hwnd, HWND_NOTOPMOST, …)`.
   - Then `BringWindowToTop(hwnd)` + `SetForegroundWindow(hwnd)` (best-effort focus).
   The top-most toggle is the reliable part; `SetForegroundWindow` alone is blocked by Windows'
   foreground-lock when the agent isn't the foreground process, so it is a best-effort add-on, not the
   mechanism. Return true.

Non-Windows / JNA failure → return false (caller maps to an error).

### D3 — Supervisor accessor + dispatch

`Supervisor.runningPid(): Long? = lock.withLock { running?.pid }`. In `AgentServer.dispatchControl`:

```
BringToFrontRequest -> {
    val pid = supervisor.runningPid()
    when {
        pid == null -> ErrorResponse(ProtocolError(INTERNAL, "no app running"))
        BringToFront.bringToFront(pid) -> OkResponse
        else -> ErrorResponse(ProtocolError(INTERNAL, "no window found for the app"))
    }
}
```

### D4 — Client + plugin + MCP

- `WdbClient.bringToFront(target, host) = expectOk(target, host, BringToFrontRequest)`.
- Plugin: `WdbService.bringToFront(sel)` (like `run`, `forEach`); `WallUi.ActionRow` adds a per-machine
  action enabled when `single.appState == "RUNNING"`, a raise/forward AllIcons icon (e.g.
  `AllIconsKeys.General.OpenInFullTab` or `AllIconsKeys.Actions.MoveToWindow` — pick a valid bundled
  key), calling `service.bringToFront(listOf(single))`.
- MCP: a `bring_to_front` tool `{machine}` → resolve host via the cache → `client.bringToFront` → text
  outcome.

### D5 — Version + roll

`wdbAgentVersion` bump (0.2.14 → 0.2.15); roll via `agent-update` (self-update is reliable from 0.2.14+).

## Non-Goals

- Non-Windows walls (Windows-only; other agents return the "no window" error).
- Choosing among multiple windows beyond a simple "main visible titled window" heuristic.
- Moving the window to a specific monitor / resizing.

## Risks

- **Foreground lock** — `SetForegroundWindow` may not focus if the agent isn't foreground; mitigated by
  the top-most toggle which raises the window regardless. Acceptable: the window comes to the top even if
  keyboard focus doesn't follow.
- **Window discovery** — a Compose app is one visible top-level window per PID on the wall; the titled-
  visible heuristic picks it. A splash/secondary window edge case falls back to the first match.
