## 1. Redeploy fallback (client wiring)

- [x] 1.1 Make `resolveJar` and `readMainClass` `internal` in `GradleDeploy.kt` so the service can reuse the exact jar/Main-Class resolution the manual Deploy uses.
- [x] 1.2 In `WdbService.pushReload`, pass a real `redeploy` closure to `client.reloadOrRedeploy` (resolve `settings.jarPath` → newest jar → `Main-Class` → `client.push(name, jar, main, host)`; return a failed `PushResult` if no deploy config). Keep the `Rejected` branch report-only and the `Redeployed` branch reporting the fallback. Applies to both manual and auto reload.

## 2. Auto-reload trigger

- [x] 2.1 In `WdbService` init, open a project message-bus connection and subscribe a `FileDocumentManagerListener` (`AppTopics.FILE_DOCUMENT_SYNC`). On `beforeDocumentSaving`, resolve the `VirtualFile` and proceed only for a `.kt`/`.java` file under the project's content roots.
- [x] 2.2 Add debounce (~300ms, single `debounceJob` on the service scope) + single-flight coalescing (`reloadInFlight`/`reloadPending`). On fire, if `autoReloadOnSave` is on and `classesDir` is set, reload `machines.value.filter { it.hot }`; no-op when the toggle is off, no machine is hot, or no classes dir is configured.

## 3. Toggle UI + persistence

- [x] 3.1 Add `var autoReloadOnSave: Boolean = false` to `WdbSettings.State`.
- [x] 3.2 Add an "Auto-reload on save" toggle to the wdb tool-window toolbar (`WallUi`) bound to the setting.

## 4. Verify

- [x] 4.1 `:wdb-plugin:compileKotlin` and `:wdb-plugin:buildPlugin` succeed.
- [x] 4.2 `runIde` live on a wall: with a hot app and the toggle on, editing + saving a source auto-reloads it (verified — reload fires on save, including when a new top-level `class` is added, since CHR uses DCEVM-style enhanced hotswap and reloads class-adds as APPLIED); toggling off stops save-driven reloads; a non-hot machine is never auto-reloaded.
  - Redeploy fallback is the `ReloadOutcome.FAILED`-only path; a class-add is APPLIED under CHR so it does NOT (and should not) trigger it. The FAILED→redeploy / REJECTED→no-redeploy / APPLIED→no-redeploy semantics are deterministically covered by `wdb-client` `ReloadTest`; this change only supplies the plugin's `redeploy` closure (resolve configured jar → `client.push`) into that already-tested branch. Forcing a live FAILED with CHR is impractical, so it is not part of the live check.
