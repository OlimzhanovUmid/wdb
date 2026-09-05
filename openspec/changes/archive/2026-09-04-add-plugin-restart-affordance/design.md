# Design — hot-run restart confirmation + state-aware Run

## Context

`WdbService.hotRun(sel) = forEach(sel, "running (hot)") { client.hotRun(m); seedBaseline(m) }`. `forEach`
launches on the service coroutine scope, runs the op per machine, and notifies success/failure. The
agent's `HotRunRequest` → `supervisor.launch(hot=…)` no-ops when the app is RUNNING, so hot-run does
nothing for a running app. `client.stop`, `client.hotRun`, `client.restart` all exist. `MachineUi.appState`
is a `String` (`"RUNNING"` etc). The Compose toolbar `WallUi.ActionRow` renders the Run icon as
`AllIconsKeys.Actions.Execute` with `onClick = service.run(targets)`.

## Decisions

### D1 — `hotRun` restarts a running app, per-machine, with confirmation

Replace `hotRun`'s `forEach` with a dedicated loop (so a *skipped* machine notifies nothing):

```
fun hotRun(sel) = cs.launch {
    for (m in sel) {
        val running = m.appState == "RUNNING"
        if (running && !confirmHotRestart(m)) { notify("${m.name}: hot-run cancelled", INFORMATION); continue }
        runCatching {
            if (running) client.stop(m.name, m.address)      // fresh launch → hot engages
            client.hotRun(m.name, m.address); seedBaseline(m)
        }.onSuccess { notify("${m.name}: running (hot)", INFORMATION) }
         .onFailure { notify("${m.name}: hot-run failed — ${it.message}", ERROR) }
    }
    refresh()
}
```

### D2 — `confirmHotRestart` (EDT) with a remembered flag

```
private suspend fun confirmHotRestart(m): Boolean = withContext(Dispatchers.EDT) {
    val s = WdbSettings.get(project).state
    if (s.hotRunRestartConfirmed) return@withContext true          // remembered → auto-yes
    val option = object : DoNotAskOption.Adapter() {
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
            if (isSelected && exitCode == Messages.YES) s.hotRunRestartConfirmed = true
        }
    }
    MessageDialogBuilder.yesNo("Restart in hot-reload mode?",
        "App on ${m.name} is running. Stop it and start in Compose hot-reload mode?")
        .icon(Messages.getQuestionIcon()).doNotAsk(option).ask(project)
}
```

The flag is checked first (not relying on the platform's implicit skip), so "remember" deterministically
skips the dialog and auto-confirms. Checking "don't ask again" together with **Yes** sets the flag; a
remembered **No** is intentionally not offered (the operator can just not press hot-run).

### D3 — `WdbSettings.hotRunRestartConfirmed`

`var hotRunRestartConfirmed: Boolean = false` in `WdbSettings.State` (persists in `wdb.xml`). Resettable
by clearing it (a future settings toggle is out of scope).

### D4 — State-aware Run in `ActionRow`

Compute `running`: `single?.let { it.appState == "RUNNING" } ?: (targets.isNotEmpty() && targets.all { it.appState == "RUNNING" })`.
Render:

```
if (running) ActionIcon("Restart", AllIconsKeys.Actions.Restart, enabled) { service.restart(targets) }
else         ActionIcon("Run",     AllIconsKeys.Actions.Execute, enabled) { service.run(targets) }
```

The standalone **Restart** icon is **removed** — the state-aware Run covers restart when running, so a
separate Restart is redundant.

### D5 — State-gated toolbar (extend `ActionIcon` enabled per action)

Each control's `enabled` (in addition to `targets.isNotEmpty()`) reflects state. For a per-machine row
use `single`'s state; for the all-machines row aggregate with `any` over `targets`:

| Control | Enabled when |
|---|---|
| Deploy / Run·Restart / Hot-run | targets non-empty (as now) |
| Reload, Mirror (mirror is per-machine) | machine `hot` (all-row: `targets.any { it.hot }`) |
| Stop, Debug (debug is per-machine) | machine `appState == "RUNNING"` (all-row: `targets.any { running }`) |
| Rollback | machine `hasPrevious` (all-row: `targets.any { it.hasPrevious }`) |

Disabled controls grey out (Jewel `IconButton(enabled=false)`); the tooltip keeps the action name (a
"— app not in hot mode" suffix on disabled tooltips is a nice-to-have, optional).

### D6 — `MachineUi.hasPrevious`

Add `val hasPrevious: Boolean` to `MachineUi`, populated in `refresh()` from `status.previousSha != null`
(the status payload already carries `previousSha`). Drives the Rollback gate.

## Non-Goals

- Confirming plain Run/Restart (restart is already an explicit action; only hot-run silently no-ops).
- Removing the standalone Restart icon (kept to avoid changing the rest of the toolbar).
- An agent-side change to make `HotRunRequest` restart-when-running (done client-side via stop+hotRun,
  no agent roll).

## Risks

- **Stale `appState`** — the toolbar reads the last-refreshed state; a just-changed app might show the
  wrong Run/Restart affordance until the next auto-refresh. Harmless (both eventually converge; the
  agent no-ops a redundant run).
- **DoNotAskOption API** — `DoNotAskOption.Adapter.rememberChoice(isSelected, exitCode)` is the 2026.1
  form; verify at compile (fallback: `setToBeShown`).
