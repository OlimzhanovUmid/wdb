## Why

The `wdb` CLI is only available by building `:wdb-cli:installDist` from the repo. With `add-release-pipeline` publishing `wdb-cli-<ver>.zip`, the plugin can install and update the CLI for the user from GitHub — the same one-click pattern as the MCP install — so a developer gets a working `wdb` on PATH without touching Gradle.

> Depends on `add-release-pipeline` (publishes `wdb-cli-<ver>.zip` + `latest.json`). Mirrors `add-plugin-mcp-install`'s download/install/fallback pattern.

## What Changes

- Plugin action "Install wdb CLI": download the latest `wdb-cli-<ver>.zip` (verify sha256+size from `latest.json`), unzip to a stable per-user location (`~/.wdb/cli/`), and make `wdb` runnable.
- Offer to add the launcher dir to PATH (or copy the path/instructions to clipboard as a safe fallback), and warn if no JDK is discoverable (CLI is JVM distZip → needs Java at run time), consistent with the MCP install's warnings-first + safe-write approach.
- Update-in-place: if a CLI is already installed, compare against `latest.json` and offer to update; idempotent when already current.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ide-integration`: add a requirement for a one-click action that installs/updates the wdb CLI from the published release, with a JDK warning, a PATH affordance, and a safe clipboard fallback.

## Impact

- **Plugin**: install/update action + reuse of the download/unzip/JDK-probe/fallback helpers from `add-plugin-mcp-install` (candidate to factor a shared `ReleaseInstall` helper).
- **User env**: needs network access at install time and a JDK 21 at run time — both surfaced as warnings.
- **Non-goal**: native/jpackage CLI (decided: distZip JVM); PATH edits are opt-in, never silent.
