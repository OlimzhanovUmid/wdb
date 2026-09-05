## ADDED Requirements

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
