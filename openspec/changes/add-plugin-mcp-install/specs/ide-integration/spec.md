## ADDED Requirements

### Requirement: One-click install of the wdb MCP server

The plugin SHALL provide an action that installs and registers the wdb MCP server for the user's MCP client without requiring the user to build the project or hand-edit configuration. The action SHALL obtain the server as a prebuilt release distribution, place it in a stable location that does not depend on the repository checkout, and register it at user scope so it is visible from every project. The action SHALL run off the UI thread and report a clear success or failure outcome.

#### Scenario: Install downloads and registers the server

- **WHEN** the operator invokes the install-MCP action
- **THEN** the plugin fetches the latest published wdb-mcp release distribution, places the launcher in a stable per-user location, registers it with the user's MCP client at user scope, and reports success with the registered launcher path

#### Scenario: Already installed is idempotent

- **WHEN** the operator invokes the install-MCP action and a `wdb` MCP entry already points at a valid launcher
- **THEN** the plugin does not silently overwrite it without consent — it reports that `wdb` is already registered and requires the operator to confirm before replacing the entry

### Requirement: Operator chooses the registration method with warnings shown first

Before writing any configuration, the plugin SHALL let the operator choose how the server is registered — either by directly editing the user MCP client config file, or by delegating to the MCP client's own registration command — and SHALL present all relevant warnings first, including a missing Java runtime, an existing `wdb` entry that would be overwritten, and the resolved launcher path.

#### Scenario: Operator picks direct config edit

- **WHEN** the operator chooses to register by editing the config file directly
- **THEN** the plugin writes a user-scope `wdb` MCP entry pointing at the installed launcher, preserving the rest of the config

#### Scenario: Operator picks the client command

- **WHEN** the operator chooses to register via the MCP client's registration command and that command is available
- **THEN** the plugin runs it to add the user-scope `wdb` entry and reports its outcome

#### Scenario: Warnings are shown before any write

- **WHEN** the plugin is about to register the server and a JDK is not discoverable, or an existing `wdb` entry would be overwritten
- **THEN** the plugin surfaces those warnings to the operator before performing any write

### Requirement: Safe write with fallback

The install action SHALL never corrupt the user's MCP client configuration. When it cannot safely perform the chosen registration — the config is unreadable or unexpectedly shaped, or the client command is unavailable — it SHALL fall back to a non-destructive path: copy the exact registration command or entry to the clipboard and/or open the relevant file, so the operator can complete registration manually.

#### Scenario: Fallback when config cannot be written safely

- **WHEN** the plugin cannot safely apply the chosen registration method
- **THEN** it makes no partial edit, copies the registration command or entry to the clipboard, surfaces guidance to finish manually, and reports the fallback outcome
