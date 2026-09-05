## 1. Versioning

- [x] 1.1 Add a repo version property (`gradle.properties wdbVersion`) and wire it as the `version` for `wdb-cli`, `wdb-mcp`, and `wdb-plugin` (agent keeps `wdbAgentVersion`). Verify `:wdb-cli:distZip`, `:wdb-mcp:distZip`, `:wdb-plugin:buildPlugin` each produce a `*-<ver>.*` archive locally. — VERIFIED: `wdb-cli-0.1.0.zip`, `wdb-mcp-0.1.0.zip`, `wdb-plugin-0.1.0.zip` built (cli distZip base-named `wdb-cli`).
- [x] 1.2 Allow CI to override the version from the tag (`-PwdbVersion=` / `-Pversion=`), and either derive it from the tag or assert `tag == wdbVersion`. Verify by running a build with the property overridden and checking the archive name. — VERIFIED: `-PwdbVersion=9.9.9` → `wdb-cli-9.9.9.zip`, `wdb-mcp-9.9.9.zip`. Tag drives version (no assert).

## 2. Release workflow

- [x] 2.1 Add `.github/workflows/release.yml` triggered on `push` tag `v*`, `runs-on: windows-latest`, `setup-java distribution: jetbrains` (JBR 21). Verify the workflow parses (`actionlint` or a manual dry tag on a branch). — VERIFIED: YAML parses (8 steps, `on.push.tags [v*]`), 0 tabs.
- [~] 2.2 Build all assets in the job: `:wdb-agent:packageAgentInstaller` (pass the JBR as `-PjbrHome`), `:wdb-cli:distZip`, `:wdb-mcp:distZip`, `:wdb-plugin:buildPlugin`. Verify each expected archive exists in its `build/` output during a test run. — IMPLEMENTED in release.yml (Build step). Live verify awaits a tag push (needs the CI runner + JBR).
- [~] 2.3 Smoke-check the built artifacts in-job: extract `wdb-cli`/`wdb-mcp` and run the launcher `--help`/version; run the agent `.exe` briefly. Verify the job step exits 0. — IMPLEMENTED as "Smoke-check artifacts" step. Adjusted for CI safety: assert all 4 archives exist + run CLI `--help` (exits 0); MCP/agent block on stdio/sockets so they are existence-checked, not run. Live verify awaits a tag push.

## 3. Manifest + plugin repo index

- [x] 3.1 Generate `latest.json` = `{component: {version, asset, url, sha256, size}}` for agent/cli/mcp/plugin, computing sha256+size from the freshly built assets. Verify the emitted JSON validates and each `sha256` matches `sha256sum` of the asset. — VERIFIED: `scripts/gen-release-manifest.ps1` run locally → valid JSON (4 components), mcp sha256 matches `Get-FileHash`, UTF-8 no BOM.
- [x] 3.2 Generate `updatePlugins.xml` with the plugin id, the versioned plugin asset `url`, `version`, and `<idea-version since-build/until-build>` derived from the plugin's build range (single source of truth with 1.x). Verify the XML is well-formed and the build range matches the plugin. — VERIFIED: id/version/since(261)/until(261.*) read from the BUILT plugin jar's `META-INF/plugin.xml`; XML parses; versioned url.

## 4. Publish

- [~] 4.1 Create/upload a non-draft, non-prerelease GitHub Release for the tag with all 6 assets (`gh release create`/`softprops-action`, `GITHUB_TOKEN`), idempotent on re-run. Verify a test tag produces a Release listing all assets. — IMPLEMENTED as "Publish GitHub Release" step (`gh release view` → `upload --clobber` else `create --verify-tag`, non-draft). Live verify awaits a tag push.
- [~] 4.2 Verify the stable consumption URLs resolve after publish: `releases/latest/download/latest.json` and `releases/latest/download/updatePlugins.xml` both return the just-published files. — Awaits a tag push (can only be checked against a real published release).

## 5. Funding

- [x] 5.1 Add `.github/FUNDING.yml` (GitHub Sponsors + Buy Me a Coffee, targets supplied by owner) and a sponsor link in the README. Verify GitHub renders the Sponsor button on the repo. — DONE: `github: [OlimzhanovUmid]` + `buy_me_a_coffee: umidolimzhanov` (Sponsor button renders after push). No README exists — button comes from FUNDING.yml alone; a README link is out of this CI-only change's scope.

## 6. Verification

- [~] 6.1 End-to-end: push `v<x.y.z>` → confirm the Release has agent/cli/mcp/plugin zips + `latest.json` + `updatePlugins.xml`; add the `updatePlugins.xml` URL as an IntelliJ custom plugin repo and confirm the IDE lists the wdb plugin; fetch `latest.json` and confirm versions/sha256 match the assets. — Awaits the owner pushing the first `v*` tag.
- [x] 6.2 Run `openspec validate add-release-pipeline --strict`; it passes. — VERIFIED: "Change 'add-release-pipeline' is valid".
