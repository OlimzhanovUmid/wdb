## 1. Settings + confirm

- [x] 1.1 Add `var hotRunRestartConfirmed: Boolean = false` to `WdbSettings.State`.
- [x] 1.2 `WdbService.confirmHotRestart(m): Boolean` (suspend, on `Dispatchers.EDT`): return true immediately if `hotRunRestartConfirmed`; else `MessageDialogBuilder.yesNo(…)` with a `DoNotAskOption.Adapter` whose `rememberChoice(isSelected, exitCode)` sets the flag on Yes+checked; return the ask result.

## 2. Hot-run restart

- [x] 2.1 Rewrite `WdbService.hotRun` as a dedicated `cs.launch` loop: for a RUNNING machine, `confirmHotRestart` → on cancel notify "hot-run cancelled" and skip; on confirm `client.stop` then `client.hotRun` + `seedBaseline`; a non-running machine hot-runs directly. Notify per outcome; `refresh()` at the end.

## 3. State-aware + state-gated toolbar

- [x] 3.1 In `WallUi.ActionRow`, compute `running` (single → `appState == "RUNNING"`; all-row → all targets running) and render the Run control as Restart (`AllIconsKeys.Actions.Restart`, `service.restart`) when running, else Run (`Execute`, `service.run`). **Remove the standalone Restart icon.**
- [x] 3.2 Add `val hasPrevious: Boolean` to `MachineUi`; populate in `refresh()` from `status.previousSha != null`.
- [x] 3.3 Gate the remaining controls by state (per-machine → that machine; all-row → `any` target): Reload + Mirror enabled when `hot`; Stop + Debug enabled when `appState == "RUNNING"`; Rollback enabled when `hasPrevious`. Deploy/Run/Hot-run stay enabled on non-empty targets.

## 4. Verify

- [x] 4.1 `:wdb-plugin:compileKotlin` + `:wdb-plugin:buildPlugin` green.
- [x] 4.2 `runIde` live: hot-run a RUNNING machine → confirm dialog with "don't ask again" → confirm → app comes back in hot mode (`status hot:true`); cancel → untouched; after remembering, later hot-run restarts with no prompt; Run shows Restart while running, Run when stopped; Reload/Mirror greyed when not hot, Stop/Debug greyed when stopped, Rollback greyed with no previous deployment; standalone Restart icon gone.
