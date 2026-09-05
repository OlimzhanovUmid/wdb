## ADDED Requirements

### Requirement: Auto-reload on save

The plugin SHALL provide an opt-in "auto-reload on save" mode. While enabled, saving a JVM source
file in the project SHALL, after a short debounce, run the hot-reload flow (compile the configured
module, then push the class delta) to every machine currently running in hot-reload mode. The mode
SHALL be off by default and its state SHALL persist with the project's deploy configuration. When the
mode is enabled but no machine is hot, or no classes directory is configured, a save SHALL NOT
trigger a reload. Rapid consecutive saves SHALL be coalesced so that a reload already in progress is
not stacked; at most one follow-up reload runs for edits made during an in-flight reload.

#### Scenario: Saving a source file reloads hot machines

- **WHEN** auto-reload is enabled, at least one machine is hot, and the operator saves a JVM source file
- **THEN** after the debounce the plugin compiles the module and pushes the class delta to every hot machine

#### Scenario: Disabled or no hot machines does nothing

- **WHEN** auto-reload is disabled, or it is enabled but no machine is currently hot
- **THEN** saving a file does not trigger a reload

#### Scenario: Bursts of saves coalesce

- **WHEN** several saves occur within the debounce window or during an in-flight reload
- **THEN** the plugin performs a single reload for the burst rather than one per save

### Requirement: Redeploy fallback on failed hot-apply

When a hot-reload delta cannot be applied to a machine (a failed hot-apply, as opposed to the app
not being in hot-reload mode), the plugin SHALL automatically fall back to a full redeploy and
restart of that machine using the persisted deploy configuration, and SHALL report that a redeploy
fallback occurred. A rejected reload (the app is not in hot-reload mode, or an integrity check
failed) SHALL NOT trigger a redeploy — the app is left untouched and the plugin reports the
rejection. This fallback applies to both manual and auto-reload.

#### Scenario: Failed hot-apply redeploys

- **WHEN** a reload's hot-apply fails on a machine and a deploy configuration is available
- **THEN** the plugin redeploys and restarts that machine from the configured jar and reports the fallback

#### Scenario: Rejected reload does not redeploy

- **WHEN** a reload is rejected because the app is not in hot-reload mode
- **THEN** the plugin reports the rejection and does not redeploy or restart the machine
