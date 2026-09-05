## Purpose

Lets a developer drive the demo wall from inside IntelliJ IDEA: a tool window lists discovered machines and offers deploy, run/hot-run/stop/restart/rollback, live logs, and one-click debugger attach — replacing the CLI and the manual "Remote JVM Debug" setup for everyday use.

## ADDED Requirements

### Requirement: Tool window lists discovered machines

The plugin SHALL provide an IDE tool window that lists the machines discovered on the network, showing at least each machine's name, address, app state, hot-reload mode, and agent version. The list SHALL be refreshable on demand and SHALL populate when the tool window is opened.

#### Scenario: Machines appear on open

- **WHEN** the operator opens the wdb tool window on a network with reachable machines
- **THEN** the tool window lists those machines with their name, address, app state, hot-mode, and agent version

#### Scenario: Manual refresh updates the list

- **WHEN** the operator triggers refresh
- **THEN** the plugin re-discovers and the list reflects machines that appeared or disappeared

#### Scenario: Discovery runs without freezing the IDE

- **WHEN** discovery is in progress
- **THEN** the IDE UI remains responsive and results appear when discovery completes

### Requirement: Lifecycle actions on one or more selected machines

The tool window SHALL let the operator select one or more machines and invoke run, run in hot-reload mode, stop, restart, or roll back on all of them. Each machine SHALL be acted on independently and the plugin SHALL report a per-machine outcome; a failure or unreachable machine SHALL NOT abort the others.

#### Scenario: Run selected machines

- **WHEN** the operator selects one or more machines and invokes run (or hot-run)
- **THEN** the plugin asks each selected machine's agent to launch the deployment in the requested mode and reports each machine's success or error

#### Scenario: Stop, restart, rollback

- **WHEN** the operator invokes stop, restart, or rollback on the selected machines
- **THEN** the plugin performs that operation on each and reports the per-machine outcome

#### Scenario: One failing machine does not abort the others

- **WHEN** an action is invoked on several machines and one is unreachable or its agent returns an error
- **THEN** the plugin surfaces that machine's failure and still completes the action on the reachable machines

### Requirement: Deploy the output of a Gradle task

The plugin SHALL let the operator deploy to the target machine(s) by running a configured Gradle task and pushing its output jar. The Gradle task SHALL be chosen from the project's actual tasks and the jar SHALL be chosen with a file picker, in a dialog whose values persist per project; the operator SHALL be able to reconfigure them.

#### Scenario: Configure via a dialog with real tasks and a file picker

- **WHEN** the operator configures deploy
- **THEN** a dialog offers the project's real Gradle tasks to choose from and a file picker for the jar, and remembers the choice for next time

#### Scenario: Deploy runs the task then pushes

- **WHEN** the operator deploys to the target machine(s) with deploy configured
- **THEN** the plugin runs that task once, locates the built jar, pushes it to each target machine, and reports the per-machine deployment result

#### Scenario: Build failure aborts the deploy

- **WHEN** the configured Gradle task fails to build
- **THEN** the plugin reports the build failure and does not push anything

### Requirement: Stream a machine's logs in the IDE

The plugin SHALL stream a selected machine's application logs into a tool-window pane, showing retained history first and then live output, and SHALL let the operator stop streaming.

#### Scenario: Live logs appear in a pane

- **WHEN** the operator opens logs for a running machine
- **THEN** the pane shows the machine's recent output and continues to append new lines live

#### Scenario: Stopping the stream

- **WHEN** the operator closes or stops the log stream
- **THEN** streaming ends and the machine's app is unaffected

### Requirement: One-click debugger attach

The plugin SHALL let the operator attach the IDE's JVM debugger to a machine's app in one action, by opening a loopback tunnel to that machine's debug port and starting a Remote JVM Debug session against the local end — without the operator authoring a run configuration.

#### Scenario: Attach starts a debug session

- **WHEN** the operator invokes debug on a machine whose app exposes a debug port
- **THEN** the plugin opens the tunnel and starts an IDE debug session attached to the app, so breakpoints in the project's sources are hit

#### Scenario: Tunnel is released when debugging ends

- **WHEN** the debug session ends
- **THEN** the plugin releases the tunnel it opened for that session

#### Scenario: Debug is unavailable without a debug port

- **WHEN** the operator invokes debug on a machine whose app is not running or exposes no debug port
- **THEN** the plugin reports that debugging is unavailable rather than opening a dead session

### Requirement: UI matches the IDE theme

The tool window UI SHALL follow the active IDE theme (light/dark and accent) so it is visually consistent with the rest of the IDE.

#### Scenario: Theme switch is reflected

- **WHEN** the operator switches the IDE between light and dark themes
- **THEN** the wdb tool window updates to match without a restart
