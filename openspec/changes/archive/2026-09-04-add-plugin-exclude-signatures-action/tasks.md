## 1. PSI edit helper

- [x] 1.1 Add a helper (e.g. `GradleSignatureExclude`) in `wdb-plugin`: `resolveBuildFile(project, gradleTask): Path?` — `modulePath = gradleTask.substringBeforeLast(':')` → dir (strip leading `:`, `:`→`/`, empty = root) → `build.gradle.kts`; null if absent or only Groovy `build.gradle` exists.
- [x] 1.2 `addExclude(project, gradleTask)`: under `WriteCommandAction` on the `KtFile`, find the task's config block (`tasks.named("<task>") { }` / `tasks.jar { }` / bare `<task> { }` with a trailing lambda); if present and no existing `exclude(...META-INF/*.SF...)`, add the exclude statement to the block; else append `tasks.named("<task>") { exclude("META-INF/*.SF","META-INF/*.RSA","META-INF/*.DSA","META-INF/*.EC") }`. Reformat, commit, open file at the edit. Return an outcome (inserted / already-present / fallback).
- [x] 1.3 Idempotency + fallback: if the exclude is already present → no-op outcome; if the file/task can't be resolved or parsed, or it's Groovy → open the build script (if any) + copy the snippet to the clipboard; never modify on uncertainty.

## 2. Notification action

- [x] 2.1 In `WdbService.pushJar`, offer the action once per push: on the first strip `onNotice`, raise a `"wdb"` notification carrying `NotificationAction` "Exclude signatures in build.gradle" (capturing `settings.gradleTask`); later per-machine strip notices stay plain info. Non-strip notices unchanged.
- [x] 2.2 The action calls `GradleSignatureExclude.addExclude(...)` on the EDT and notifies the outcome (inserted / already excluded / copied to clipboard).

## 3. Verify

- [x] 3.1 `:wdb-plugin:compileKotlin` + `:wdb-plugin:buildPlugin` green.
- [x] 3.2 `runIde` live: deploy a signed uber jar (a third-party app) → strip notice appears with the action → invoke it → `desktopApp/build.gradle.kts` gains `exclude(...)` in/for `packageUberJarForCurrentOS`, file opens at the edit; re-invoke → "already excluded"; a subsequent push reports no strip. Check the fallback by pointing the deploy task at a Groovy-build module (or a missing file) → snippet copied to clipboard, no file change.
