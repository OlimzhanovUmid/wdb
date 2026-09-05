# mcp-server Specification

## Purpose
Exposes the demo wall to an AI agent over the Model Context Protocol: discover machines, see and inspect a machine's screen, drive its UI, and run/hot-reload it — the agent-facing counterpart of the IDE plugin's mirror and lifecycle actions.

## Requirements

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

### Requirement: Cached machine resolution

The server SHALL cache discovered machines (name to address) so that machine-addressed tool calls do
not each incur a full network discovery. The list-machines tool SHALL perform a fresh discovery and
repopulate the cache. Any other tool that names a machine SHALL resolve it from the cache when the
cache is fresh, performing a discovery only when the entry is missing or the cache has expired. This
SHALL NOT change what any tool returns — only the latency of resolving the target.

#### Scenario: Repeated tool calls reuse the cache

- **WHEN** the agent lists machines and then calls several machine-addressed tools within the cache lifetime
- **THEN** the server resolves those machines from the cache without repeating discovery for each call

#### Scenario: Unknown machine triggers a discovery

- **WHEN** the agent names a machine that is not in the cache or the cache has expired
- **THEN** the server performs a discovery to resolve it, and reports a clear error if it still cannot be found

### Requirement: Report a machine's status

The server SHALL provide a tool that returns a named machine's full status, including its app state,
whether it is running in hot-reload mode, its desired state, its debug (JDWP) port, uptime, restart
count, last exit, deployed and previous build identifiers, main class, and agent and runtime
versions. It SHALL report a clear error when the machine cannot be reached.

#### Scenario: Status of a machine

- **WHEN** the agent calls the status tool for a reachable machine
- **THEN** the server returns that machine's app state, hot-reload mode, desired state, debug port, uptime, deployed build, and versions

#### Scenario: Status of an unreachable machine

- **WHEN** the agent calls the status tool for a machine that cannot be reached
- **THEN** the server returns an error rather than empty or fabricated status

### Requirement: Deploy a prebuilt jar to a machine

The server SHALL provide a tool that deploys an already-built application jar to a named machine and,
by default, restarts the app, reporting the deployed build identifier and whether it succeeded. The
application's main class SHALL be taken from the jar's manifest unless the caller supplies one. The
tool SHALL NOT build the jar; it operates on a jar that already exists where the server runs, and
SHALL report a clear error when the jar path is missing or has no resolvable main class. Whether the
app restarts SHALL be controllable by the caller.

#### Scenario: Deploy and restart

- **WHEN** the agent calls the deploy tool with a machine and a path to a built jar
- **THEN** the server pushes that jar to the machine, restarts the app by default, and reports the deployed build identifier

#### Scenario: Missing jar or main class

- **WHEN** the agent calls the deploy tool with a jar path that does not exist, or a jar with no manifest main class and no supplied main class
- **THEN** the server returns an error and does not report a successful deploy

### Requirement: Stream a machine's logs as a resource

The server SHALL expose a per-machine logs resource that an MCP client can read to obtain the
machine's recent log lines, in addition to the one-shot logs tool. While a client is observing a
machine's logs resource, the server SHALL notify the client that the resource has changed as new log
lines arrive, so the client can obtain the updated tail; the resource SHALL return a bounded number
of recent lines rather than unbounded history. The server SHALL stop collecting a machine's logs when
the client session ends, so no collection continues after disconnect.

#### Scenario: Read a machine's logs resource

- **WHEN** the agent reads the logs resource for a machine
- **THEN** the server returns that machine's recent log lines as the resource's content

#### Scenario: Updates as new lines arrive

- **WHEN** the agent is observing a machine's logs resource and the app emits new log lines
- **THEN** the server notifies the client that the resource has updated so it can read the newer tail

#### Scenario: Collection stops on disconnect

- **WHEN** the client session that was observing a logs resource ends
- **THEN** the server stops collecting that machine's logs

### Requirement: Bring a machine's app window to the front

The server SHALL provide a tool that raises the running app's window to the foreground on a named
machine, reporting whether it was raised. It SHALL report a clear result when the machine is unreachable,
no app is running, or no window is found.

#### Scenario: Raise via the tool

- **WHEN** the agent calls the bring-to-front tool for a machine whose app is running
- **THEN** the server raises that app's window on the machine and reports success

#### Scenario: Nothing to raise

- **WHEN** the agent calls the bring-to-front tool and no app is running (or no window is found)
- **THEN** the server reports that there was no window to raise rather than a false success
