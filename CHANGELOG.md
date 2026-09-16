# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project follows Semantic
Versioning (pre-1.0, so minor versions may include breaking changes).

## [0.1.2]

### Fixed
- Agent self-update now uses the raw app-image zip. The wrapped installer zip nested
  `wdb-agent.exe` one directory too deep, so the launcher could not find the new version,
  it never booted, and the watchdog reverted to the previous version after 60 seconds.
- Agent update is single-flight in the plugin. Two concurrent pushes raced the agent's
  self-update and corrupted it.

### Added
- MCP server: `restart`, `rollback`, and `agent_update` tools, bringing the agent-facing
  toolset to parity with the CLI.
- Plugin: one-click "Install wdb MCP server" that registers the server with Claude Code
  (direct config edit or `claude mcp add`), with warnings shown first and a clipboard fallback.

## [0.1.1]

### Added
- Plugin: update a machine's agent from the published GitHub release — download, verify, and
  push over the existing wire; an "update available" badge and an "Update agent" action.

## [0.1.0]

### Added
- Initial public release.
- CLI, IntelliJ / Android Studio plugin, MCP server, and Windows agent.
- Deploy, run, hot-run (Compose hot-reload), stop, restart, rollback; screenshot, semantic
  tree, and UI actions; log streaming; JDWP debug attach; bring the app window to front.
- Release pipeline: pushing a `v*` tag publishes the agent, CLI, MCP, and plugin, plus
  `latest.json` and `updatePlugins.xml` at the stable `releases/latest/download` URLs.
