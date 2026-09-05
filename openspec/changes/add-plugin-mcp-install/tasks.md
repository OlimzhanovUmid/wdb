## 1. Release distribution (CI)

- [ ] 1.1 Add `.github/workflows/release.yml`: on push of tag `v*`, set up JDK 21, run `./gradlew :wdb-mcp:distZip`, upload `wdb-mcp/build/distributions/wdb-mcp-*.zip` as a Release asset. Verify by pushing a test tag and confirming the asset appears on the Release (or dry-run the job locally / via `act`), and that `distZip` produces the zip locally.
- [ ] 1.2 Confirm the produced archive layout is `wdb-mcp/bin/wdb-mcp.bat` + `wdb-mcp/lib/*.jar` and that running the extracted `.bat` starts the stdio MCP server (manual: unzip to a temp dir, run `.bat`, verify it speaks MCP / a client can list tools).

## 2. Install helper (McpInstall)

- [ ] 2.1 Create `wdb-plugin/.../McpInstall.kt` object with an `Outcome` enum (`INSTALLED`, `ALREADY`, `FALLBACK`, `CANCELLED`, `FAILED`); verify it compiles (`./gradlew :wdb-plugin:compileKotlin`).
- [ ] 2.2 Implement latest-release resolution + download via IntelliJ `HttpRequests` against `repos/OlimzhanovUmid/windows-debug-bridge/releases/latest`, selecting the `wdb-mcp-*.zip` asset; handle no-release / no-asset / network errors as typed failures. Verify with a unit test over a captured/mocked releases JSON (asset match + each error branch).
- [ ] 2.3 Implement unzip to `~/.wdb/mcp/`, replacing any existing `wdb-mcp/` subtree, resolving the launcher at `~/.wdb/mcp/wdb-mcp/bin/wdb-mcp.bat`. Verify with a unit test unzipping a small fixture zip and asserting the launcher path exists and stale files are gone.
- [ ] 2.4 Implement JDK probe (`JAVA_HOME` then `java` on PATH) and existing-`wdb`-entry detection from `~/.claude.json`. Verify with unit tests: JDK present/absent, entry present/absent.

## 3. Registration methods

- [ ] 3.1 Implement direct `~/.claude.json` edit: parse JSON, set user-scope `mcpServers.wdb` to `{type:stdio, command:<launcher, forward slashes>, args:[], env:{}}`, preserve all other keys, create a minimal file if missing; on unreadable/unexpected shape do NOT write. Verify with unit tests: fresh file, file with other servers preserved, malformed file → no write.
- [ ] 3.2 Implement `claude mcp add wdb -s user -- <launcher>` process path, offered only when `claude` resolves on PATH; map non-zero exit to fallback. Verify by asserting the constructed command/args and the PATH-availability gate (unit) and one manual end-to-end run.
- [ ] 3.3 Implement the clipboard + open-file fallback (copy the exact command/entry, open `~/.claude.json`) returning `FALLBACK`. Verify with a unit test that the fallback triggers on a forced write failure and the clipboard payload is correct.

## 4. UI wiring

- [ ] 4.1 Add "Install wdb MCP server" to the tool-window toolbar in `WallUi` routed through `WdbService`; download/unzip run under a background task with progress, the method+warnings dialog and fallbacks on EDT. Verify in `runIde`: action visible, dialog shows warnings (JDK, existing entry, launcher path) before any write.
- [ ] 4.2 Implement idempotency: when `wdb` already points at a valid launcher, report ALREADY and require explicit confirm before overwrite. Verify in `runIde`: second invocation shows the already-registered confirm.

## 5. Verification

- [ ] 5.1 Live end-to-end in `runIde`: invoke install with no prior `wdb` entry → downloads latest release, installs to `~/.wdb/mcp/`, registers via the chosen method, and a fresh Claude Code session lists the `wdb` MCP tools. Confirm the same works from a project other than this repo (user scope).
- [ ] 5.2 Run `openspec validate add-plugin-mcp-install --strict` and `./gradlew :wdb-plugin:build`; both pass.
