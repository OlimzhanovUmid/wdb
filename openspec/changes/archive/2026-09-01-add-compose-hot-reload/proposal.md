## Why

Iterating on a Compose UI across the demo wall currently means recompile → full redeploy (whole fat jar) → JVM restart, which drops window state and takes seconds per edit. JetBrains Compose Hot Reload (CHR) can swap changed classes into a live JVM and recompose without restart — but its design assumes the compiler and the running app share one filesystem. wdb splits those across machines. This change bridges that gap so an operator can edit on a dev machine and see the change live on a remote wall machine in well under a second, without losing the running window.

## What Changes

- Add a **hot-reload run mode** for the agent: launch the current deployment on the bundled JetBrains Runtime with CHR's `hot-reload-agent` (`-javaagent`), `-XX:+AllowEnhancedClassRedefinition`, an orchestration server the agent hosts on loopback, and a per-run **hot-classpath directory**. Normal (non-hot) runs are unchanged — apps that don't opt into CHR are never forced to pay for it.
- Add a **`RELOAD` wire stream**: the client pushes changed `.class` files (relative path + bytes + change type: Modified / Added / Removed) to the agent. Reuses the existing framed-JSON protocol; bytes travel the wire (CHR's own `ReloadClassesRequest` carries only paths, so cross-machine needs a bytes-carrying transport).
- On receiving a reload push, the agent writes the bytes into the hot-classpath dir at the reproduced paths and emits a CHR `ReloadClassesRequest` into its orchestration server → the app's `hot-reload-agent` runs a DCEVM `redefineClasses` and Compose recomposes. The agent reports a per-push reload result (applied / failed) back to the client.
- Add a **`wdb reload` CLI command**: watches a dev-side Gradle build-output dir, diffs against the last-pushed snapshot, and pushes the changed classes to one machine or (fan-out) all discovered machines. Same per-machine result reporting as `push`.
- **Fallback**: if a redefine fails (structural change past DCEVM's limits, or global-state edit), the agent reports the failure and the client falls back to a full redeploy + restart of that machine, so a bad reload never leaves the app wedged.
- Adopt the CHR Gradle plugin + a `@Composable` dev entry point in **`wdb-dummy-app`** so hot-reload has a live test fixture on the wall.

## Capabilities

### New Capabilities
- `compose-hot-reload`: run a deployment in hot-reload mode, push changed classes from a dev machine to a live remote app, redefine + recompose without restart, fan out to many machines, and fall back to full redeploy on failure.

### Modified Capabilities
<!-- None. Existing process-supervision requirements (keep-alive, JDWP, job object, display-awake) still apply to hot runs unchanged; the hot launch mode and its restart fallback are described wholly by the new capability. -->

## Impact

- **wdb-protocol**: new `StreamKind.RELOAD`; new messages for the reload push (changed-class batch with per-file change type) and the reload result.
- **wdb-client**: `sendReload(...)` op; `WdbClient.reload(...)`; class-diff/snapshot helper for the watch loop.
- **wdb-cli**: new `reload` command (single + `--all` fan-out, `--watch`).
- **wdb-agent**: new hot-run launch path in `Supervisor` (extra jvmArgs, hot-classpath dir); hosts a CHR `OrchestrationServer` on loopback; new `RELOAD` handler in `AgentServer`; bundles `hot-reload-agent`, `hot-reload-runtime-jvm`, `hot-reload-orchestration`.
- **Dependencies**: CHR artifacts `org.jetbrains.compose.hot-reload`. Requires the app be built with Kotlin ≥ 2.1.20 / Compose ≥ 1.8.2 and depend on `hot-reload-runtime-jvm`. Requires the bundled JBR to be a full JetBrains Runtime with DCEVM (verified — `-XX:+AllowEnhancedClassRedefinition` accepted).
- **Toolchain (added scope, discovered during apply)**: repo was on Kotlin 2.0.21; CHR forces a **repo-wide Kotlin bump to ≥ 2.1.20** (shared version catalog) so the agent can compile against `hot-reload-orchestration`. Adds the Compose Multiplatform + Compose-compiler + CHR Gradle plugins to the catalog.
- **wdb-dummy-app**: was a plain Kotlin/JVM uber-jar with no Compose UI; **converted to a minimal Compose Desktop app** so it can host CHR (added CHR Gradle plugin + `@Composable` dev entry point). Opt-in fixture only.
- **Non-goal (this change)**: IntelliJ-plugin-driven reload (backlog #4) — the `RELOAD` stream is designed so the plugin can reuse it later.
