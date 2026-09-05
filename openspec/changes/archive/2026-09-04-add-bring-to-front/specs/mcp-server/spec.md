## ADDED Requirements

### Requirement: Bring a machine's app window to the front

The server SHALL provide a tool that raises the running app's window to the foreground on a named
machine, reporting whether it was raised. It SHALL report a clear result when the machine is unreachable,
no app is running, or no window is found.

#### Scenario: Raise via the tool

- **WHEN** the agent calls the bring-to-front tool for a machine whose app is running
- **THEN** the server raises that app's window on the machine and reports success

#### Scenario: Nothing to raise

- **WHEN** the agent calls the bring-to-front tool and no app is running (or no window is found)
- **THEN** the server reports that there was no window to raise rather than a false success
