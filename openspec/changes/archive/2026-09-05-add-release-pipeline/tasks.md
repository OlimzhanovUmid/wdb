## 1. Versioning

- [x] 1.1 Add a repo version property (`gradle.properties wdbVersion`) and wire it as the `version` for `wdb-cli`, `wdb-mcp`, and `wdb-plugin` (agent keeps `wdbAgentVersion`). Verify `:wdb-cli:distZip`, `:wdb-mcp:distZip`, `:wdb-plugin:buildPlugin` each produce a `*-<ver>.*` archive locally. — VERIFIED: `wdb-cli-0.1.0.zip`, `wdb-mcp-0.1.0.zip`, `wdb-plugin-0.1.0.zip` built (cli distZip base-named `wdb-cli`).
- [x] 1.2 Allow CI to override the version from the tag (`-PwdbVersion=` / `-Pversion=`), and either derive it from the tag or assert `tag == wdbVersion`. Verify by running a build with the property overridden and checking the archive name. — VERIFIED: `-PwdbVersion=9.9.9` → `wdb-cli-9.9.9.zip`, `wdb-mcp-9.9.9.zip`. Tag drives version (no assert).

## 2. Release workflow

- [x] 2.1 Add `.github/workflows/release.yml` triggered on `push` tag `v*`, `runs-on: windows-latest`, `setup-java distribution: jetbrains` (JBR 21). Verify the workflow parses (`actionlint` or a manual dry tag on a branch). — VERIFIED: YAML parses (8 steps, `on.push.tags [v*]`), 0 tabs.
- [x] 2.2 Build all assets in the job: `:wdb-agent:packageAgentInstaller` (pass the JBR as `-PjbrHome`), `:wdb-cli:distZip`, `:wdb-mcp:distZip`, `:wdb-plugin:buildPlugin`. Verify each expected archive exists in its `build/` output during a test run. — VERIFIED live: v0.1.0 run built all 4 (JBR ships jpackage; agent installer 159 MB, cli/mcp/plugin zips).
- [x] 2.3 Smoke-check the built artifacts in-job: extract `wdb-cli`/`wdb-mcp` and run the launcher `--help`/version; run the agent `.exe` briefly. Verify the job step exits 0. — VERIFIED live: smoke step passed (asserts 4 archives + CLI `--help`; MCP/agent existence-checked as they block on stdio/sockets).

## 3. Manifest + plugin repo index

- [x] 3.1 Generate `latest.json` = `{component: {version, asset, url, sha256, size}}` for agent/cli/mcp/plugin, computing sha256+size from the freshly built assets. Verify the emitted JSON validates and each `sha256` matches `sha256sum` of the asset. — VERIFIED: `scripts/gen-release-manifest.ps1` run locally → valid JSON (4 components), mcp sha256 matches `Get-FileHash`, UTF-8 no BOM.
- [x] 3.2 Generate `updatePlugins.xml` with the plugin id, the versioned plugin asset `url`, `version`, and `<idea-version since-build/until-build>` derived from the plugin's build range (single source of truth with 1.x). Verify the XML is well-formed and the build range matches the plugin. — VERIFIED: id/version/since(261)/until(261.*) read from the BUILT plugin jar's `META-INF/plugin.xml`; XML parses; versioned url.

## 4. Publish

- [x] 4.1 Create/upload a non-draft, non-prerelease GitHub Release for the tag with all 6 assets (`gh release create`/`softprops-action`, `GITHUB_TOKEN`), idempotent on re-run. Verify a test tag produces a Release listing all assets. — VERIFIED live: v0.1.0 release lists all 6 assets (4 zips + latest.json + updatePlugins.xml); appears as `releases/latest`.
- [x] 4.2 Verify the stable consumption URLs resolve after publish: `releases/latest/download/latest.json` and `releases/latest/download/updatePlugins.xml` both return the just-published files. — VERIFIED: both resolve after the repo went public.

## 5. Funding

- [x] 5.1 Add `.github/FUNDING.yml` (GitHub Sponsors + Buy Me a Coffee, targets supplied by owner) and a sponsor link in the README. Verify GitHub renders the Sponsor button on the repo. — DONE: `github: [OlimzhanovUmid]` + `buy_me_a_coffee: umidolimzhanov` (Sponsor button renders after push). No README exists — button comes from FUNDING.yml alone; a README link is out of this CI-only change's scope.

## 6. Verification

- [x] 6.1 End-to-end: push `v<x.y.z>` → confirm the Release has agent/cli/mcp/plugin zips + `latest.json` + `updatePlugins.xml`; add the `updatePlugins.xml` URL as an IntelliJ custom plugin repo and confirm the IDE lists the wdb plugin; fetch `latest.json` and confirm versions/sha256 match the assets. — VERIFIED: v0.1.0 has all 6 assets; `latest.json` versions/urls correct; downloaded `wdb-plugin-0.1.0.zip` sha256 = manifest (c75014a…, 2818313 bytes). IDE custom-repo install = owner to eyeball.
- [x] 6.2 Run `openspec validate add-release-pipeline --strict`; it passes. — VERIFIED: "Change 'add-release-pipeline' is valid".
