## Purpose

Makes the agent easy to install on many demo-wall machines: one command registers it to autostart in the kiosk session and opens the firewall, so it comes back automatically after reboot.

## ADDED Requirements

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
