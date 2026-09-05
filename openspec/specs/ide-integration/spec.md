## Purpose

Lets a developer drive the demo wall from inside IntelliJ IDEA: a tool window lists discovered machines and offers deploy, run/hot-run/stop/restart/rollback, live logs, and one-click debugger attach — replacing the CLI and the manual "Remote JVM Debug" setup for everyday use.

## Requirements

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

### Requirement: Trigger Compose hot-reload from the IDE

The plugin SHALL let the operator push code changes into a machine's hot-running Compose app from the IDE, without using the CLI. For each targeted machine the plugin SHALL compute the change set of compiled classes since the last push and send it to that machine, and SHALL report a per-machine outcome (applied, rejected, or failed). A machine whose app is not running in hot-reload mode SHALL be reported as rejected and left untouched, and one machine's failure SHALL NOT abort the others. The operator SHALL be able to point the plugin at the module's compiled-classes location, remembered per project, and the plugin SHALL propose that location automatically for a detected Compose Desktop module.

#### Scenario: Reload pushes changed classes to a hot app

- **WHEN** the operator invokes Reload on a machine whose app is running in hot-reload mode, after changing and recompiling code
- **THEN** the plugin sends only the classes that changed since the last push and reports that the reload was applied, and the running app reflects the change without restarting

#### Scenario: Nothing to reload

- **WHEN** the operator invokes Reload but no compiled class has changed since the last push
- **THEN** the plugin reports there is nothing to reload and sends nothing

#### Scenario: Reload on an app that is not hot

- **WHEN** the operator invokes Reload on a machine whose app is not running in hot-reload mode
- **THEN** the plugin reports the reload was rejected and the app is left untouched

#### Scenario: One failing machine does not abort the others

- **WHEN** Reload is invoked on several machines and one is unreachable or rejects the reload
- **THEN** the plugin surfaces that machine's outcome and still reloads the reachable, hot machines

#### Scenario: Classes location is configured and proposed

- **WHEN** the operator configures deploy for a project that has a Compose Desktop module
- **THEN** the configuration offers a compiled-classes location prefilled from that module, and remembers it for subsequent reloads

### Requirement: Mirror and interact with a hot machine's screen

The plugin SHALL let the operator view the live screen of a machine's hot-running Compose app inside the IDE and refresh that view on demand. For a machine that is not running in hot-reload mode, the plugin SHALL report that the mirror is unavailable rather than showing a stale or blank image. The plugin SHALL also let the operator interact with the mirrored app by activating a UI element identified from the app's semantic tree (for example, tapping the element under a point the operator picks on the screenshot), and SHALL report whether the interaction succeeded.

#### Scenario: Screen appears in the IDE

- **WHEN** the operator opens the mirror for a machine whose app is running in hot-reload mode
- **THEN** the plugin shows that app's current screen as an image, and refreshing it shows the latest screen

#### Scenario: Mirror unavailable when not hot

- **WHEN** the operator opens the mirror for a machine whose app is not running in hot-reload mode
- **THEN** the plugin reports the mirror is unavailable instead of showing a blank or stale image

#### Scenario: Tap an element on the mirrored screen

- **WHEN** the operator picks a point on the mirrored screenshot that lies over an interactive element
- **THEN** the plugin resolves the element from the app's semantic tree, dispatches a tap to it, and reports whether it succeeded

#### Scenario: Interaction failure is surfaced

- **WHEN** a requested interaction cannot be applied (the app rejects it or is unreachable)
- **THEN** the plugin reports the failure rather than silently doing nothing

### Requirement: Act on a semantic node of the mirrored app

From the mirror's semantic-tree view the plugin SHALL let the operator invoke an action on a chosen node — at least click, long-click, and set-text — and SHALL report whether it was applied. Set-text SHALL let the operator supply the text to enter. Actions target the node the operator selected, not merely whatever covers a point.

#### Scenario: Set text on a field

- **WHEN** the operator picks a text-input node in the semantic tree and chooses Set Text with a value
- **THEN** the plugin sends a set-text action to that node and the hot app's field shows the entered text

#### Scenario: Act on a node from the tree

