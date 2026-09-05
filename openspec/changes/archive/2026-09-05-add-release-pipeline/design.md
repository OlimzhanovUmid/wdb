## Context

See proposal.md — Why, and the explore session that produced this plan. Current state:

- `wdb-agent`: versioned via `gradle.properties wdbAgentVersion` (0.2.15); `:wdb-agent:packageAgentInstaller` produces `wdb-agent-installer-<ver>.zip` (jpackage app-image + bundled JBR + `install-agent.ps1`). Self-update is push-based: `sendAgentUpdate` streams a zip over the wire; agent verifies sha256+size (`AgentUpdateManifest`) → extract → switch → relaunch.
- `wdb-cli`, `wdb-mcp`: Gradle `application` plugin → `distZip` (cross-OS `bin/` launchers). No version property yet.
- `wdb-plugin`: IntelliJ Platform Gradle plugin → `buildPlugin` zip. No version property. Target platform build 261.
- CI (`ci.yml`): `windows-latest`, build+test on every push/PR. No release workflow.
- Repo: `github.com/OlimzhanovUmid/windows-debug-bridge` (public).

## Goals / Non-Goals

**Goals:**
- One tag → one GitHub Release with every artifact + two index files, reproducibly.
- Stable, no-auth consumption URLs for the indexes so downstream code needs neither the GitHub API nor GitHub Pages.
- Self-versioned assets and a manifest that composes with the agent's existing `AgentUpdateManifest`.

**Non-Goals:**
- Building any consumer (plugin auto-update, agent GitHub-pull, CLI install) — those are follow-on changes; this only publishes.
- Code signing / macOS notarization / Windows Authenticode (CLI is JVM distZip; agent exe stays unsigned for now — a named risk).
- Native/jpackage CLI (decided: distZip JVM).
- Publishing to JetBrains Marketplace in this change (the custom-repo `updatePlugins.xml` is produced here; Marketplace `publishPlugin` is part of the follow-on plugin-auto-update change).

## Decisions

**D1 — Single `windows-latest` release job, triggered on tag `v*`.**
Agent jpackage is Windows-only and everything else builds fine on Windows, so one job avoids artifact hand-off between matrix legs. `setup-java distribution: jetbrains` provides a JBR 21 used both as the Gradle JDK and as jpackage's `-PjbrHome`/runtime image. *Alt:* 3-OS matrix — unnecessary since no artifact needs mac/linux to build (CLI distZip is OS-agnostic).

**D2 — Version scheme: the tag is the release version; components are self-versioned.**
`v<x.y.z>` tag → release name/version. A repo version property (e.g. `gradle.properties wdbVersion`) drives `wdb-cli`/`wdb-mcp`/`wdb-plugin` archive names and the plugin's `version`. The agent keeps its independent `wdbAgentVersion` (it updates far more often than the IDE-side tooling). `latest.json` records each component's *actual* version, so consumers never parse asset filenames for truth. *Alt:* per-component tags — rejected as overkill for a solo monorepo; one tag, many self-versioned assets is simpler.

**D3 — Two index files, consumed at `releases/latest/download/<asset>` (the key trick).**
GitHub resolves `https://github.com/OWNER/REPO/releases/latest/download/<asset>` to the newest release's asset — a *stable* URL even though the underlying asset URL carries the tag. So:
- `updatePlugins.xml` — IntelliJ custom plugin repository. Consumers add
  `.../releases/latest/download/updatePlugins.xml` once as a repository; its inner
  `<plugin url=...>` points at the versioned `wdb-plugin-<ver>.zip` asset. Includes
  `<idea-version since-build="261" until-build="261.*"/>` (kept in sync with the plugin's build range).
- `latest.json` — `{ "<component>": { "version", "asset", "url", "sha256", "size" } }` for
  agent/cli/mcp/plugin, consumed via `.../releases/latest/download/latest.json`.
`sha256`+`size` mirror `AgentUpdateManifest`, so the future agent-github-pull change can build a manifest straight from `latest.json`. *Alt:* GitHub Pages for a stable XML — rejected; `releases/latest/download` already gives stability with zero extra hosting. *Alt:* consumers hit the REST API — kept as a fallback only; the stable URL is primary.

**D4 — Manifest generation lives in the workflow.**
After the Gradle builds, a small step computes each asset's sha256+size and emits `latest.json` and `updatePlugins.xml` (a short shell/PowerShell or a tiny Gradle task). Kept in CI, not committed, so it always reflects the freshly built assets.

**D5 — Upload via `gh` (preinstalled on GitHub runners) or `softprops/action-gh-release`.**
`gh release create v<ver> <assets...>` (or the action) with `GITHUB_TOKEN`. Idempotent handling for re-runs: create-or-edit the release for the tag.

**D6 — FUNDING.yml folded in.**
`.github/FUNDING.yml` with `github:` (Sponsors) + `custom:`/`buy_me_a_coffee:` per `docs/monetization.md`. Sponsor link added to README; a plugin "About"/sponsor affordance is deferred to the plugin change (not this CI-only change).

## Risks / Trade-offs

- **Unsigned agent `.exe` / no mac notarization** → SmartScreen/Gatekeeper warnings for end users. *Mitigation:* accept for now (dev/internal tool); revisit signing when there's a paying fleet customer. Named, not solved here.
- **`updatePlugins.xml` build-range drift** → if the plugin's `since/until-build` and the XML disagree, the IDE hides the update. *Mitigation:* derive the range in the generator from the same source the plugin build uses (single source of truth), or assert equality in CI.
- **`latest` points at a pre-release/draft** → `releases/latest` excludes drafts and pre-releases, so a full release must be published for consumers to see it. *Mitigation:* publish as a full (non-draft, non-prerelease) release; document that pre-releases won't be picked up.
- **jpackage in CI needs a real JBR/JDK image with the right modules** → build breaks if the runtime image is wrong. *Mitigation:* use `setup-java distribution: jetbrains` (full JBR) and pass it as `jbrHome`; smoke-run the produced `.exe`/launcher in the job.
- **Tag/version mismatch** (tag `v1.2.3` but `wdbVersion` says otherwise) → confusing assets. *Mitigation:* CI derives the version from the tag and passes it to Gradle (`-Pversion=`/`-PwdbVersion=`), or asserts tag == property.

## Migration Plan

1. Land workflow + versioning + FUNDING.yml.
2. Push `v<x.y.z>` → verify a Release appears with all 6 assets and that `releases/latest/download/latest.json` and `.../updatePlugins.xml` resolve.
3. Follow-on changes consume it; `add-plugin-mcp-install` task 1.1 is reduced to consumption.
4. Rollback: delete the workflow (and release/tag if needed); no runtime component is affected.

## Open Questions

- Exact FUNDING targets (Sponsors handle, Buy Me a Coffee URL) — owner to supply; does not change the workflow shape.
- Whether to assert `tag == wdbVersion` or derive version solely from the tag — a small CI detail, resolvable at implementation without changing scope.
