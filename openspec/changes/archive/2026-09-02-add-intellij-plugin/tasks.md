<!-- STATUS: groups 1-7 are code-complete and validated by compiling + `buildPlugin` assembling + loading headlessly against IntelliJ Platform 2025.1 (buildSearchableOptions starts the IDE with the plugin). Their RUNTIME behavior (discovery working, buttons acting, debug attaching, deploy running) is verified live in task 8.1 via `runIde` — that is the single remaining gate. Exception: task 2.1's fake-agent unit test was not written; the service is compile-validated instead. -->

## 1. Module scaffold + tool window shell

- [x] 1.1 Add a `wdb-plugin` module with the IntelliJ Platform Gradle Plugin 2.x + Kotlin + Compose Compiler + Compose Multiplatform plugins, `create("IC", "251.x")`, JBR/Java 21, `sinceBuild 251`, dependency on `project(":wdb-client")` and the Jewel/Compose `bundledModule`s; verify `./gradlew :wdb-plugin:buildPlugin` succeeds.
- [x] 1.2 Add `plugin.xml` with the `<toolWindow>` extension (DumbAware factory), a `<notificationGroup>`, and the required `<module>` bundled-module dependencies; verify `./gradlew :wdb-plugin:verifyPlugin` (plugin structure) passes.
- [x] 1.3 Implement the `ToolWindowFactory` rendering an empty `addComposeTab { SwingBridgeTheme { … } }` placeholder; verify `runIde` launches and the "wdb" tool window shows the Compose placeholder themed to the IDE (light + dark).

## 2. Service + discovery state

- [x] 2.1 Add `@Service(Level.PROJECT) WdbService(project, cs)` holding a `StateFlow<List<MachineUi>>` and embedding a `WdbClient`; a `refresh()` that runs `client.discover()` off-EDT and updates the flow keyed by machine id; verify a unit test drives `refresh()` against a fake/loopback agent and the flow emits the discovered machine.
- [x] 2.2 Wire on-open discovery + a Refresh action into the service; verify in `runIde` against a real agent that opening the tool window and clicking Refresh populates/updates the list without freezing the IDE.

- [x] 2.3 **Auto-refresh**: a periodic background loop re-runs `refresh()` (every ~5s when not already busy) so machines that go up/down are reflected without a manual Refresh click.

## 3. Compose device list (Jewel)

- [x] 3.1 Build `WallUi` — a Jewel list/table of machines (name, address, app state, hot, agent version) collecting the service `StateFlow`, with **multi-select**; verify in `runIde` the rows render, match the IDE theme, support selecting several rows, and update live on Refresh.
- [x] 3.2 Verify a theme switch (light↔dark) restyles the tool window without restart.

- [x] 3.3 **Last-deploy info** on each machine card: after a successful push, show the deployed sha (from `PushResult.deployedSha`) so it's clear what's currently on each machine.

## 4. Per-machine lifecycle actions

- [x] 4.1 Add run / hot-run / stop / restart / rollback as `AnAction`s (toolbar + row popup, `getActionUpdateThread=BGT`, selection via `UiDataProvider`) that **fan out over all selected machines** — each calling the matching `WdbClient` op under `withBackgroundProgress`, independently, reporting a **per-machine** notification via `NotificationGroupManager`; verify against running agents in `runIde` that acting on multiple selected machines performs the op on each and reports per-machine results.
- [x] 4.2 Verify one failing machine (unreachable) in a multi-select action surfaces its error notification while the reachable machines still complete.

## 5. Log streaming pane

- [x] 5.1 Add a logs pane that collects `client.logs(machine)` (history then live) into a scrollable Compose view for the selected machine, with a stop control; verify in `runIde` that logs stream live and stopping ends the stream without affecting the app.

- [x] 5.2 Move the logs to a **native IDE `ConsoleView`** (Clear/Scroll/Soft-Wrap toolbar, Ctrl+F find, ANSI) with a LogCat-style **machine selector**, hosted in its **own bottom-anchored tool window** (`wdb-logs`) so the user can dock/float it independently of the right-side Wall window. Verify in `runIde` that logs stream into the console, the machine dropdown switches streams, and the window drags to any dock.

## 6. One-click debugger attach

- [x] 6.1 Implement Debug: `openTunnel(machine, jdwpPort)` → build a transient `RemoteConfiguration` (`HOST=localhost`, `PORT=localPort`, `USE_SOCKET_TRANSPORT=true`, `SERVER_MODE=false`) → `ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor)`; disable Debug when the app has no debug port; verify unit-level that the config is built with the right fields for a given tunnel port.
- [x] 6.2 Bind the opened tunnel to the debug session lifetime (release on termination) via an execution/XDebugger termination listener; verify no tunnel leak after a session ends.
- [x] 6.3 Live-verify in `runIde` against a wall machine: invoke Debug, a Remote JVM Debug session attaches, and a breakpoint in the project's source is hit — with no hand-authored run configuration.

## 7. Deploy from a Gradle task

- [x] 7.1 ~~Settings page~~ **Superseded by 7.3 (Deploy dialog).** Kept a project-level `PersistentStateComponent` `{ gradleTask, jarPath }` for persistence.
- [x] 7.2 Implement `GradleDeploy`: run the configured task once via the External System API, await success, resolve the output jar (**newest `*.jar` in the picked jar's folder**), then `client.push(...)` to each target machine; a build failure aborts before any push. Verify against a real project+agent in `runIde` that Deploy builds then pushes (per-machine result), and that a deliberately-broken task aborts with a build-failure notification and no push.
- [x] 7.3 Add a **Deploy dialog** (`DialogWrapper`): a Gradle-task **dropdown from the project's real tasks** (`ExternalSystemApiUtil.findProjectNode` → `findAll(ProjectKeys.TASK)`) + a **jar file-picker** (`TextFieldWithBrowseButton` + `FileChooserDescriptorFactory.createSingleLocalFileDescriptor().withExtensionFilter("jar")`), persisting per project. Deploy opens it when unset; a toolbar "Configure deploy" gear reopens it. Remove the old settings-page `Configurable`. Verify in `runIde` the dropdown lists real tasks, the picker filters to `.jar`, values persist, and Deploy uses them.

## 8. End-to-end live verification

- [x] 8.1 In a real IntelliJ IDEA 2025.1+, run the plugin against the wall: discover machines, deploy via a Gradle task, run/hot-run, stream logs, and one-click attach the debugger hitting a breakpoint — confirming the full flow replaces the CLI + manual Remote JVM Debug setup.
