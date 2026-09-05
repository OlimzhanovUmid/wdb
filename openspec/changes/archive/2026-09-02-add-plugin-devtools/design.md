# Design — in-IDE devtools mirror for hot apps

## Context

Compose Hot Reload's runtime (`hot-reload-runtime-jvm`) answers `ScreenshotRequest` → `ScreenshotResult` (PNG bytes), `SemanticTreeRequest` → `SemanticTreeResult` (JSON with per-node `id`, `bounds`, `text`), and `UIActionRequest(nodeId, UIAction)` → `UIActionResult`, all over the CHR **orchestration** channel. The wdb agent already hosts that orchestration server for hot reload (`ChrHotReloadCoordinator`). A spike (thrown away) confirmed the whole path works from a wdb-style launch — see D1.

## Goals

- View a hot machine's screen in the IDE, refresh on demand (screenshot first).
- Then: show the semantic tree and let the operator tap an element (by node, resolvable from a point on the screenshot via bounds).
- Build a reusable core (agent relay + protocol + client) that a later MCP flavor can sit on.

## Non-Goals (this change)

- The MCP flavor (exposing this to an AI agent) — separate future change, ideally its own module, not plugin-hosted.
- Multi-window apps beyond the primary window; UI actions beyond what the tap needs (LongClick/Scroll/SetText come later).
- Live/continuous streaming — screenshots are on-demand (Refresh), not a video feed.

## Decisions

### D1 — Activation: inject the CHR runtime into the hot classpath (spike-confirmed)

The spike showed the agent's `-javaagent` (`ComposeHotReloadAgent` premain) **auto-wraps** the app's Compose window in the CHR `JvmDevelopmentEntryPoint` — which installs the screenshot/semantics/UI-action handlers — as soon as the CHR runtime is on the app's classpath and `-Dcompose.reload.isHotReloadActive=true` is set (already set for hot mode). No need to launch through CHR's own `DevApplication`; wdb keeps launching the app's real main.

Required jars on the hot `-cp` (from the spike): `hot-reload-runtime-jvm`, `hot-reload-runtime-api-jvm`, `hot-reload-core`, `hot-reload-orchestration`, `hot-reload-annotations-jvm`, `hot-reload-analysis`, `hot-reload-devtools-api`, `hot-reload-devtools`. (Missing `devtools-api` crashed the entrypoint with `NoClassDefFoundError: ReloadState` in the spike — all are needed.)

### D2 — Agent relays over the existing orchestration server

Extend `ChrHotReloadCoordinator` with `screenshot()` / `semanticTree()` / `uiAction(nodeId, action)` that send the matching `OrchestrationMessage` request and await the correlated `*Result` (same pattern as `applyReload` awaiting `ReloadClassesResult`). Requests target the single primary window (the no-arg request forms worked in the spike); window id can be added later. Returns PNG bytes / tree JSON / ack. `AgentServer` exposes these as wdb wire commands.

### D3 — Protocol + client

New framed wire messages carrying: a screenshot request → PNG bytes + format; a semantic-tree request → tree JSON string; a UI-action request (nodeId + action kind + args) → success/error. `WdbClient` gains `screenshot(name)`, `semanticTree(name)`, `uiAction(name, nodeId, action)`. PNG travels as bytes in the framed payload (the wire already carries binary for jar push).

### D4 — Plugin UI: screenshot first, then tree + tap

A mirror surface for the selected hot machine:
1. **Screenshot (first slice):** fetch PNG → show in a panel (Swing `ImageIcon`/scaled, or Compose `Image`) with a Refresh button. Unavailable/not-hot → a clear message.
2. **Semantic tree:** parse `SemanticTreeResult` JSON (`id`/`bounds`/`text`/`children`) into a tree view.
3. **Tap:** on a click at a screenshot point, walk the tree for the deepest node whose `bounds` contain the point (accounting for image scale) → `uiAction(nodeId, Click)` → report result.

Reuses the tool-window patterns already in the plugin (a bottom/side content, machine selector, notifications).

### D5 — Version compatibility of the injected runtime (risk owned here)

The injected `hot-reload-runtime-jvm` (+deps) must be **binary-compatible with the target app's Compose runtime and CHR version**. The spike ran on Compose 1.10 / CHR 1.2.0 (matching the demo apps). A wall app built against a different Compose/CHR could `NoClassDefFound`/`LinkageError` under the injected runtime. Mitigations for this change: pin the bundled CHR version to the one the demo apps use and document the constraint; degrade gracefully — if the app never answers (no `ScreenshotResult` before a timeout), report "devtools unavailable (version mismatch?)" rather than hang. A per-project runtime-version selection is future work.

## Risks

- **Version mismatch** (D5) — the main reliability risk; handled by a timeout + clear message, not a hard guarantee.
- **App-image size** grows by the CHR runtime jars (dev-only), acceptable.
- **Hot-only**: devtools need the CHR runtime active — same precondition as reload; non-hot machines report unavailable.
- **Live verification only**: like reload, correctness needs a real hot Compose app; unit tests can cover the tree→node hit-testing and message plumbing but not the end-to-end render.

## Migration

Additive. No protocol removals; new messages are optional and only used when the operator opens the mirror. Existing deploy/run/reload/logs/debug are unaffected.
