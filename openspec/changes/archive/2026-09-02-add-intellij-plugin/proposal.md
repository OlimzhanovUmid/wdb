## Why

Everything wdb does — discover machines, deploy, run, stream logs, attach a debugger, hot-reload — currently lives behind the `wdb` CLI and manual IDE steps (hand-configuring a "Remote JVM Debug" run configuration to attach). The strategic goal from day one was full IDE integration: drive the demo wall from inside IntelliJ IDEA, one click, no terminal. This change delivers a v1 IntelliJ plugin with a device-manager-style tool window whose UI is written in Compose.

## What Changes

- New **`wdb-plugin`** module: an IntelliJ IDEA plugin (IntelliJ Platform Gradle Plugin 2.x, JBR, target platform 2025.1 / 251.x) that embeds `wdb-client` and exposes its operations in the IDE.
- **Tool window "wdb"** with a **Compose UI (via the platform's bundled Jewel)**: a live list of discovered machines showing name, address, app state, hot-mode, and agent version, themed to match the IDE.
- **Per-machine actions** from the tool window: run, hot-run, stop, restart, rollback.
- **Live log streaming** into a tool-window pane for a selected machine.
- **One-click debug attach**: the plugin opens the JDWP tunnel to the machine and starts a real Remote JVM Debug session programmatically (no user-authored run configuration).
- **Deploy from a Gradle task**: the operator configures a Gradle task per project; the plugin runs it, resolves the built jar, and pushes it to the selected machine(s).
- Discovery is **manual + on-open** (a Refresh action and an initial discovery when the tool window opens), not background polling.

## Capabilities

### New Capabilities
- `ide-integration`: an IDE tool window that lists discovered wall machines and drives wdb operations (deploy from a Gradle task, run/hot-run/stop/restart/rollback, stream logs, one-click debugger attach) from inside IntelliJ IDEA.

### Modified Capabilities
<!-- None. The plugin is a new client surface over existing wdb-client operations; it changes no existing capability's requirements. Discovery, deployment, supervision, log-streaming, port-tunnel and hot-reload behavior are unchanged and simply driven from the IDE. -->

## Impact

- **New module `wdb-plugin`**: IntelliJ Platform Gradle Plugin 2.x + Compose Multiplatform + Compose-compiler plugins; depends on `wdb-client` (which is Compose-free and net-dependency-free, so no clash with the platform's bundled Compose) and on platform `bundledModule`s (Jewel foundation/ui/ide-laf-bridge, compose.foundation.desktop, skiko).
- **Platform pin**: `sinceBuild 251` (2025.1). Compose/Jewel versions are taken from the platform (never bundled by us). Requires the JetBrains Runtime. Android Studio is out of scope for v1 (its own bundled Compose/Jewel would require class shadowing).
- **wdb-client**: consumed as-is. May need small additions if the plugin needs richer status (e.g. a stable machine handle), but no requirement-level change is expected.
- **Debugger**: uses `RemoteConfigurationType` / `RemoteConfiguration` + `ProgramRunnerUtil.executeConfiguration` under the Debug executor.
- **Non-goals (v1)**: hot-reload button / hot-reload-on-build, agent install/update from the IDE, Android Studio support, plugin-marketplace distribution. The `ide-integration` capability is shaped so these extend it later.
