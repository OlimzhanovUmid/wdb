## ADDED Requirements

### Requirement: Installer places the agent in a stable, self-update-writable location

The installer SHALL install the agent under a stable system location that persists across updates and is writable by the running agent, so self-update can place and switch versions there without elevation. The location SHALL NOT be a scratch/temporary folder, and SHALL NOT be a location that requires elevation for the running agent to write (which would break self-update).

#### Scenario: Installed to a stable writable location

- **WHEN** an operator installs the agent
- **THEN** the agent's versioned layout lives under a stable system location, and the running agent can write a new version there during self-update without prompting for elevation

### Requirement: Installer registers the agent in the OS installed-apps list

The installer SHALL register the agent so it appears in the operating system's list of installed applications with a name and version, and can be uninstalled from there.

#### Scenario: Appears in installed apps and uninstalls from there

- **WHEN** the agent is installed and the operator opens the OS installed-apps list
- **THEN** the agent is listed with its name and version, and choosing to uninstall it removes the agent

### Requirement: Installer removes any previous agent installation first

Before installing, the installer SHALL detect and remove any existing agent installation on the machine — stopping the running agent, removing its autostart registration and firewall rule, and clearing the prior install — so that re-installing or migrating from an older install is clean and idempotent and never leaves two agents or a stale autostart behind.

#### Scenario: Re-install over an existing agent

- **WHEN** the operator installs the agent on a machine that already has an agent installed (including an older ad-hoc install)
- **THEN** the installer stops and removes the previous agent (process, autostart, firewall) before installing, and afterwards exactly one agent is registered and running

### Requirement: Unattended (silent) install

The installer SHALL support a non-interactive install that takes the machine name (and install location) as parameters and requires no user interaction, so many machines can be provisioned by a script.

#### Scenario: Silent install with a machine name

- **WHEN** the operator runs the installer in silent mode passing the machine name
- **THEN** the agent installs, registers autostart, and starts — with no prompts — and reports that machine name in discovery and status

## MODIFIED Requirements

### Requirement: Uninstall reverses installation

The agent's uninstall SHALL fully reverse the installation: stop the running agent process, remove the autostart registration and the firewall rule it added, remove the installed versioned layout and runtime data it created, and remove the OS installed-apps registration. Afterwards the agent SHALL no longer start on login and SHALL leave no autostart, firewall rule, install directory, or installed-apps entry behind.

#### Scenario: Clean uninstall

- **WHEN** an operator uninstalls the agent (from the OS installed-apps list or the uninstall command)
- **THEN** the running agent stops, the autostart registration and firewall rule are removed, the install directory and runtime layout are deleted, the installed-apps entry is gone, and the agent no longer starts on login
