# Design — auto-reload on save + redeploy fallback

## Context

`WdbService.reload(machines)` already does the whole hot-reload flow: compile `:<module>:classes`
(via `runGradleTask`), then `pushReload` diffs the configured `classesDir` against a per-machine
`baseline` and calls `client.reloadOrRedeploy(name, payload, host, redeploy = null)`. Two gaps: it
is only ever called from the manual **Reload** button, and it passes `redeploy = null` so a FAILED
hot-apply just notifies. `MachineUi` already carries `hot: Boolean`; `WdbSettings.State` persists
`gradleTask`, `jarPath`, `classesDir`; `GradleDeploy` already has `resolveJar` + `readMainClass`
(the exact jar/Main-Class resolution a redeploy needs). So this change is wiring, not new machinery.

## Decisions

### D1 — Trigger: document-save listener + debounce

Subscribe to the platform's `FileDocumentManagerListener` (message-bus topic
`AppTopics.FILE_DOCUMENT_SYNC`) on a project-scoped connection created in `WdbService` init.
`beforeDocumentSaving(document)` fires on both explicit and auto saves; resolve the `VirtualFile`,
and only proceed for a file that is under the project's content roots and is a JVM source
(`.kt`/`.java`). This is cheaper and more direct than watching the classes output dir, and does not
couple us to the IDE's own build configuration.

Debounce: keep a single `debounceJob: Job?` on the service scope. Each qualifying save cancels the
prior job and launches a new one that `delay(300)`s then requests a reload. A burst of saves thus
produces one reload.

### D2 — Scope: all hot machines

The auto-trigger targets `machines.value.filter { it.hot }`. If that list is empty (or `classesDir`
is not configured, or the toggle is off), the save is a no-op — no compile, no notification. The
manual Reload button keeps its current behavior (its explicit machine list).

### D3 — Single-flight coalescing

Compiling + pushing takes seconds; saves can arrive during it. Guard with a `reloadInFlight` flag
and a `reloadPending` flag: if a trigger arrives while a reload runs, set `reloadPending`; when the
running reload finishes, if `reloadPending` was set, run exactly one more. This bounds the work to
"at most one queued follow-up" regardless of how many saves landed mid-flight, satisfying the
coalescing requirement without a growing queue.

### D4 — Redeploy fallback

`pushReload` passes a real `redeploy` closure to `reloadOrRedeploy` instead of `null`:

```
redeploy = {
    val jar = resolveJar(settings.jarPath)
    val main = jar?.let(::readMainClass)
    if (jar != null && !main.isNullOrBlank()) client.push(m.name, jar, main, host = m.address)
    else PushResult(ok = false, error = ProtocolError(INTERNAL, "no deploy config for fallback"))
}
```

`resolveJar`/`readMainClass` become `internal` in `GradleDeploy` so the service can reuse them.
`reloadOrRedeploy` only invokes `redeploy` on `ReloadOutcome.FAILED`; `REJECTED` (not hot /
integrity) never triggers it — so the existing `Rejected` branch already gives the "report only"
behavior the spec requires, and the `Redeployed` branch reports the fallback. A redeploy restarts
the app in **normal** (non-hot) mode; the operator re-runs Hot Run to resume the hot loop. This is
acceptable for v1 (the failed change is live again); re-hot-running as part of the fallback is a
future refinement noted below. The fallback applies to both manual and auto reload since both go
through `pushReload`.

### D5 — Toggle + persistence

`WdbSettings.State` gains `var autoReloadOnSave: Boolean = false` (persists in `wdb.xml` with the
rest of the deploy config). A checkbox/toggle in the wdb tool window toolbar reflects and edits it,
next to the existing actions. The save listener is always registered but is a cheap early-return
when the flag is off, so toggling needs no listener re-wiring.

## Non-Goals

- Re-hot-running automatically after a redeploy fallback (v1 leaves the app in normal mode).
- Auto-reload scoping to a single machine or per-card toggles (chosen scope is all hot machines).
- Triggering off the IDE's build-finished event or a filesystem watcher (chosen trigger is save).
- Prompting before a redeploy (the fallback is automatic).

## Risks

- **Autosave chattiness** — IntelliJ saves often; mitigated by the 300ms debounce, the JVM-source +
  content-root filter, and single-flight coalescing. An empty delta (`nothing to reload`) is a
  cheap no-op push-side.
- **Compile cost per burst** — `:<module>:classes` is incremental and debounced; worst case is one
  extra compile queued behind an in-flight one.
- **Fallback leaves the app non-hot** — reported clearly; documented as expected until the operator
  re-runs Hot Run. Revisit if it proves annoying in demos.
