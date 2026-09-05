## 1. Core — CHR runtime on the hot classpath (agent packaging)

- [x] 1.1 Resolve the CHR runtime jars as a Gradle configuration in `wdb-agent` (runtime-jvm, runtime-api-jvm, core, orchestration, annotations-jvm, analysis, devtools-api, devtools) — like the existing `hotReloadAgentJar` config but non-shadowed.
- [x] 1.2 In `packageAgent`, copy those jars into the app-image lib dir so they ship with the agent; add a resolver (`detectDevtoolsRuntimeJars()`) that finds them next to the agent jars at runtime (null in dev/test = devtools disabled).
- [x] 1.3 In `buildLaunchCommand` (hot branch only), prepend the CHR runtime jars to the app `-cp` (with the hot dir + app jar). Verify the launch line in a unit test.

## 2. Core — agent relay over orchestration

- [x] 2.1 Extend `ChrHotReloadCoordinator` (and its interface) with `screenshot(): ByteArray?` / `semanticTree(): String?` / `uiAction(nodeId, action): Boolean` that send the matching `OrchestrationMessage` request and await the correlated `*Result` (same pattern as `applyReload`), with a timeout → null/false on no answer.
- [x] 2.2 Add `AgentServer` wire handlers that call the coordinator and return the PNG bytes / tree JSON / ack; reject with a clear "not hot" when no hot run is active.

## 3. Core — protocol + client

- [x] 3.1 Add `wdb-protocol` messages: `ScreenshotRequest`/`ScreenshotResponse` (PNG bytes + format), `SemanticTreeRequest`/`SemanticTreeResponse` (tree JSON), `UiActionRequest`(nodeId, kind, args)/`UiActionResponse`(ok, error).
- [x] 3.2 Add `WdbClient.screenshot(name, host?)`, `semanticTree(name, host?)`, `uiAction(name, nodeId, action, host?)`; round-trip test against `FakeAgent`.

## 4. Flavor A — mirror panel (screenshot first)

- [x] 4.1 **Screenshot:** a mirror panel/tab showing the selected hot machine's PNG (scaled to fit) with a Refresh action; not-hot / no-answer → a clear "devtools unavailable" message.
- [x] 4.2 **Semantic tree:** fetch + parse `SemanticTreeResponse` JSON (`id`/`bounds`/`text`/`children`) into a tree view; unit-test the parse.
- [x] 4.3 **Tap:** on a click at a screenshot point, hit-test the tree for the deepest node whose `bounds` contain the (scale-adjusted) point → `uiAction(nodeId, Click)` → report result; unit-test the hit-testing.
- [x] 4.4 Wire a "Mirror" action (per hot machine) that opens the panel for that machine and does the initial screenshot.

## 5. Verify

- [x] 5.1 Version-mismatch handling: `compileKotlin`; confirm that when the app never answers, the panel shows "devtools unavailable" within the timeout (no hang).
- [x] 5.2 `runIde` against a hot wall app: open Mirror → the screen appears and Refresh updates it; the semantic tree lists nodes; clicking a button on the screenshot taps it (the app reacts).
- [x] 5.3 `runIde`: Mirror on a non-hot machine reports unavailable.
