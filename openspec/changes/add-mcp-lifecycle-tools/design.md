## Context

See proposal.md. Anchors:

- `wdb-mcp/Main.kt` registers tools as `server.addTool(Tool(name=…, inputSchema=objSchema(...))) { req -> … }`; lifecycle tools follow a fixed shape: `req.str("machine")` → `cache.resolve(m)` (address, or "not found") → `runCatching { client.op(m, host) }.fold(success, error)` → `text(...)`. Inspection/status/deploy bodies are extracted to `internal suspend fun tool*` for host-injected unit tests (`WdbMcpToolsTest`).
- `WdbClient.restart(target, host)` and `rollback(target, host)` already exist (used by the CLI). `WdbClient.agentUpdate(target, zip, version, host, onProgress)` streams a local zip over the existing agent-update wire.
- `wdb-client` `parseReleaseManifest(json)` → `Map<String, ComponentRelease>` and `isNewerVersion(current, candidate)` already exist (from `agent-github-pull`). `ComponentRelease` carries `version/asset/url/sha256/size`.
- wdb-mcp is a plain JVM app (no IntelliJ), so it can't use the plugin's `HttpRequests`.

## Goals / Non-Goals

**Goals:**
- `restart`, `rollback` tools at parity with the CLI.
- `agent_update` tool that pulls the latest agent from the GitHub release, verifies it, and pushes it — idempotent, agent-friendly (no local file needed).

**Non-Goals:**
- `debug`/JDWP tunnel and `push`-from-source (Gradle) tools — out of scope for the MCP server.
- Per-byte download progress in the tool result (MCP tools return once; the push is fast on the LAN, the download is the long part but not streamed to the client).
- A shared HTTP abstraction across plugin + mcp — the plugin uses `HttpRequests`, mcp uses stdlib; keep them separate.

## Decisions

**D1 — `restart`/`rollback`: mirror the existing lifecycle tools verbatim.**
`resolve → runCatching { client.restart/rollback(m, host) } → text`. Extract `toolRestart`/`toolRollback` (or fold into the existing inline style) so they match the surrounding code; add `internal suspend fun` bodies if they carry any logic worth unit-testing (these are thin, so inline like `stop` is acceptable — match the file's own convention).

**D2 — `agent_update` pulls from the release (plugin-mediated-pull model).**
Tool arg: `machine` (required). Flow: fetch `latest.json` from the stable `releases/latest/download/latest.json` → `parseReleaseManifest` → `agent` entry. Read the machine's current agent version via `client.status(m, host).agentVersion`. If not `isNewerVersion(current, latest)` → return "already up to date" (no push). Else download the installer (`ComponentRelease.url`) to a temp file, verify size + sha256 against the manifest, then `client.agentUpdate(m, tempZip, latest.version, host)`; delete the temp file. Report updating/restarting or a typed error. *Alt:* accept a local `installerPath` like `deploy` — rejected as the primary path (a headless agent has no local zip); pulling latest is the useful default. A future `installerPath` override can be added if needed.

**D3 — Stdlib HTTP downloader in wdb-mcp (`java.net.http.HttpClient`).**
A small `ReleaseFetch` object in wdb-mcp: `latestAgent(): ComponentRelease?` (GET latest.json, parse, return `agent`) and `download(url, dest)` + a sha256/size verify (reuse `MessageDigest`). No new dependency — `java.net.http` is JDK. Manifest URL is a constant (same repo as the pipeline). Failures (network/parse/integrity) surface as `isError` tool results, never a bad push.

**D4 — Testability.**
Extract `toolAgentUpdate(client, machine, host, fetchAgent, download)` with the network bits injected (a `ComponentRelease?` supplier + a "download+verify → Path" function), so `WdbMcpToolsTest` can drive it against a fake agent + a stub manifest/file — covering: newer→push, equal→no-op, integrity-fail→error, unreachable-manifest→error. The thin `restart`/`rollback` follow the file's inline convention and are covered by the existing round-trip style if worth it.

## Risks / Trade-offs

- **MCP host has no internet / no release yet** → `agent_update` returns a clear "manifest unreachable" error; other tools unaffected. *Mitigation:* typed error result (D3).
- **Large agent installer (~159 MB) downloaded on the MCP host** → the tool call blocks for the download with no progress. *Mitigation:* accept for now (agents tolerate a slow tool call); could add a temp-file cache later (like the plugin's `ReleaseSource`).
- **Agent version string unknown (`?`)** → `isNewerVersion` returns false → no push; the agent-update tool reports "cannot determine current version / already up to date". Acceptable (no accidental downgrade).

## Open Questions

- None blocking. (A `version`/`installerPath` override for `agent_update` is a possible future addition, not needed for parity.)
