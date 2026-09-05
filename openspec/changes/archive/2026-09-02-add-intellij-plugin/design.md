## Context

See proposal.md — Why. `wdb-client` is already a coroutine, zero-third-party-net-dependency, Compose-free library exposing `discover / status / run / hotRun / stop / restart / rollback / push / reload / logs(Flow) / openTunnel`. The plugin is a new client surface over it. Compose-in-IDE is now viable: Jewel lives inside the IntelliJ Platform and ships bundled from 2025.1 (251.x), so a plugin renders Compose in a tool window and picks up the IDE theme, without shipping its own Compose runtime.

## Goals / Non-Goals

**Goals:**
- A `wdb-plugin` module targeting IntelliJ IDEA 2025.1+ (251.x), Compose/Jewel UI, driving `wdb-client`.
- One-click debugger attach with no user-authored run configuration.
- Deploy = run a configured Gradle task, push its output jar.
- Keep the plugin's Compose isolated to the platform's bundled modules (no classpath clash).

**Non-Goals:**
- Android Studio support (its own bundled Compose/Jewel would need class shadowing — deferred).
- Hot-reload button / hot-reload-on-build, agent install/update from the IDE, Marketplace distribution.
- Bundling any Compose/Skiko/coroutines version ourselves — the platform dictates them.

## Decisions

**D1 — New `wdb-plugin` module, platform-pinned to 251.x.**
Gradle plugins: `org.jetbrains.intellij.platform` (2.x) + Kotlin + Compose Compiler + Compose Multiplatform. `create("IC", "251.x")`, `sinceBuild 251`. JBR toolchain (Java 21). Declare Jewel + Compose as `bundledModule(...)` (`intellij.platform.jewel.foundation/ui/ideLafBridge`, `intellij.libraries.compose.foundation.desktop`, `intellij.libraries.skiko`) — never as bundled jars. Depends on `project(":wdb-client")`. *Alternative rejected:* external `org.jetbrains.jewel:*` artifacts — only for pre-2025.1 or standalone apps, and they force manual Compose-version matching.

**D2 — Compose UI via `addComposeTab { SwingBridgeTheme { … } }`.**
The `ToolWindowFactory` (implements `DumbAware`) creates the tab with `toolWindow.addComposeTab("wdb") { SwingBridgeTheme { WallUi(...) } }` — `addComposeTab` enables the new Swing compositing for us, and `SwingBridgeTheme` mirrors the live IDE LaF (light/dark + accent) into Compose (satisfies "UI matches the IDE theme"). *Alternative rejected:* standalone `IntUiTheme` — doesn't track the IDE theme.

**D3 — A project-level `@Service` with an injected `CoroutineScope` holds state.**
`@Service(Service.Level.PROJECT) class WdbService(project, cs: CoroutineScope)` exposes a `StateFlow<List<MachineUi>>`. Discovery/actions run on `cs.launch` (off-EDT); the Compose UI collects the flow and renders. The injected scope is cancelled with the project, so no manual `Disposable` for it. This mirrors Android Studio's Device Manager v2 (Flow-of-devices → table on EDT). *Alternative rejected:* a hand-rolled `GlobalScope`/polling loop — leaks and fights the platform lifecycle.

**D4 — Threading: mutate Compose state on the EDT.**
`wdb-client` calls run on `Dispatchers.IO`/`Default`; results are marshalled to Compose `State`/the `StateFlow` such that Compose state is only mutated on the EDT (`withContext(Dispatchers.EDT)` at the boundary). Long actions (deploy, discovery) run under `withBackgroundProgress(project, …)` so they show IDE progress and are cancellable.

**D5 — One-click debug via a transient `RemoteConfiguration`.**
Debug = `openTunnel(machine, remoteJdwpPort)` → get the local port → build a Remote JVM Debug config and execute it under the Debug executor:
```
val type = ConfigurationTypeUtil.findConfigurationType(RemoteConfigurationType::class.java)
val settings = runManager.createConfiguration("wdb: attach <machine>", type.configurationFactories.first())
(settings.configuration as RemoteConfiguration).apply {
    HOST = "localhost"; PORT = localPort.toString(); USE_SOCKET_TRANSPORT = true; SERVER_MODE = false
}
ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
```
The settings are not persisted (not added to `RunManager`), so no clutter. The tunnel is tied to the debug session lifetime — released on session termination (an `ExecutionManager`/`XDebuggerManager` termination listener, or a `Disposable` bound to the session). *Alternatives rejected:* `DebuggerManagerEx.attachVirtualMachine` and `com.intellij.debugger.impl.attach.*` are internal/version-fragile and give no Debug-tool-window integration; `AttachToProcessAction` is for local PID attach, not a host:port tunnel.

