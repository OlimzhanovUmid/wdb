# Design — toolchain modernization (Kotlin 2.3 / IntelliJ 2026.1)

## Context

Spike results (confirmed): IntelliJ 2026.1 bundles Kotlin 2.3.x; 2026.2 bundles Kotlin 2.4. Compose Hot Reload's minimum is Kotlin 2.1.20 and recent releases support Kotlin 2.3.x. Compose MP latest is 1.12.0; CHR needs Compose MP 1.10+. The MCP SDK (0.13.0) is Kotlin-2.3 metadata. So Kotlin 2.3.20 + IntelliJ 2026.1 + Compose MP 1.12 + a 2.3-capable CHR is a coherent target.

## Why all axes move together

The IntelliJ plugin compiles against the platform's **bundled** Kotlin/Compose/Jewel. If the project's Kotlin compiler is older than the platform's bundle (or vice-versa), reading the other's metadata fails — the exact error we hit with the MCP SDK, mirrored. So Kotlin, the platform, the compose-compiler plugin, Compose MP, and CHR must land on mutually compatible versions in one change.

## Decisions

### D1 — Target versions (adjust during apply if a resolve fails)

- Kotlin **2.3.20**; `kotlin.plugin.serialization` and `kotlin.plugin.compose` pinned to it.
- IntelliJ Platform **2026.1** (`intellijIdea = "2026.1"`, `intellijPlatform` gradle plugin bumped to a version that supports it; `sinceBuild` → 261, `untilBuild` → 261.*).
- Compose MP **1.12.0**.
- CHR: the **lowest version that supports Kotlin 2.3.x** — prefer a stable; if only an alpha/RC (e.g. 1.3.0-alpha01) supports 2.3, use it and note the stability caveat.

### D2 — Order of work (fail fast on the riskiest axis first)

1. Bump Kotlin + serialization/compose-compiler; compile the non-plugin modules (protocol/client/agent/dummy) — smallest blast radius.
2. Bump Compose MP + CHR; compile `wdb-dummy-app` and `wdb-agent` (agent references CHR orchestration/core).
3. Bump the IntelliJ platform to 2026.1; fix `wdb-plugin` compile (bundled-module renames, deprecated APIs, `buildPlugin`).
4. `runIde` smoke + the live wall re-verification.

### D3 — Agent CHR jars must match

`wdb-agent` bundles the CHR `-javaagent` and (for devtools) the runtime jars onto the hot app's classpath. These are pulled by version from the catalog (`composeHotReload`), so bumping the catalog updates them. After the bump, rebuild the app-image and `wdb agent-update --all` so the walls run an agent whose injected CHR matches the new Compose/Kotlin — otherwise reload/devtools break (a mismatch already bit us once via `hot-reload-analysis`).

### D4 — Verification is live, not just compile

`buildPlugin` proves the plugin loads headlessly, but hot-reload and devtools only prove out against a real hot Compose app. The change isn't done until, on a wall running the rebuilt agent: deploy → run/hot-run → **reload** → logs → debug → **mirror (screenshot/tap/set-text/scroll)** all work.

## Risks

- **IntelliJ 2026.1 API/module deltas** — the biggest unknown; bundled Jewel/Compose module names or the `intellijPlatform` DSL may have changed 2025.1→2026.1. Mitigate: bump platform last, fix compile errors, keep changes minimal.
- **CHR-for-2.3 may be pre-release** — if only an alpha supports Kotlin 2.3, the reload/devtools stack runs on an alpha; acceptable for a demo wall, flagged.
- **Compose 1.12 API changes** in the dummy fixture — minor, fix at compile.
- **Rollout** — every wall agent must be re-rolled; a stale 0.2.x agent with mismatched CHR would fail reload. Roll all before declaring done.
