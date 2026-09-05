## MODIFIED Requirements

### Requirement: App is always debuggable on the agent's loopback

Every launch of the app SHALL enable JDWP listening on a loopback-only port of the agent machine without suspending startup, and the agent SHALL report that port in the machine's status. The debug port SHALL NOT be reachable from the LAN except through a forward. The JDWP port SHALL default to a fixed, configurable per-machine value so it is stable across app restarts; if that port is unavailable when the app launches, the agent SHALL fall back to an ephemeral port and indicate in status that a fallback port is in use.

#### Scenario: JDWP port reported

- **WHEN** the app is running
- **THEN** status reports the JDWP port actually in use, and that port accepts connections only from the machine itself

#### Scenario: Fixed port stable across restart

- **WHEN** the app restarts (crash, operator restart, or push) and the configured JDWP port is available
- **THEN** the app is debuggable on the same fixed port as before, so an already-open forward and an IDE run-config remain valid without reconfiguration

#### Scenario: Fallback when the fixed port is busy

- **WHEN** the configured JDWP port is already in use at launch time
- **THEN** the agent launches the app on an ephemeral JDWP port instead, reports that port in status, and flags that a fallback (non-fixed) port is in use