- **WHEN** the operator double-clicks a node in the semantic tree (or picks Click/Long Click from its menu)
- **THEN** the plugin dispatches that action to the node and reports whether it was applied

#### Scenario: Unsupported action is reported, not silent

- **WHEN** an action cannot be applied (the node does not support it, or the agent is too old to understand it)
- **THEN** the plugin reports the failure rather than appearing to succeed

### Requirement: Inspect and scroll the mirrored app

The mirror SHALL help the operator inspect the hot app's UI and scroll it. Selecting a node in the semantic tree SHALL indicate that node's position on the screenshot, and picking a point on the screenshot SHALL select the corresponding node in the tree. The plugin SHALL show the selected node's details (such as text, role, available actions, and bounds). The operator SHALL be able to keep the mirror image refreshing automatically while it is open. The operator SHALL be able to scroll a scrollable node, and the plugin SHALL report whether the scroll was applied.

#### Scenario: Selecting a tree node highlights it on screen

- **WHEN** the operator selects a node in the semantic tree
- **THEN** the plugin marks that node's bounds on the screenshot

#### Scenario: Clicking the screenshot selects the node

- **WHEN** the operator picks a point on the screenshot over an element
- **THEN** the plugin selects the corresponding node in the semantic tree and shows its details

#### Scenario: Auto-refresh keeps the image current

- **WHEN** the operator enables auto-refresh on the mirror
- **THEN** the screenshot updates on its own while the mirror is open, and stops when disabled

#### Scenario: Scroll a node

- **WHEN** the operator invokes scroll on a scrollable node
- **THEN** the plugin dispatches the scroll to that node and reports whether it was applied

### Requirement: Auto-reload on save

The plugin SHALL provide an opt-in "auto-reload on save" mode. While enabled, saving a JVM source
file in the project SHALL, after a short debounce, run the hot-reload flow (compile the configured
module, then push the class delta) to every machine currently running in hot-reload mode. The mode
SHALL be off by default and its state SHALL persist with the project's deploy configuration. When the
mode is enabled but no machine is hot, or no classes directory is configured, a save SHALL NOT
trigger a reload. Rapid consecutive saves SHALL be coalesced so that a reload already in progress is
not stacked; at most one follow-up reload runs for edits made during an in-flight reload.

#### Scenario: Saving a source file reloads hot machines

- **WHEN** auto-reload is enabled, at least one machine is hot, and the operator saves a JVM source file
- **THEN** after the debounce the plugin compiles the module and pushes the class delta to every hot machine

#### Scenario: Disabled or no hot machines does nothing

- **WHEN** auto-reload is disabled, or it is enabled but no machine is currently hot
- **THEN** saving a file does not trigger a reload

#### Scenario: Bursts of saves coalesce

- **WHEN** several saves occur within the debounce window or during an in-flight reload
- **THEN** the plugin performs a single reload for the burst rather than one per save

### Requirement: Redeploy fallback on failed hot-apply

When a hot-reload delta cannot be applied to a machine (a failed hot-apply, as opposed to the app
not being in hot-reload mode), the plugin SHALL automatically fall back to a full redeploy and
restart of that machine using the persisted deploy configuration, and SHALL report that a redeploy
fallback occurred. A rejected reload (the app is not in hot-reload mode, or an integrity check
failed) SHALL NOT trigger a redeploy — the app is left untouched and the plugin reports the
rejection. This fallback applies to both manual and auto-reload.

#### Scenario: Failed hot-apply redeploys

- **WHEN** a reload's hot-apply fails on a machine and a deploy configuration is available
- **THEN** the plugin redeploys and restarts that machine from the configured jar and reports the fallback

#### Scenario: Rejected reload does not redeploy

- **WHEN** a reload is rejected because the app is not in hot-reload mode
- **THEN** the plugin reports the rejection and does not redeploy or restart the machine

### Requirement: Offer to exclude stale signatures in the build

When a deploy from the IDE strips stale JAR signature files, the plugin SHALL offer an action that
adds a signature-file exclude to the app's Gradle build, so subsequent deploys need no stripping. The
action SHALL target the build task derived from the configured deploy task and insert the exclude into
that task's configuration in the module's Kotlin-DSL build script, undoably. It SHALL be safe: if the
build script or task cannot be located or edited reliably (including a non-Kotlin-DSL build), the
plugin SHALL instead open the build script and place the exclude snippet on the clipboard rather than
modify the file. The action SHALL be idempotent — when the exclude is already present it makes no
change and says so.

