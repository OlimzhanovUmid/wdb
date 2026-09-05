# wdb-mcp

An [MCP](https://modelcontextprotocol.io) server that exposes the demo wall to an AI agent
over stdio. It embeds `WdbClient`, so an MCP client (Claude Code, Claude Desktop, …) launches
it as a subprocess and drives the wall through its tools — the agent-facing counterpart of the
IDE plugin's mirror and lifecycle actions.

## Tools

| Tool | Args | Result |
|---|---|---|
| `list_machines` | – | discovered machines: `name  host:port  APP_STATE` |
| `screenshot` | `machine` | the machine's screen as PNG image content |
| `semantic_tree` | `machine` | the app's semantic tree (JSON text) |
| `ui_action` | `machine, nodeId, kind` (+ `text`/`dx`/`dy`/`index`) | dispatches `click`/`long_click`/`set_text`/`scroll_by`/`scroll_to_index`; reports applied |
| `status` | `machine` | full status: app state, hot mode, desired, jdwp, uptime, restarts, deployed/previous sha, main class, agent/runtime versions |
| `run` / `hot_run` / `stop` | `machine` | lifecycle; `hot_run` starts in Compose hot-reload mode |
| `reload` | `machine, classesDir` | hot-reloads compiled classes from a dev-side dir into a hot app |
| `deploy` | `machine, jarPath` (+ `restart?`, `mainClass?`) | push an already-built jar and restart (no Gradle build); `Main-Class` from the jar manifest unless `mainClass` given |
| `logs` | `machine` (+ `lines`, default 200) | one-shot tail of the machine's logs |

**Resources:** `wdb://logs/{machine}` — a streaming logs resource. Read it for the recent tail; while
you observe it the server sends resource-updated notifications as new lines arrive (re-read for the
fresh tail). The collector starts on first read and stops when the session closes.

`screenshot` / `semantic_tree` / `ui_action` need the target app running in **hot-reload mode**
(`hot_run`); when devtools are unavailable the tool returns an error result, not empty content.
Machine addresses are cached briefly (a `list_machines` call refreshes the cache) so repeated
machine-addressed calls don't each re-run discovery.

## Build

```
./gradlew :wdb-mcp:installDist
```

This produces a launcher at `wdb-mcp/build/install/wdb-mcp/bin/wdb-mcp` (`.bat` on Windows).

## Register with an MCP client

Point your MCP client at the launcher. For Claude Code:

```
claude mcp add wdb -- <repo>/wdb-mcp/build/install/wdb-mcp/bin/wdb-mcp
```

(on Windows use the `wdb-mcp.bat` launcher). The server talks to the same agent TCP/UDP
protocol as the CLI and plugin, so the wall machines must be reachable on the LAN.

## Scope

stdio transport only; no `deploy` (needs a Gradle build — a plugin/CLI concern), no HTTP/SSE,
no auth (inherits the wall's open-agent model). See `openspec/changes/archive/*-add-wdb-mcp`.
