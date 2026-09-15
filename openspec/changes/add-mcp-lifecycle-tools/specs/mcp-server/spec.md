## ADDED Requirements

### Requirement: Lifecycle parity — restart and rollback tools

The server SHALL provide tools to restart the app on a named machine and to roll it back to its previous deployment, so an AI agent can recover a machine without the CLI. Each tool SHALL report whether the operation succeeded, and SHALL report a clear error when the machine is unreachable rather than failing silently.

#### Scenario: Restart a machine

- **WHEN** the agent calls the restart tool for a machine
- **THEN** the server restarts the app on that machine and reports success (or a clear error if unreachable)

#### Scenario: Roll back a machine

- **WHEN** the agent calls the rollback tool for a machine
- **THEN** the server rolls that machine back to its previous deployment and reports the outcome

### Requirement: Update a machine's agent from the published release

The server SHALL provide a tool to update a named machine's agent to the latest published version: it reads the release manifest, downloads and verifies (size + sha256) the agent installer named there, and delivers it to the machine over the existing agent-update transport. The tool SHALL be idempotent — when the machine already runs the latest version it SHALL report that and NOT push — and SHALL report a clear error if the manifest is unreachable or the download fails integrity.

#### Scenario: Update an out-of-date agent

- **WHEN** the agent calls the agent-update tool for a machine whose agent is older than the latest published version
- **THEN** the server downloads the manifest's agent installer, verifies it, delivers it to the machine, and reports that the machine is updating (restarting) to the new version

#### Scenario: Already up to date is a no-op

- **WHEN** the agent-update tool is called for a machine already on the latest published version
- **THEN** the server reports it is already up to date and does not deliver anything

#### Scenario: Integrity or manifest failure is reported

- **WHEN** the release manifest is unreachable, or the downloaded installer fails its size/sha256 check
- **THEN** the server returns a clear error result and does not deliver a bad installer
