## Why

Registering the wdb MCP server today is a manual, error-prone step: the user must build the launcher with `:wdb-mcp:installDist` and hand-edit their MCP client config (`claude mcp add wdb -- <path>`), which bit us with shell path mangling and only works on a machine that has the repo checked out and built. The plugin already lives in the IDE where developers work — it should offer to install and register the MCP server for them, using a prebuilt release so no local build is required.

## What Changes

- Publish the `wdb-mcp` distribution (`:wdb-mcp:distZip`) as a downloadable GitHub Release asset via a new tag-triggered release workflow.
- Add a plugin action ("Install wdb MCP server") that:
  - downloads the latest `wdb-mcp-*.zip` release asset and unzips it to a stable, repo-independent location (`~/.wdb/mcp/`);
  - registers the unzipped launcher with the user's MCP client at **user scope** so it is visible from every project;
  - lets the user **choose the registration method each time** — directly edit `~/.claude.json`, or run `claude mcp add` — and shows all warnings first (missing JDK, an existing `wdb` entry that would be overwritten, resolved launcher path);
  - never blindly corrupts config: safe write with a copy-to-clipboard / open-file fallback, mirroring the signature-exclude action.
- Warn when no JDK is discoverable, since the launcher `.bat` invokes `java`.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ide-integration`: add a requirement for a one-click action that installs and registers the wdb MCP server from a published release, with user-chosen registration method and safe fallbacks.
- `mcp-server`: add a requirement that the server ships as a downloadable, versioned release distribution so it can be installed without the repo.

## Impact

- **New**: `.github/workflows/release.yml` (tag `v*` → `:wdb-mcp:distZip` → upload asset); plugin install action + config-writer helper (mirrors `GradleSignatureExclude`), wired into the tool-window toolbar (`WallUi`) / `WdbService`.
- **Existing**: `wdb-mcp` build already applies the Gradle `application` plugin, so `distZip` is available with no build change.
- **User env**: requires network access to GitHub Releases at install time and a JDK 21 on PATH/`JAVA_HOME` at MCP run time; both are surfaced as warnings, not silent failures.
- **Config touched**: the user's `~/.claude.json` (user-scope `mcpServers.wdb`) — only via the user's explicitly chosen method.
