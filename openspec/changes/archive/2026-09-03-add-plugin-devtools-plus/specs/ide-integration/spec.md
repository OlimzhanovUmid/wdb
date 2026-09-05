## ADDED Requirements

### Requirement: Inspect and scroll the mirrored app

The mirror SHALL help the operator inspect the hot app's UI and scroll it. Selecting a node in the semantic tree SHALL indicate that node's position on the screenshot, and picking a point on the screenshot SHALL select the corresponding node in the tree. The plugin SHALL show the selected node's details (such as text, role, available actions, and bounds). The operator SHALL be able to keep the mirror image refreshing automatically while it is open. The operator SHALL be able to scroll a scrollable node, and the plugin SHALL report whether the scroll was applied.

#### Scenario: Selecting a tree node highlights it on screen

- **WHEN** the operator selects a node in the semantic tree
- **THEN** the plugin marks that node's bounds on the screenshot

#### Scenario: Clicking the screenshot selects the node

- **WHEN** the operator picks a point on the screenshot over an element
- **THEN** the plugin selects the corresponding node in the semantic tree and shows its details

#### Scenario: Auto-refresh keeps the image current

- **WHEN** the operator enables auto-refresh on the mirror
- **THEN** the screenshot updates on its own while the mirror is open, and stops when disabled

#### Scenario: Scroll a node

- **WHEN** the operator invokes scroll on a scrollable node
- **THEN** the plugin dispatches the scroll to that node and reports whether it was applied
