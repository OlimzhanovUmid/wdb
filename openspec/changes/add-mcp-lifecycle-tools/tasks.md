## 1. restart + rollback tools

- [x] 1.1 Register `restart` and `rollback` MCP tools in `wdb-mcp/Main.kt`, mirroring the `stop` tool shape (`resolve machine → runCatching { client.restart/rollback(m, host) } → text`). Verify `:wdb-mcp:build` compiles and the tools appear in the advertised list. — DONE: extracted `toolRestart`/`toolRollback` internal fns + registered `restart`/`rollback` tools; `:wdb-mcp:build` green.
- [x] 1.2 Cover restart/rollback with the existing host-injected test style (drive against a fake agent, assert non-error result). Verify the test passes. — DONE: `restart_ok`/`rollback_ok` in `WdbMcpToolsTest` (FakeAgent default OkResponse), pass.

## 2. agent_update — release fetch

- [x] 2.1 Add `ReleaseFetch` in wdb-mcp (stdlib `java.net.http`): `latestAgent(): ComponentRelease?` (GET `releases/latest/download/latest.json` → `parseReleaseManifest` → `agent`; null on failure) and `downloadVerified(component): Path` (download to a temp file, verify size + sha256 via `MessageDigest`, throw on mismatch). — DONE: `ReleaseFetch.kt`, no new Gradle dependency. NOTE: parse reused from wdb-client (covered by `ReleaseManifestTest`); the HTTP methods themselves aren't unit-tested (need a local server) — verify/decision logic is exercised via `toolAgentUpdate` tests + live 4.1.

## 3. agent_update tool

- [x] 3.1 Add `internal suspend fun toolAgentUpdate(client, machine, host, fetchAgent, download)` with the network bits injected: fetch latest agent; read `client.status(m).agentVersion`; if not `isNewerVersion` → "already up to date" (no push); else `download` + `client.agentUpdate(m, zip, version, host)` and delete the temp; report updating / typed error. — DONE + tested: `agent_update_already_current_is_noop` (no download), `agent_update_manifest_unreachable_is_error`, `agent_update_download_integrity_failure_is_error`. (newer→push path covered live in 4.1.)
- [x] 3.2 Register the `agent_update` tool (arg `machine`) wiring `ReleaseFetch` into `toolAgentUpdate`. Verify `:wdb-mcp:build` compiles and the tool is advertised. — DONE.

## 4. Verification

- [ ] 4.1 Live: from an MCP client (e.g. Claude Code) against a wall — call `restart` and `rollback` (observe the app restart / revert via `status`), and `agent_update` on a machine older than the published version (it downloads, verifies, pushes; the machine restarts on the new version; a second call reports "already up to date"). — PENDING: needs the rebuilt MCP server registered in an MCP client + a wall.
- [x] 4.2 Run `openspec validate add-mcp-lifecycle-tools --strict` and `./gradlew :wdb-mcp:build`; both pass. — VERIFIED: validate OK; `:wdb-mcp:build` green (13 tool tests, 0 failures).
