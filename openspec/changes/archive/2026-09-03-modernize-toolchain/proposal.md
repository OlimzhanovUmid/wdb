## Why

The wdb stack is pinned to Kotlin 2.1.21 / Compose Multiplatform 1.10 / Compose Hot Reload 1.2.0 / IntelliJ Platform 2025.1. The MCP server (`add-wdb-mcp`) can't compile because the Kotlin MCP SDK (0.13.0) is built with Kotlin 2.3 metadata — reading it with the 2.1 compiler fails. A spike confirmed the modern stack lines up: IntelliJ 2026.1 bundles Kotlin 2.3.x, Compose Hot Reload supports Kotlin 2.3.x, and Compose MP 1.11/1.12 works with it. Bumping the toolchain unblocks MCP and keeps the project current, without the metadata-mismatch risk of a half-bump (the plugin compiles against the platform's bundled Kotlin, so project Kotlin and platform Kotlin must move together).

## What Changes

- **Kotlin** 2.1.21 → **2.3.20** (compiler, `kotlin.plugin.serialization`, `kotlin.plugin.compose` all track this version).
- **Compose Multiplatform** 1.10.0 → a Kotlin-2.3-compatible release (target 1.12.0, adjust if needed).
- **Compose Hot Reload** 1.2.0 → the release that supports Kotlin 2.3.x (stable if available, else the lowest RC/alpha that does) — critical: reload + the devtools runtime it injects must keep working.
- **IntelliJ Platform** 2025.1 → **2026.1** for `wdb-plugin` (`create("IC", ...)`, `sinceBuild`/`untilBuild`), reconciling any bundled-module renames (Jewel/Compose) and deprecated-API changes so the plugin still compiles and `runIde` works.
- **Agents rebuilt + rolled** to the walls: they bundle CHR runtime/agent jars, whose versions must match the new Compose/Kotlin, via `wdb-agent:packageAgent` + `wdb agent-update`.

No externally observable behavior changes — this is a toolchain/dependency bump (hence `skip_specs`). All existing capabilities (deploy/run/hot-reload/logs/debug/devtools) must keep working, verified live.

## Capabilities

_None — pure toolchain modernization; `skip_specs: true`._

## Impact

- **Build:** `gradle/libs.versions.toml` (kotlin, compose, composeHotReload, intellijPlatform/intellijIdea, + a compose-compiler alias if it diverges), `wdb-plugin/build.gradle.kts` (platform version, sinceBuild), `wdb-agent/build.gradle.kts` (CHR artifact versions bundled onto the hot classpath).
- **Code:** `wdb-plugin` may need small edits for IntelliJ 2026.1 API/module changes; `wdb-dummy-app` for any Compose 1.12 API deltas.
- **Rollout:** rebuild agents, `wdb agent-update --all`; re-verify deploy/run/hot-reload/logs/debug/devtools on the walls.
- **Unblocks:** `add-wdb-mcp` (the MCP SDK then compiles); re-add `wdb-mcp` to `settings.gradle.kts` when applying that change.
