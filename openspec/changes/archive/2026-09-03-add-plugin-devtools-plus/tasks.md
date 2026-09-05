## 1. Scroll actions (core)

- [x] 1.1 `wdb-protocol`: `UiActionKind` += `SCROLL_BY`, `SCROLL_TO_INDEX`; `UiActionRequest` += `dx: Float = 0`, `dy: Float = 0`, `index: Int = 0`.
- [x] 1.2 `wdb-agent`: coordinator `uiAction` maps `SCROLL_BY → UIAction.ScrollBy(dx,dy)`, `SCROLL_TO_INDEX → UIAction.ScrollToIndex(index)`; `AgentServer` passes the new fields.
- [x] 1.3 `wdb-client`: `uiAction(..., dx, dy, index)` (defaulted); `WdbService.deviceUiAction` forwards them.

## 2. Tree ↔ screenshot link (plugin)

- [x] 2.1 `MirrorPanel`: keep the parsed root + the image→semantic factor; on tree selection, draw the node's bounds as a highlight rectangle over the screenshot.
- [x] 2.2 On screenshot click, select the deepest containing node in the tree (and show its details) in addition to tapping.

## 3. Auto-refresh + node details (plugin)

- [x] 3.1 An "Auto" toggle that re-screenshots on a ~1.5s timer while enabled; cancels on toggle-off.
- [x] 3.2 A node-details area showing the selected node's text / role / actions / bounds.

## 4. Scroll UI + rollout

- [x] 4.1 Tree node menu: **Scroll Up / Scroll Down** (`ScrollBy(0, ∓step)`) and **Scroll to index…** (prompt), calling `deviceUiAction`.
- [x] 4.2 Bump the agent and roll a scroll-capable build to the walls via `wdb agent-update` (0.2.7).

## 5. Verify

- [x] 5.1 `compileKotlin` + unit tests (client round-trip incl scroll, agent) pass.
- [x] 5.2 `runIde` against a hot wall app: select a tree node → its box shows on the screenshot; click the screenshot → the tree selects that node and shows details.
- [x] 5.3 `runIde`: enable Auto and confirm the image updates on its own; scroll a list node and confirm it scrolls.
