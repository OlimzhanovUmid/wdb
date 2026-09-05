# Design — IDE-triggered Compose hot-reload

## Context

The wall's hot-reload machinery already exists end-to-end in `wdb-client` and `wdb-agent`: an app started in hot mode hosts a CHR orchestration server, and a class delta written into the hot dir is redefined live. The dev-side delta computation and transport also exist in `wdb-client` — `ClassDiff` and `WdbClient.reload` / `reloadOrRedeploy` — and are exercised by the CLI. The plugin already starts hot mode (`hot-run`) but never pushes a delta. This change is a thin orchestration layer in `wdb-plugin` over those existing client APIs.

## Goals

- One-click Reload from the tool window that pushes a class delta to a machine's hot-running app.
- Push only what changed since the last push (per-machine baseline), not the whole app every time.
- Zero-config for the common case (a detected Compose Desktop module), with an explicit override.

## Non-Goals (this change)

- Auto-triggering reload on *file save / IDE compilation events* (`CompilationStatusListener`). Reload does compile the module itself (D7), but the operator still clicks it.
- A working redeploy fallback on hot-apply failure (future: wire the Gradle rebuild+push).
- Non-`kotlin/main` source-set layouts (KMP `kotlin/jvm/main`) — handled only via the manual override field.

## Decisions

### D1 — Reuse the client delta/transport unchanged

`service.reload(machines)` calls, per machine:
`ClassDiff.buildPayload(classesDir, baseline[id])` → `(ReloadPayload, newSnapshot)` → `client.reloadOrRedeploy(name, payload, host)` → `ReloadReport`. No changes to `wdb-client`, the protocol, or the agent.

### D2 — Classes dir is an explicit, persisted, prefilled field (option B)

A new `classesDir` on `WdbSettings.State`, surfaced as a third field in the Deploy dialog. It is prefilled from the detected Compose Desktop module and set by "Use Compose Default"; `ComposeTarget` gains `classesDir = <moduleDir>/build/classes/kotlin/main`. Chosen over deriving the path from `jarPath` by string surgery, which breaks on custom jar paths and KMP layouts. The field is editable so a non-standard layout can be fixed by hand.

### D3 — Per-machine baseline, seeded on hot-run

`WdbService` keeps `baseline: MutableMap<machineId, ClassSnapshot>`. `hotRun(m)` seeds `baseline[id] = ClassDiff.snapshot(classesDir)` at launch, so the first reload pushes only edits made since hot-run (assumes deploy + hot-run come from the same build). A successful reload updates `baseline[id]` to the snapshot `buildPayload` returned. If a machine is already hot but was never hot-run from the plugin, its baseline is empty and the first reload pushes all classes (safe — the agent redefines them); subsequent reloads are incremental.

### D4 — No redeploy fallback yet (redeploy = null)

`reloadOrRedeploy(..., redeploy = null)`, so a `FAILED` hot-apply is reported as failed with a "run Deploy" hint rather than silently rebuilding. Reason: the plugin's deploy is an async, fire-and-forget Gradle run (`runGradleThenDeploy` + `TaskCallback`), awkward to express as the `suspend () -> PushResult` the fallback wants. Deferred to a follow-up.

### D5 — Manual trigger, fan-out like the other actions

A `Reload` action in `ActionRow` (per-row `single`, and the all-machines row), mirroring run/stop/etc.: each machine handled independently, per-machine notification, no abort-on-one-failure. Runs off-EDT on the service coroutine scope. Empty delta → "nothing to reload", no send.

### D6 — Reporting

Map `ReloadReport` to notifications: `Applied` → info "reloaded N classes"; `Rejected` → warning "app not in hot mode"; `Failed` → error "hot-apply failed — run Deploy"; (`Redeployed` unreachable while redeploy = null).

### D7 — Reload compiles the module first

`reload` runs `:<mod>:classes` (module derived from the deploy task by stripping the leaf task name) via `runGradleTask` and only pushes the delta on success. Without this the operator's edit never reaches `classesDir`, so the delta is empty (or, with an empty baseline, re-pushes identical bytecode) and the app does not change — the "reloaded N classes but nothing happened" trap. The Gradle build tool window shows the compile's own progress; the delta push itself is small and needs no extra progress UI.

## Risks

- **Stale baseline** if deploy and hot-run came from different builds — first reload may push more (or, rarely, miss) — but every reload is a safe redefine, so it self-corrects on the next push.
- **Class freshness**: handled — Reload compiles the module before pushing (D7).
- **Layout assumption**: `kotlin/main` is the default; other layouts rely on the manual `classesDir` override.

## Migration

None — additive. New settings field defaults empty; existing deploy configs are unaffected and simply have no classesDir until reconfigured or "Use Compose Default" is clicked.
