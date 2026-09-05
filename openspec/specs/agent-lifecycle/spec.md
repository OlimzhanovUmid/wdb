# agent-lifecycle Specification

## Purpose

Makes the agent easy to install on many demo-wall machines: one command registers it to autostart in the kiosk session and opens the firewall, so it comes back automatically after reboot.

## Requirements

### Requirement: Agent runs in the interactive kiosk session

The agent SHALL run inside the logged-in interactive session (not as a session-0 Windows service) so that apps it launches can render their GUI on the machine's display.

#### Scenario: Agent starts in the interactive session

- **WHEN** the kiosk user is logged in and the machine has the agent installed
- **THEN** the agent is running within that interactive session and apps it launches appear on the physical display

### Requirement: Self-install and autostart registration

The agent SHALL provide a self-install command that registers it to start automatically when the kiosk session logs in and that adds the network firewall rule it needs, with minimal manual steps.

#### Scenario: Install registers autostart and firewall

- **WHEN** an operator runs the agent's install command on a machine
- **THEN** the agent is registered to launch on kiosk-session login and the required inbound firewall rule is present

#### Scenario: Survives reboot

- **WHEN** an installed machine reboots and auto-logs into the kiosk session
- **THEN** the agent starts automatically and begins announcing without manual action

### Requirement: Install sets the machine name

The install command SHALL accept an optional human-readable machine name, defaulting to the hostname, and the agent SHALL report that name in discovery answers and status.

#### Scenario: Named install

- **WHEN** an operator installs the agent with the name `wall-03`
- **THEN** discovery and status list that machine as `wall-03`

### Requirement: Agent recovers from its own failure

If the agent process exits unexpectedly while the kiosk session remains logged in, it SHALL be restarted automatically without operator action.

#### Scenario: Agent process is killed

- **WHEN** the agent process terminates unexpectedly in a logged-in kiosk session
- **THEN** the agent is relaunched automatically and resumes announcing within a short interval

### Requirement: Uninstall reverses installation

The agent SHALL provide an uninstall command that removes the autostart registration and the firewall rule it added.

#### Scenario: Clean uninstall

- **WHEN** an operator runs the agent's uninstall command
- **THEN** the autostart registration and the added firewall rule are removed and the agent no longer starts on login

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
