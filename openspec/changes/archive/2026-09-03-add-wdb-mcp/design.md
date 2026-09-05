# Design — wdb MCP server

## Context

wdb-client already exposes everything the tools need: `discover()`, `screenshot()`, `semanticTree()`, `uiAction()`, `run/hotRun/stop/reload...`, and `logs()`. The only new thing is an MCP front end. The official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk-server:0.13.0`) is already on the dependency graph (pulled by CHR's mcp) and is what CHR's own `hot-reload-mcp` uses — so we build on it rather than hand-rolling JSON-RPC.

## Decisions

### D1 — Standalone stdio module, not plugin-hosted

`wdb-mcp` is its own Kotlin/JVM `application` module (like `wdb-cli`) that runs an MCP `Server` over `StdioServerTransport`. An MCP client (Claude Code/Desktop) launches its executable as a subprocess. This avoids hosting a server inside the GUI plugin (awkward lifecycle/transport) and matches how MCP servers are normally deployed.

### D2 — Tools map 1:1 to `WdbClient`

`main()` builds a `WdbClient` on a coroutine scope and registers tools whose handlers call it:
`list_machines`, `screenshot`, `semantic_tree`, `ui_action`, `run`, `hot_run`, `stop`, `reload`, `logs`. Machines are addressed by name; the handler resolves via discovery (host optional). Tool input schemas are small JSON objects (`{machine, ...}`); `ui_action` takes `{machine, nodeId, kind, text?, dx?, dy?, index?}` where `kind ∈ click|long_click|set_text|scroll_by|scroll_to_index`.

### D3 — Screenshot as image content

`screenshot` returns MCP `ImageContent` (base64 PNG from `client.screenshot`), so the agent can actually see the screen. `semantic_tree` returns text (the JSON). Interaction/lifecycle tools return a short text result (applied / outcome). Unavailable devtools (not hot / unreachable) → an `isError` tool result with a clear message, not empty content.

### D4 — Logs are bounded

`logs` returns the last N lines (default ~200): collect `client.logs(machine)` history with a short time budget, then return — MCP tool calls are request/response, not a stream. (A streaming resource can come later.)

### D5 — Packaging

`installDist` produces a launcher (`wdb-mcp`), like `wdb-cli`. The user registers it with their MCP client, e.g. `claude mcp add wdb -- <path>/wdb-mcp`. No jpackage/JBR bundle needed for v1 (runs on the dev machine's JVM); can add one later if useful.

## Non-Goals

`deploy` (needs a Gradle build); HTTP/SSE transport; auth; a live screenshot/logs streaming resource.

## Risks

- **SDK version drift** — pin `0.13.0` in the catalog; the API is young. Isolated to this module.
- **Devtools preconditions** — screenshot/tree/ui_action need the target app in hot mode (same as the plugin); surfaced as tool errors.
- **Discovery timing** — a tool call triggers discovery each time unless cached; acceptable for v1 (small LAN), can cache later.