#### Scenario: One-click exclude after a strip

- **WHEN** a deploy strips stale signature files and the operator invokes the offered action
- **THEN** the plugin adds the signature-file exclude to the deploy task's configuration in the module's Kotlin-DSL build script and opens the file at the change

#### Scenario: Safe fallback when the build can't be edited

- **WHEN** the action cannot locate or safely edit the build script (e.g. a Groovy build, or an unparsable file)
- **THEN** the plugin opens the build script and copies the exclude snippet to the clipboard instead of modifying the file

#### Scenario: Idempotent

- **WHEN** the exclude is already present for the target task
- **THEN** the action makes no change and reports that the build already excludes signatures

### Requirement: Hot-run restarts a running app after confirmation

When the operator triggers hot-run on a machine whose app is already running, the plugin SHALL, before
doing anything, ask the operator to confirm stopping the current run and starting in Compose hot-reload
mode, and SHALL offer to remember that choice. On confirmation (or when the choice was remembered) the
plugin SHALL stop the running app and start it fresh in hot-reload mode; on cancellation it SHALL leave
that machine untouched. A machine whose app is not running SHALL hot-run directly with no prompt.

#### Scenario: Confirm restart into hot mode

- **WHEN** the operator hot-runs a machine whose app is running and confirms the prompt
- **THEN** the plugin stops the current run and starts the app in hot-reload mode

#### Scenario: Cancel leaves the app running

- **WHEN** the operator hot-runs a running machine and cancels the prompt
- **THEN** the app keeps running as it was and nothing is restarted

#### Scenario: Remembered choice skips the prompt

- **WHEN** the operator chose to remember the confirmation on an earlier hot-run
- **THEN** later hot-runs on a running machine restart into hot mode without prompting

### Requirement: Run action reflects the running state

The Run lifecycle action SHALL reflect whether the target's app is already running: when it is running
the action SHALL present as a restart (a restart icon and label) and restart the app; when it is not
running the action SHALL present as run and launch the app. A per-machine control SHALL use that
machine's state; the all-machines control SHALL treat the app as running only when every target is
running.

#### Scenario: Running shows restart

- **WHEN** a machine's app is running
- **THEN** its Run control shows a restart icon and restarts the app when invoked

#### Scenario: Stopped shows run

- **WHEN** a machine's app is not running
- **THEN** its Run control shows the run icon and launches the app when invoked

### Requirement: Lifecycle controls are gated by machine state

Toolbar controls that cannot apply to a machine's current state SHALL be disabled rather than silently
doing nothing or failing after invocation. Reload and mirror SHALL require hot-reload mode; stop and
debug-attach SHALL require a running app; rollback SHALL require a previous deployment to exist. A
per-machine control SHALL reflect that machine's state; an all-machines control SHALL be enabled when
at least one target qualifies.

#### Scenario: Reload disabled when not hot

- **WHEN** a machine's app is not in hot-reload mode
- **THEN** its reload (and mirror) control is disabled

#### Scenario: Stop disabled when not running

- **WHEN** a machine's app is not running
- **THEN** its stop (and debug) control is disabled

#### Scenario: Rollback disabled without a previous deployment

- **WHEN** a machine has no previous deployment retained
- **THEN** its rollback control is disabled

### Requirement: Bring the app window to the foreground

The plugin SHALL provide a per-machine action that brings the running app's window to the foreground on
that machine, so the operator can raise a window that has fallen behind others without walking to the
wall. The action SHALL be available only when the machine's app is running, and SHALL report whether the
window was raised (including a clear result when no app is running or no window is found).

#### Scenario: Raise a running app's window

- **WHEN** the operator invokes bring-to-front on a machine whose app is running
- **THEN** the app's window is raised to the foreground on that machine and the plugin reports the outcome

#### Scenario: Unavailable when not running

- **WHEN** the machine's app is not running
- **THEN** the bring-to-front control is not offered (or does nothing and reports the app is not running)
