## ADDED Requirements

### Requirement: Detect an available agent update from the published manifest

The plugin SHALL determine the latest published agent version from the release manifest and, for each discovered machine, indicate whether an agent update is available by comparing the machine's reported agent version against the published one. The check SHALL run off the UI thread and SHALL degrade gracefully when the manifest is unreachable (no update indication, no error spam).

#### Scenario: Update available is flagged

- **WHEN** a machine reports an agent version older than the latest published agent version
- **THEN** the plugin indicates that an agent update is available for that machine

#### Scenario: Up-to-date machines are not flagged

- **WHEN** a machine reports an agent version equal to (or newer than) the latest published version
- **THEN** the plugin does not offer an update for that machine

#### Scenario: Manifest unreachable degrades quietly

- **WHEN** the release manifest cannot be fetched (offline, no release yet)
- **THEN** the plugin shows no update indication and does not surface a blocking error

### Requirement: Roll out an agent update from the published release

The plugin SHALL let the operator update the agent on one or more machines using the published release: it downloads the agent installer named by the manifest, verifies its integrity against the manifest (size and sha256) before use, and delivers it to each selected machine over the existing agent-update transport. Machines SHALL be updated independently with a per-machine outcome; a failure or unreachable machine SHALL NOT abort the others. The downloaded installer SHALL be cached per version so a multi-machine rollout downloads once.

#### Scenario: Update a machine from the release

- **WHEN** the operator triggers an agent update for a machine with an available update
- **THEN** the plugin downloads the manifest's agent installer, verifies its size and sha256, delivers it to that machine's agent, and reports success (the machine restarts onto the new version)

#### Scenario: Integrity failure aborts before delivery

- **WHEN** the downloaded installer's size or sha256 does not match the manifest
- **THEN** the plugin does not deliver it and reports an integrity error

#### Scenario: Fleet rollout downloads once, reports per machine

- **WHEN** the operator updates several machines to the same version
- **THEN** the plugin downloads the installer once (cached), delivers it to each machine, and reports each machine's success or error without one failure aborting the rest
