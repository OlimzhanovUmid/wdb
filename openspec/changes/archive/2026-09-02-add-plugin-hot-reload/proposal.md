## Why

The IntelliJ plugin can start an app in Compose hot-reload mode (`hot-run`), but it has no way to *push* code changes into that running app — the delta-push half of hot-reload lives only in the CLI. So from the IDE, editing a composable and seeing it update live still requires dropping to the terminal. This closes that gap: reload from inside the IDE, the same one-click flow the rest of the plugin offers.

## What Changes

- A manual **Reload** action in the tool window (per machine row and an all-machines variant) that snapshots the compose module's compiled classes, computes the delta since the last push, and sends it to each machine's hot-running app via the existing client reload.
- A per-machine **baseline** snapshot, seeded when the app is hot-run from the plugin, so a reload pushes only what changed since launch.
- A new persisted **Classes dir** field in the Deploy dialog, prefilled from the detected Compose Desktop module and filled by the existing "Use Compose Default" button.
- Reload outcomes reported per machine (applied / rejected-not-hot / failed). No automatic redeploy fallback in this change — on a failed hot-apply the plugin tells the operator to Deploy.

Reload compiles the module (`:<mod>:classes`) before pushing, so an edit actually takes effect. Out of scope (future work): auto-triggering reload on file-save/compilation events, wiring the full Gradle rebuild+push as the failure fallback, and non-`kotlin/main` source-set layouts.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: adds a requirement that the plugin can trigger a Compose hot-reload push to a machine's hot-running app from the IDE.

## Impact

- **Code:** `wdb-plugin` only — `WdbService` (reload flow + baseline + seed on hot-run), `WallUi` (Reload action), `DeployDialog`/`WdbSettings`/`GradleTasks` (classesDir field + prefill from `ComposeTarget`). Reuses `wdb-client` `ClassDiff` / `reloadOrRedeploy` unchanged.
- **APIs/deps:** no new dependencies; no wire/protocol changes.
- **Spec:** `openspec/specs/ide-integration/spec.md` gains one requirement.
