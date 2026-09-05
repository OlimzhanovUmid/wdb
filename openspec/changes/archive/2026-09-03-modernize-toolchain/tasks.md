## 1. Kotlin + serialization/compose-compiler

- [x] 1.1 Bump `kotlin = "2.3.20"` in `gradle/libs.versions.toml` (drives `kotlin-jvm`, `kotlin-serialization`, `compose-compiler` which use `version.ref = "kotlin"`).
- [x] 1.2 Compile the non-plugin modules: `:wdb-protocol :wdb-client :wdb-cli :wdb-agent` compileKotlin + their tests; fix any Kotlin 2.3 deprecations.

## 2. Compose MP + Compose Hot Reload

- [x] 2.1 Bump `compose = "1.12.0"` and `composeHotReload` to the lowest version supporting Kotlin 2.3.x (prefer stable; note if alpha/RC).
- [x] 2.2 Compile `:wdb-dummy-app` (Compose 1.12 API deltas) and `:wdb-agent` (CHR orchestration/core API); confirm `packageAgent` still assembles with the new CHR runtime/agent jars.

## 3. IntelliJ Platform 2026.1

- [x] 3.1 Bump `intellijIdea = "2026.1"` and the `intellijPlatform` gradle plugin to a version supporting it; set `sinceBuild`/`untilBuild` to 261.
- [x] 3.2 Fix `wdb-plugin` compile against 2026.1 — reconcile bundled-module names (Jewel/Compose/skiko), deprecated APIs; `:wdb-plugin:buildPlugin` succeeds (headless load).

## 4. Rollout + live verification

- [x] 4.1 Bump the agent version; `packageAgent` + build the update zip (`jar -cMf`) and `wdb agent-update --all` so walls run an agent whose bundled CHR matches the new stack.
  - Agent 0.2.7 → 0.2.8. `packageAgent` app-image carries the modern stack (kotlin-stdlib 2.3.20, all CHR jars 1.3.0-alpha01, devtools/ set). Zip `build/wdb-agent-0.2.8.zip` (forward-slash entries). `agent-update --all` → wall "1" restarted; `status` reports `agent/rt: 0.2.8 / 21.0.10`. (Only one wall online at rollout time; the second was offline.)
- [x] 4.2 `runIde` + live on a wall: deploy → run/hot-run → **reload** → logs → debug → **mirror (screenshot/tap/set-text/scroll)** all still work.
  - Verified on wall "1" (agent 0.2.8) via the sandbox IDE on IntelliJ 2026.1: reload succeeds (CHR 1.3.0-alpha01 apply) and mirror screenshot/tap/set-text/scroll all work.

## 5. Unblock MCP

- [x] 5.1 Re-add `wdb-mcp` to `settings.gradle.kts`; confirm `:wdb-mcp:compileKotlin` now passes with the MCP SDK (Kotlin 2.3). (Full MCP impl/verify stays in `add-wdb-mcp`.)
  - Metadata blocker resolved: under Kotlin 2.3.20 the compiler reads the MCP SDK 0.13.0 metadata (no more "incompatible metadata 2.3.0 vs 2.1.0"). Remaining `compileKotlin` errors are the stub `Main.kt`'s SDK-0.13 API drift (`Tool(...)` ctor params, `addTool` handler now 2-arg) — that impl is `add-wdb-mcp` scope. Re-parked (commented) in settings to keep the aggregate build green until `add-wdb-mcp` lands.
