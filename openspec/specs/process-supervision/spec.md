# process-supervision Specification

## Purpose

Launches the deployed Compose Desktop app in the interactive kiosk session and keeps it alive — across crashes, agent restarts and reboots — so demo-wall screens never stay blank, and stops it gracefully on command.

## Requirements

### Requirement: Launch the app in the interactive session

On command, the agent SHALL launch the current deployment using its bundled JRE inside the interactive logged-in session so the app's GUI is visible on the machine's physical display.

#### Scenario: Launch a deployed app

- **WHEN** a client issues run for a machine that has a current deployment
- **THEN** the agent starts the app process in the interactive session, the GUI appears on screen, and the machine's state becomes `running`

#### Scenario: Run with no deployment

- **WHEN** a client issues run for a machine that has no deployment
- **THEN** the agent rejects the command and reports that nothing is deployed

### Requirement: App receives its machine identity

The agent SHALL provide the machine's name and stable identifier to the app process through its environment on every launch.

#### Scenario: App reads its identity

- **WHEN** the app is launched on the machine named `wall-03`
- **THEN** its environment contains the machine name `wall-03` and the machine's stable identifier

### Requirement: Auto-restart on crash

While supervision is active, the agent SHALL detect app process exit that is not an operator-requested stop and SHALL relaunch the app, so a crashed screen recovers automatically.

#### Scenario: App crashes

- **WHEN** a supervised app process exits unexpectedly
- **THEN** the agent relaunches it and the machine returns to `running`

#### Scenario: Restart storm is bounded

- **WHEN** an app exits repeatedly in rapid succession
- **THEN** the agent applies a backoff and reports the machine as `crashed` rather than relaunching in a tight loop

### Requirement: Stop the app on command

On command, the agent SHALL stop the supervised app and SHALL NOT relaunch it, leaving the machine in `stopped` state until the next run.

#### Scenario: Operator stops the app

- **WHEN** a client issues stop for a running machine
- **THEN** the agent terminates the app, suppresses auto-restart, and the machine state becomes `stopped`

### Requirement: Stop is graceful with a bounded timeout

On stop (and on any restart), the agent SHALL first request an orderly close so the app's close handlers run, and SHALL forcibly terminate the app only if it has not exited within a bounded timeout.

#### Scenario: App closes on request

- **WHEN** stop is issued and the app honours the close request
- **THEN** the app's close handlers run and the process exits without being force-killed

#### Scenario: App ignores the close request

- **WHEN** stop is issued and the app has not exited when the timeout elapses
- **THEN** the agent forcibly terminates it and the machine becomes `stopped`

### Requirement: Desired state persists across agent restarts and reboots

The agent SHALL persist whether the app is meant to be running. After an agent restart or a machine reboot into the kiosk session, the agent SHALL launch the app if the desired state is running and a deployment exists, and SHALL NOT launch it if the desired state is stopped.

#### Scenario: Wall comes back after a reboot

- **WHEN** a machine whose app was running reboots and auto-logs into the kiosk session
- **THEN** the agent starts and launches the app without operator action

#### Scenario: Stopped box stays quiet

- **WHEN** a machine whose app was stopped by an operator reboots
- **THEN** the agent starts but does not launch the app

### Requirement: No orphaned app instances

The app SHALL only run under the supervision of the agent. If the agent process terminates, the app process SHALL terminate with it, so a restarted agent never finds a second, unsupervised instance.

#### Scenario: Agent dies while the app runs

- **WHEN** the agent process is killed while supervising a running app
- **THEN** the app process exits, and after the agent is relaunched exactly one app instance is running (per the persisted desired state)

### Requirement: Display stays awake while the app runs

While the app is running, the agent SHALL prevent the display from turning off and the machine from sleeping due to inactivity, and SHALL release that hold when the app stops.

#### Scenario: Idle demo

- **WHEN** the app has been running with no user input for longer than the machine's display timeout
- **THEN** the display remains on and the app remains visible
