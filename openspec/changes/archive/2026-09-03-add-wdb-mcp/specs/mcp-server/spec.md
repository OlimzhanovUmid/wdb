## Purpose

Exposes the demo wall to an AI agent over the Model Context Protocol: discover machines, see and inspect a machine's screen, drive its UI, and run/hot-reload it — the agent-facing counterpart of the IDE plugin's mirror and lifecycle actions.

## ADDED Requirements

### Requirement: MCP server over stdio

The wdb MCP server SHALL run as a standalone process that speaks the Model Context Protocol over stdio, so an MCP client can launch it as a subprocess and call its tools. It SHALL embed the wdb client and reach machines over the same agent protocol the CLI and plugin use.

#### Scenario: Client lists the tools

- **WHEN** an MCP client connects to the wdb MCP server
- **THEN** the server advertises its machine, inspection, interaction, and lifecycle tools

### Requirement: Discover and inspect machines via tools

The server SHALL provide tools to list the discovered machines and, for a named machine, to return its screen as an image and its UI semantic tree. Inspection tools SHALL report a clear error when the machine is unreachable or its app is not running in hot-reload mode, rather than returning empty or misleading data.

#### Scenario: List machines

- **WHEN** the agent calls the list-machines tool
- **THEN** the server returns the discovered machines with their name, address, and app state

#### Scenario: Screenshot returns an image

- **WHEN** the agent calls the screenshot tool for a hot machine
- **THEN** the server returns that machine's current screen as image content

#### Scenario: Inspect reports unavailability

- **WHEN** the agent requests a screenshot or semantic tree for a machine whose app is not in hot-reload mode
- **THEN** the server returns an error saying devtools are unavailable, not empty content

### Requirement: Drive and run machines via tools

The server SHALL provide tools to interact with a machine's UI (click, long-click, set-text, scroll a semantic node) and to control its lifecycle (run, hot-run, stop, reload) and read its recent logs. Each tool SHALL report whether the operation succeeded.

#### Scenario: Interact with a node

- **WHEN** the agent calls the ui-action tool with a machine, a node id, and an action
- **THEN** the server dispatches that action to the node and reports whether it was applied

#### Scenario: Control lifecycle

- **WHEN** the agent calls run, hot-run, stop, or reload for a machine
- **THEN** the server performs that operation and reports the outcome

#### Scenario: Read logs

- **WHEN** the agent requests a machine's logs
- **THEN** the server returns the recent log lines for that machine