**D6 — Deploy is configured in a dialog (task dropdown + jar file-picker), not a settings page.**
A free-text settings page was too fiddly. Instead the Deploy button opens a **`DialogWrapper`** with:
- a **Gradle-task dropdown** populated from the project's *real* tasks via the stable External System API (`ExternalSystemApiUtil.findProjectNode(project, GradleConstants.SYSTEM_ID, basePath)` → `findAll(ProjectKeys.MODULE/TASK)` → `TaskData`), and
- a **jar file-picker** (`TextFieldWithBrowseButton` + `FileChooserDescriptorFactory.createSingleLocalFileDescriptor().withExtensionFilter("jar")`).

Values persist per project in a `PersistentStateComponent` `{ gradleTask, jarPath }`. The dialog opens on the first Deploy (unset) and via a "Configure deploy" gear in the toolbar; once set, Deploy runs directly. On deploy the plugin runs the task (`ExternalSystemUtil.runTask`, build failure aborts), then pushes the **newest `*.jar` in the picked jar's folder** (so a version bump `app-1.0.jar`→`app-1.1.jar` still resolves) with the main class read from the jar manifest. Deploy **targets come from the tool-window buttons** (the clicked row, or all), not the config. *Alternatives rejected:* a settings page (tedious free-text); a custom Run Configuration type (more boilerplate; its dropdown/team-share wins are marginal since Deploy is driven from the wdb tool window, not the Run menu).

**D7 — v1 actions are Compose controls calling the service directly.**
Since the tab is all-Compose (`addComposeTab`), v1 renders the toolbar (Refresh, Deploy, Debug, Run/HotRun/Stop/Restart/Rollback, Logs) as Compose buttons and holds selection in Compose state; each button calls a `WdbService` method that runs the op on `cs` and reports per-machine via `NotificationGroupManager` + a registered `<notificationGroup>`. This keeps the UI fully in Compose (the stated goal) with the least Swing plumbing. *Deferred:* native IDE `AnAction`s / `UiDataProvider` / a Swing `ActionToolbar` (for keyboard shortcuts + context menus) — a later refinement if IDE-action integration is wanted; the spec only requires the actions exist and report outcomes.

**D8 — Multi-select targeting for fan-out actions; single-row for debug/logs.**
The device list supports **multi-select**. Deploy / Run / Hot-run / Stop / Restart / Rollback apply to **all selected machines**, each independently (mirroring the CLI `--all` fan-out) and report a **per-machine result** — one failing/unreachable machine never aborts the others. **Debug and Logs are single-machine** (one debug session, one log pane); they act on the primary selected row and are disabled when the selection is not exactly one machine.

## Risks / Trade-offs

- **Jewel API is experimental / pinned to the platform** → keep UI code thin and behind a small `WallUi` surface; pin `sinceBuild/untilBuild` conservatively; expect to bump with platform upgrades.
- **Compose classpath clash** → mitigated by D1 (only `bundledModule`, never our own Compose jars); `wdb-client` is Compose-free so it can't drag Compose in.
- **External System Gradle run API surface** → the exact call to run a task + await completion is the least-settled piece; wrap it behind one `GradleDeploy` helper so the API specifics are localized (see Open Questions).
- **EDT violations** → a single marshalling boundary (D4); never touch `mutableStateOf` off the EDT.
- **wdb-client identity** → the UI needs a stable per-machine handle across refreshes; use the machine id from discovery as the key so rows don't flicker/duplicate.

## Migration Plan

Purely additive — a new module, no change to existing modules' behavior. Build/run the plugin via the IntelliJ Platform Gradle Plugin's `runIde` sandbox for development; install from disk for real use. Rollback = don't ship the module. `wdb-client` stays usable from the CLI unchanged.

## Open Questions

- **Exact External System call to run a Gradle task and await its result** (`ExternalSystemUtil.runTask` callback vs a blocking helper) — resolve during implementation against the pinned platform; isolated behind the `GradleDeploy` helper, so it does not affect the specs or the task breakdown. (Jar location is decided: newest glob match, D6.)

Resolved during exploration: a combined "build → deploy → hot-run → attach" macro is **deferred to v2** — v1 keeps the actions separate. Deploy config lives in an IDE Settings page; the output jar is resolved by a configurable glob; fan-out actions are multi-select.
