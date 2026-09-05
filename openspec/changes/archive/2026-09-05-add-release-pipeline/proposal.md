## Why

Every wdb artifact today is distributed by building it locally: the plugin isn't published, the CLI/MCP are `installDist` on the dev's box, and agent updates are pushed from a locally-built jpackage zip. Nothing auto-updates and nothing can be installed without the repo. A single tag-triggered GitHub release that publishes all artifacts plus a machine-readable manifest is the foundation for auto-update, plugin-driven CLI install, and pulling agent updates from GitHub instead of a local build. This change lands only that foundation; the consumers are separate follow-on changes.

## What Changes

- Add a tag-triggered release workflow (`.github/workflows/release.yml`, on `push` of tag `v*`) that builds and uploads, as assets on one GitHub Release:
  - `wdb-agent-installer-<ver>.zip` (`:wdb-agent:packageAgentInstaller`, Windows, bundled JBR),
  - `wdb-cli-<ver>.zip` (`:wdb-cli:distZip`, cross-OS JVM launcher),
  - `wdb-mcp-<ver>.zip` (`:wdb-mcp:distZip`),
  - `wdb-plugin-<ver>.zip` (`:wdb-plugin:buildPlugin`),
  - `latest.json` — a component→`{version, asset, sha256, size}` manifest,
  - `updatePlugins.xml` — an IntelliJ custom-plugin-repository index whose inner `url` points at the versioned plugin zip.
- Consumers read the two index files at GitHub's stable `releases/latest/download/<asset>` URLs (no GitHub Pages, no API needed).
- Introduce repo-level versioning so the plugin/CLI/MCP assets are self-versioned (today only the agent has a version); the release version comes from the `v*` tag.
- Add `.github/FUNDING.yml` (GitHub Sponsors + Buy Me a Coffee) and a sponsor link, per `docs/monetization.md`.

## Capabilities

### New Capabilities
<!-- none — CI/tooling only, no runtime product behavior changes. skip_specs: true. -->

### Modified Capabilities
<!-- none -->

## Impact

- **New**: `.github/workflows/release.yml`, `.github/FUNDING.yml`; generated release assets (`latest.json`, `updatePlugins.xml`) + a small generator step/script in the workflow.
- **Build**: repo/module versioning wired for `wdb-cli`, `wdb-mcp`, `wdb-plugin` (agent already versioned via `wdbAgentVersion`); `buildPlugin` produces a versioned zip with `since/until-build` set for the target platform (261). No change to `wdb-mcp`/`wdb-cli` build beyond version — `application` plugin already gives `distZip`.
- **CI env**: release job runs on `windows-latest` (agent jpackage is Windows-only; all other artifacts build fine there); needs a JBR 21 for jpackage (`setup-java distribution: jetbrains`) and `GITHUB_TOKEN` to create the release.
- **Downstream (not in this change)**: `add-plugin-mcp-install` task 1.1 collapses to "consume the published MCP asset" once this lands; future changes `plugin-auto-update` (Marketplace + custom repo), `agent-github-pull` (plugin downloads agent zip → pushes over existing wire protocol), and `cli-install` all depend on this release + manifest.
- **No runtime behavior change** to any shipped component — this is packaging/CI only.
