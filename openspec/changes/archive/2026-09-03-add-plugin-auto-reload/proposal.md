## Why

Today hot-reload is a manual button: edit code, then click **Reload** to compile and push a class
delta. That breaks the CHR-style "edit and see it" loop the demo wall is meant to show. And when a
hot-apply fails (a change the JVM can't redefine — new class, signature change), the app is left
stale with only an error notification; the operator has to notice and manually re-Deploy.

## What Changes

- **Auto-reload on save**: an opt-in "Auto-reload on save" toggle in the wdb tool window. While on,
  saving a JVM source file in the project debounces (~300ms) and runs the existing Reload flow
  (compile `:<module>:classes`, then push the class delta) to **all currently-hot machines**.
- **Redeploy fallback**: when a hot-apply returns **FAILED** (the delta couldn't be applied), the
  reload automatically falls back to a full redeploy + restart of that machine using the persisted
  Deploy config (the configured jar + its `Main-Class`). A **REJECTED** result (app not in hot mode
  / integrity) still just reports — the app is untouched and the fix is to retry, not to redeploy.
- Overlapping saves are coalesced: a reload already in flight is not stacked; the latest pending
  edit triggers one follow-up run.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: adds requirements for auto-reload-on-save (debounced, all hot machines, opt-in
  toggle) and for an automatic redeploy+restart fallback on a failed hot-apply.

## Impact

- **Code:** `wdb-plugin` only. `WdbService.reload`/`pushReload` gain a redeploy fallback closure
  (reusing `resolveJar` + `readMainClass` from `GradleDeploy`); a new document-save listener +
  debounce drives auto-reload; `WdbSettings.State` gains an `autoReloadOnSave` flag; the tool window
  gains the toggle. No protocol, agent, or client changes — `client.reloadOrRedeploy` already takes
  a `redeploy` lambda.
- **Runtime:** unchanged wire protocol; agents need no update. Auto-reload only fires when the toggle
  is on and at least one machine is hot.
