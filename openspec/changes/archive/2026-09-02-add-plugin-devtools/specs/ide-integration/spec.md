## ADDED Requirements

### Requirement: Mirror and interact with a hot machine's screen

The plugin SHALL let the operator view the live screen of a machine's hot-running Compose app inside the IDE and refresh that view on demand. For a machine that is not running in hot-reload mode, the plugin SHALL report that the mirror is unavailable rather than showing a stale or blank image. The plugin SHALL also let the operator interact with the mirrored app by activating a UI element identified from the app's semantic tree (for example, tapping the element under a point the operator picks on the screenshot), and SHALL report whether the interaction succeeded.

#### Scenario: Screen appears in the IDE

- **WHEN** the operator opens the mirror for a machine whose app is running in hot-reload mode
- **THEN** the plugin shows that app's current screen as an image, and refreshing it shows the latest screen

#### Scenario: Mirror unavailable when not hot

- **WHEN** the operator opens the mirror for a machine whose app is not running in hot-reload mode
- **THEN** the plugin reports the mirror is unavailable instead of showing a blank or stale image

#### Scenario: Tap an element on the mirrored screen

- **WHEN** the operator picks a point on the mirrored screenshot that lies over an interactive element
- **THEN** the plugin resolves the element from the app's semantic tree, dispatches a tap to it, and reports whether it succeeded

#### Scenario: Interaction failure is surfaced

- **WHEN** a requested interaction cannot be applied (the app rejects it or is unreachable)
- **THEN** the plugin reports the failure rather than silently doing nothing
