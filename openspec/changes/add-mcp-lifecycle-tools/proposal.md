## Why

An AI agent drives the wall through the MCP server, not the CLI — but the MCP toolset is narrower than the CLI, so an agent can't fully control a machine. It can run/hot-run/stop/reload/deploy and inspect, but it can't **restart**, **rollback** a bad deploy, or **update the agent**. Closing that gap lets an agent recover and maintain a machine autonomously, matching the CLI/plugin.

## What Changes

- Add MCP tools mirroring the remaining CLI lifecycle ops:
  - `restart` — restart the app on a machine.
  - `rollback` — roll back to the previous deployment.
  - `agent_update` — update a machine's agent from the latest published GitHub release (pull `latest.json`, download + verify the agent installer, push over the existing agent-update wire). Idempotent: if the machine is already on the latest version, it reports so and does not push.
- `restart`/`rollback` reuse `WdbClient.restart`/`rollback`; `agent_update` reuses `WdbClient.agentUpdate` and the shared `parseReleaseManifest`/`isNewerVersion` from wdb-client, with a small stdlib (`java.net.http`) downloader in wdb-mcp (no IntelliJ dependency).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `mcp-server`: add a requirement for lifecycle parity — `restart` and `rollback` tools, and an `agent_update` tool that updates a machine's agent from the published release.

## Impact

- **wdb-mcp**: three new `addTool` registrations + internal testable `tool*` functions; a small `java.net.http` release downloader for `agent_update`.
- **wdb-client**: reused unchanged (`restart`/`rollback`/`agentUpdate`, `parseReleaseManifest`/`isNewerVersion`).
- **No wire/agent change.** `agent_update` needs the MCP host machine to reach GitHub (it downloads there, then pushes over the LAN to the agent) — same plugin-mediated-pull model as `agent-github-pull`.
- **Non-goals**: `debug`/JDWP tunnel (not agent-relevant) and `push`-from-source (needs a Gradle build, not appropriate for the MCP server) stay CLI/plugin-only.
