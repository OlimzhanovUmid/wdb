## ADDED Requirements

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
