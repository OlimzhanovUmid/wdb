## ADDED Requirements

### Requirement: Versioned install layout

The install command SHALL lay the agent out under a per-version directory with a `current` pointer that the autostart task launches through, so an update can place a new version alongside the running one and switch to it without overwriting a running executable.

#### Scenario: Install creates a versioned layout

- **WHEN** an operator installs the agent
- **THEN** the agent binaries live under a per-version directory, a `current` pointer names that version, and the autostart task launches that version through the pointer

### Requirement: Agent self-update distributes a new build

The client SHALL provide an operation to distribute a new agent build to one or many machines (including an "all discovered machines" target). Each targeted agent SHALL store the received build as a new version, switch the `current` pointer to it, and restart onto the new version. The transfer SHALL verify integrity before the new version is switched to.

#### Scenario: Update one machine

- **WHEN** the client sends a newer agent build to a machine
- **THEN** the agent stores it as a new version, switches `current` to it, restarts, and afterwards reports the new agent version in status

#### Scenario: Fan-out update with per-machine results

- **WHEN** the client updates all discovered machines and one is unreachable
- **THEN** the reachable machines update and the unreachable one is reported as failed, without aborting the others

#### Scenario: Integrity failure leaves the running agent untouched

- **WHEN** a received agent build fails its integrity check
- **THEN** the agent does not switch `current`, keeps running the existing version, and reports the failure

### Requirement: Self-update rolls back a bad agent build

The agent SHALL retain the previously current agent version across an update. If the newly switched-to agent does not come back healthy after the restart, the `current` pointer SHALL revert to the previous version and that version SHALL be restarted, so a bad agent build cannot leave a machine without a working agent.

#### Scenario: New agent fails to come back

- **WHEN** an update switches to a new agent version that fails to start or announce within a bounded time
- **THEN** the `current` pointer reverts to the previous version, the previous agent is restarted, and the machine remains reachable

#### Scenario: New agent is healthy

- **WHEN** an update switches to a new agent version that starts and announces successfully
- **THEN** the update is committed and the previous version is retained only for the next rollback
