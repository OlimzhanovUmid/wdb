## 1. Classes-dir configuration

- [x] 1.1 Add `classesDir: String = ""` to `WdbSettings.State`.
- [x] 1.2 Extend `ComposeTarget` with `classesDir = <moduleDir>/build/classes/kotlin/main`, populated in `listComposeDesktopTargets`.
- [x] 1.3 Add a third "Classes dir" field to `DeployDialog` (plain `TextFieldWithBrowseButton` folder picker, `columns = 40`), persisted via `configureDeploy`; prefill it from the detected `ComposeTarget` when unconfigured and from "Use Compose Default" (`applyTarget`).

## 2. Reload flow in the service

- [x] 2.1 Add `baseline: MutableMap<String, ClassSnapshot>` to `WdbService`; on `hotRun` success seed `baseline[m.id] = ClassDiff.snapshot(classesDir)` (skip if classesDir blank/missing).
- [x] 2.2 Add `reload(machines: List<MachineUi>)`: for each machine, resolve `classesDir` from settings; if blank/missing → per-machine warning and skip. Build `ClassDiff.buildPayload(classesDir, baseline[id] ?: emptyMap())`; empty entries → "nothing to reload"; else `client.reloadOrRedeploy(m.name, payload, host = m.address)` with `redeploy = null`.
- [x] 2.3 Map `ReloadReport` to notifications (Applied → "reloaded N classes"; Rejected → warning "app not in hot mode"; Failed → error "hot-apply failed — run Deploy") and, on Applied, update `baseline[id]` to the fresh snapshot. Run off-EDT on `cs`, independent per machine (no abort-on-one-failure), refresh after.

- [x] 2.4 **Build before push**: `reload` first compiles the module (`runGradleTask(project, ":<mod>:classes")`, module derived from the deploy task) and only pushes the delta on compile success — otherwise the pushed classes are unchanged and the reload is a no-op. Compile failure reports and pushes nothing.

## 3. Reload action in the UI

- [x] 3.1 Add a `Reload` `ActionIcon` to `ActionRow` (per-row `single` + the all-machines row) calling `service.reload(targets)`, with a suitable bundled icon.

## 4. Verify

- [x] 4.1 `compileKotlin` + `runIde`: with a machine hot-running, edit a composable, Build, click Reload → the app updates live and the notification reports it applied; a second Reload with no change reports "nothing to reload".
- [x] 4.2 In `runIde`, Reload on a non-hot machine reports rejected (app untouched); the Classes dir field prefills from the Compose module and persists.
