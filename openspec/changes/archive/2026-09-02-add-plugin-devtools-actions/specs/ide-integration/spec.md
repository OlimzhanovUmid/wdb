## ADDED Requirements

### Requirement: Act on a semantic node of the mirrored app

From the mirror's semantic-tree view the plugin SHALL let the operator invoke an action on a chosen node — at least click, long-click, and set-text — and SHALL report whether it was applied. Set-text SHALL let the operator supply the text to enter. Actions target the node the operator selected, not merely whatever covers a point.

#### Scenario: Set text on a field

- **WHEN** the operator picks a text-input node in the semantic tree and chooses Set Text with a value
- **THEN** the plugin sends a set-text action to that node and the hot app's field shows the entered text

#### Scenario: Act on a node from the tree

- **WHEN** the operator double-clicks a node in the semantic tree (or picks Click/Long Click from its menu)
- **THEN** the plugin dispatches that action to the node and reports whether it was applied

#### Scenario: Unsupported action is reported, not silent

- **WHEN** an action cannot be applied (the node does not support it, or the agent is too old to understand it)
- **THEN** the plugin reports the failure rather than appearing to succeed
