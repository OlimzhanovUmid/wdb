## Why

The client strips stale JAR signature files on every push and reports it, suggesting the operator add
a build-time `exclude(...)`. But a signed uber jar is ~150 MB, so each push pays a full temp rewrite —
and hand-editing the right Gradle task is exactly the friction the notice asks the user to overcome.
In the IDE we can do it for them: one click that inserts the exclude into the app's build so future
pushes skip the rewrite.

## What Changes

- **Notification action after a strip:** when a plugin deploy strips signatures, the plugin raises a
  notification with an action **"Exclude signatures in build.gradle"** (once per push, not per machine).
- **Targeted PSI insert:** the action locates the build task from the configured deploy task
  (`WdbSettings.gradleTask`, e.g. `:desktopApp:packageUberJarForCurrentOS`, `:app:shadowJar`,
  `:mod:jar`) → that module's `build.gradle.kts`, and inserts
  `exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")` **into that task's
  block** using IntelliJ Kotlin PSI under an undoable `WriteCommandAction`:
  - an existing block for the task (`tasks.jar { }`, `tasks.named("<task>") { }`, bare `<task> { }`)
    gets the `exclude` added inside it;
  - otherwise a new `tasks.named("<task>") { exclude(...) }` block is appended (Compose's
    `packageUberJarForCurrentOS` is not declared by the user).
  - The file is reformatted and opened at the edit.
- **Safe fallbacks (never corrupt the build):** if the module/task/file can't be located or parsed, or
  the build is Groovy (`build.gradle`, not `.kts`), the action instead opens the build file and copies
  the exclude snippet to the clipboard with a notification to paste it. Re-running is idempotent — an
  existing exclude is detected and the action no-ops.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: adds an action, offered after a deploy strips stale signature files, that inserts
  the signature `exclude` into the app's Gradle build (with a safe clipboard fallback).

## Impact

- **Code:** `wdb-plugin` only — a `build.gradle.kts` PSI edit helper + a `NotificationAction`, wired
  into the existing deploy `onNotice` (already carries the strip message).
- **No protocol/agent/client changes** — stripping still happens client-side; this only removes the
  need for it by fixing the source build.
