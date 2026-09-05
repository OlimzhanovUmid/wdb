## 1. Protocol + agent

- [x] 1.1 `wdb-protocol`: add `@SerialName("bring-to-front") data object BringToFrontRequest : ControlRequest`.
- [x] 1.2 `wdb-agent` `win/BringToFront.kt`: `bringToFront(pid: Long): Boolean` (JNA User32) — EnumWindows → visible titled top-level window with matching PID; restore if minimized; raise via HWND_TOPMOST→NOTOPMOST toggle + BringWindowToTop + best-effort SetForegroundWindow; false when no window / non-Windows / JNA error.
- [x] 1.3 `Supervisor.runningPid(): Long?` (lock-guarded `running?.pid`); `AgentServer.dispatchControl` handles `BringToFrontRequest` (no app → error; raised → OkResponse; no window → error).
- [x] 1.4 Bump `wdbAgentVersion` 0.2.14 → 0.2.15.

## 2. Client + surfaces

- [x] 2.1 `WdbClient.bringToFront(target, host) = expectOk(target, host, BringToFrontRequest)`.
- [x] 2.2 Plugin: `WdbService.bringToFront(sel)`; `WallUi.ActionRow` per-machine action (enabled when `single.appState == "RUNNING"`, a raise/forward AllIcons icon) → `service.bringToFront(listOf(single))`.
- [x] 2.3 `wdb-mcp`: `toolBringToFront(client, machine, host)` + a `bring_to_front` tool `{machine}` resolving host via the cache; extract-and-inject like the other tools; report outcome.

## 3. Verify

- [x] 3.1 `:wdb-protocol:build :wdb-agent:build :wdb-client:build :wdb-mcp:build :wdb-plugin:compileKotlin` green; a `wdb-client` smoke test (FakeAgent OK) round-trips `bringToFront`.
- [x] 3.2 Build agent 0.2.15, roll to the wall; live: with the app behind another window, invoke bring-to-front (plugin action or `bring_to_front` MCP tool / a CLI) → the app window comes to the top on the wall; the action is disabled/absent when the app is stopped.
