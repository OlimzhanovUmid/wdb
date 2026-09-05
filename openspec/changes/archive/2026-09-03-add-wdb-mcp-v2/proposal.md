## Why

v1 of the wdb MCP server proved out agent-driven control of the wall. Two rough edges remain: every
tool call that addresses a machine re-runs a ~1.5s UDP discovery (so a screenshot-then-tap-then-tap
sequence pays it repeatedly), and the agent can inspect and drive a machine but can neither read its
full status nor put a new build on it — the two things `wdb status` / `wdb push` give a human.

## What Changes

- **Discovery cache**: the server caches discovered machines (name → address) with a short TTL.
  `list_machines` forces a fresh discovery and repopulates the cache; every other machine-addressed
  tool resolves the target from the cache and passes its address to the client, doing a one-shot
  discovery only on a cache miss or when the entry is stale. This removes the per-call discovery
  latency without changing what the tools do.
- **`status` tool**: `{machine}` → the machine's full status (app state, hot-reload mode, desired
  state, JDWP port, uptime, restart count, last exit, deployed/previous sha, main class, agent and
  runtime versions).
- **`deploy` tool**: `{machine, jarPath, restart?, mainClass?}` → push an already-built jar to the
  machine and (by default) restart it, reporting the deployed sha / outcome. The `Main-Class` is
  read from the jar's manifest unless `mainClass` is given. No Gradle build — the jar must already
  exist on the machine running the MCP server (as with the CLI).
- **Streaming logs resource**: a per-machine MCP resource `wdb://logs/{machine}`. Reading it returns
  the machine's recent log lines; while a client is observing it, the server pushes resource-updated
  notifications as new lines arrive (coalesced), so the client re-reads a fresh tail. The bounded
  `logs` tool stays for one-shot reads. (SDK 0.13 has no subscribe hook, so the per-machine log
  collector starts on first read; it is cancelled when the client session closes.)

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mcp-server`: adds a cached-discovery behavior for machine resolution, a machine-status tool, a
  deploy tool for pushing a prebuilt jar, and a streaming per-machine logs resource.

## Impact

- **Code:** `wdb-mcp` only. A small cache holding `name → address` with a timestamp; new `status` and
  `deploy` tool handlers (extracted like the v1 `tool*` funcs so the smoke test can cover them);
  reuse of `WdbClient.status` / `WdbClient.push` and a manifest `Main-Class` read; a logs-resource
  template with a per-(session, machine) collector, a bounded ring buffer, and coalesced
  resource-updated pushes, cleaned up on session close. Advertises `resources` (subscribe) capability.
  No new dependencies.
- **Protocol/agent/client:** unchanged — `WdbClient` already exposes `status`, `push`, `logs`, and
  host-addressed calls.
- **Deferred:** nothing from the v2 shortlist. (Streaming is now in scope; SDK 0.13 lacks a subscribe
  hook so the collector starts on first read — noted in design.)
