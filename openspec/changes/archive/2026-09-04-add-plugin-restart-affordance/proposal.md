## Why

Hot-run on a machine whose app is already running is a silent no-op — the agent's `launch()` does
nothing when the app is RUNNING, so the app never actually enters Compose hot-reload mode and the
operator is left wondering why "Hot-run" did nothing. Separately, the Run action looks the same whether
the app is stopped or already running, giving no hint that pressing it (when running) would need a
restart.

## What Changes

- **Hot-run restarts a running app, with confirmation:** for each target that is RUNNING, Hot-run asks
  "App on <machine> is running — stop it and start in Compose hot-reload mode?" with a **remember-my-
  choice** option. On confirm it stops the current run and hot-runs (a real hot launch); on cancel it
  skips that machine. Targets that are not running hot-run directly, no dialog. Once "don't ask again"
  is chosen, later hot-runs restart without prompting.
- **State-aware Run action:** when the target machine is already RUNNING the Run toolbar button shows a
  **Restart** icon + "Restart" tooltip and acts as a restart; when stopped it shows the Run icon +
  "Run" and launches. Per-machine rows use that machine's state; the all-machines row uses "all targets
  running". The now-redundant **standalone Restart icon is removed** (Run covers it when running).
- **State-gated toolbar:** actions that can't apply to the current state are disabled (greyed) instead
  of silently no-op'ing or erroring after the click — **Reload** and **Mirror** need hot mode, **Stop**
  and **Debug** need a running app, **Rollback** needs a previous deployment. Per-machine controls use
  that machine's state; all-machines controls enable when any target qualifies.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: hot-run now restarts a running app after a remembered confirmation, and the Run
  action reflects whether the app is already running.

## Impact

- **Code:** `wdb-plugin` only — `WdbService.hotRun` (+ a confirm helper), a persisted
  `WdbSettings.hotRunRestartConfirmed` flag, and the `WallUi` `ActionRow` Run-icon logic.
- **No protocol/agent/client changes** — `stop`, `hotRun`, and `restart` already exist on the client.
