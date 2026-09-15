<!-- Reconciled 2026-09-15: the release pipeline (add-release-pipeline) shipped, so the original
     "Release distribution (CI)" group is dropped — wdb-mcp-<ver>.zip is already published and listed
     in latest.json. Download now reuses the plugin's ReleaseSource (added by agent-github-pull),
     which reads latest.json and downloads+verifies a component. Repo is `wdb`. Design D1/D3
     (custom release.yml + REST-API asset glob against `windows-debug-bridge`) are superseded. -->

## 1. Claude MCP config edit (wdb-client, pure + testable)

- [x] 1.1 Add `McpConfig.kt` to wdb-client (it owns kotlinx-serialization for the plugin): `upsertUserMcpServer(configJson: String?, name: String, command: String): String` — parse `~/.claude.json`, set user-scope `mcpServers.<name>` to `{type:stdio, command, args:[], env:{}}`, preserve all other keys, create a minimal object when the input is null/blank; throw on unreadable/unexpected shape (caller falls back). Add `mcpServerCommand(configJson, name): String?` to detect an existing entry's command. Verify with unit tests: fresh, preserve-others, malformed→throws, detect present/absent.

## 2. Install helper (McpInstall, wdb-plugin)

- [x] 2.1 Create `wdb-plugin/.../McpInstall.kt` object with an `Outcome` enum (`INSTALLED`, `ALREADY`, `FALLBACK`, `CANCELLED`, `FAILED`). Verify it compiles.
- [x] 2.2 Download the `mcp` component via `ReleaseSource` (`latestManifest()["mcp"]` → `downloadVerified`); typed failures for no-manifest / no-mcp-entry / integrity. Unzip to `~/.wdb/mcp/`, replacing any existing `wdb-mcp/` subtree; resolve the launcher at `~/.wdb/mcp/wdb-mcp/bin/wdb-mcp.bat`. Verify with a unit test unzipping a small fixture zip (launcher path exists, stale files gone).
- [x] 2.3 JDK probe (`JAVA_HOME` then `java` on PATH) and existing-`wdb`-entry detection from `~/.claude.json` (via `mcpServerCommand`). Verify with unit tests: JDK present/absent, entry present/absent.

## 3. Registration methods

- [x] 3.1 Direct `~/.claude.json` edit via `upsertUserMcpServer` (forward-slash launcher path); write back preserving other keys; create minimal if missing; on parse/shape failure do NOT write → fallback. Covered by the wdb-client 1.1 tests + a plugin-side write test.
- [x] 3.2 `claude mcp add wdb -s user -- <launcher>` process path, offered only when `claude` resolves on PATH; map non-zero exit to fallback. Verify by asserting the constructed command/args + the PATH-availability gate.
- [x] 3.3 Clipboard + open-file fallback (copy the exact command/entry, open `~/.claude.json`) returning `FALLBACK`.

## 4. UI wiring

- [x] 4.1 Add "Install wdb MCP server" to the tool-window toolbar in `WallUi` routed through `WdbService`; download/unzip run off-EDT with progress, the method+warnings dialog and fallbacks on EDT. The dialog shows warnings first (missing JDK, existing `wdb` entry to be overwritten, resolved launcher path), then the method choice (edit config / run `claude mcp add`). Verify in `runIde`: action visible, dialog shows warnings before any write.
- [x] 4.2 Idempotency: when `wdb` already points at a launcher, report ALREADY and require explicit confirm before overwrite. Verify in `runIde`: second invocation shows the already-registered confirm.

## 5. Verification

- [ ] 5.1 Live end-to-end in `runIde`: invoke install with no prior `wdb` entry → downloads the published `wdb-mcp` release, installs to `~/.wdb/mcp/`, registers via the chosen method, and a fresh Claude Code session lists the `wdb` MCP tools. Confirm it works from a project other than this repo (user scope).
- [x] 5.2 Run `openspec validate add-plugin-mcp-install --strict` and `./gradlew :wdb-plugin:build`; both pass.
