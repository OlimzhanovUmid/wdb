## Why

The plugin lets a human drive the demo wall from the IDE. An AI agent can't — there's no machine interface. Compose Hot Reload already ships an MCP server for a single local app; wdb has the same building blocks (discovery, screenshot, semantic tree, UI actions, lifecycle) but for a whole LAN of remote machines. Exposing them over MCP lets an agent (Claude Code, etc.) discover a wall machine, see its screen, read its UI, tap/type/scroll, and run/hot-reload it — the devtools mirror, but agent-driven.

## What Changes

- A new **`wdb-mcp`** module: a standalone executable that speaks MCP over stdio and embeds `WdbClient`, so an MCP client launches it as a subprocess and talks to the wall.
- Tools:
  - `list_machines` — discover machines (name, address, app state, hot).
  - `screenshot` — the machine's screen as an image (agent sees it).
  - `semantic_tree` — the app's semantic tree (JSON) for inspection/targeting.
  - `ui_action` — click / long-click / set-text / scroll a semantic node.
  - `run` / `hot_run` / `stop` / `reload` — lifecycle on a machine.
  - `logs` — the tail of a machine's logs.
- Built on the official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk-server`), the same one CHR's mcp uses.

Out of scope: `deploy` (needs a Gradle build + jar — a plugin/CLI concern, not a headless MCP); serving over HTTP (stdio only for v1); auth (inherits the wall's current open-agent model).

## Capabilities

### New Capabilities

- `mcp-server`: an MCP interface that lets an AI agent discover, inspect, drive, and hot-reload demo-wall machines.

### Modified Capabilities

_None._

## Impact

- **Code:** new module `wdb-mcp` (Kotlin/JVM application) depending on `wdb-client` + the Kotlin MCP SDK; version catalog gains the SDK.
- **Packaging:** an `installDist`/executable like `wdb-cli`; the user registers its launcher with their MCP client (e.g. `claude mcp add wdb -- <launcher>`).
- **Runtime:** talks to the same agent TCP/UDP protocol as the CLI/plugin; devtools tools need the target app in hot mode (as in the plugin).
