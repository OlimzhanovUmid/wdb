## Why

The devtools mirror can screenshot, tap, and set text, but inspecting and driving a screen still has rough edges: you can't tell which node a tree row maps to on screen, scrolling a list isn't possible, and the screenshot is a manual snapshot that goes stale. This rounds the mirror into a small layout-inspector: link the tree to the image, auto-refresh, show node details, and scroll.

## What Changes

- **Scroll actions:** `SCROLL_BY` (delta) and `SCROLL_TO_INDEX` UI actions on a node, mapping to CHR `UIAction.ScrollBy/ScrollToIndex`, exposed in the tree node menu (Scroll Up/Down, Scroll to index…). Needs a devtools agent that understands the new kinds.
- **Tree ↔ screenshot highlight:** selecting a node in the tree draws its bounds as an overlay rectangle on the screenshot; clicking the screenshot selects the hit node in the tree — a two-way link, layout-inspector style.
- **Auto-refresh mirror:** a toggle that re-screenshots on a timer while the mirror is open, so on-screen changes show without clicking Refresh.
- **Node details:** the selected node's text / role / actions / bounds shown in a small panel.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: extends the mirror requirement — inspect (tree↔screenshot link, node details, live refresh) and scroll, in addition to tap/set-text.

## Impact

- **Code:** `wdb-protocol` (`UiActionKind.SCROLL_BY`/`SCROLL_TO_INDEX` + `dx`/`dy`/`index` on `UiActionRequest`), `wdb-agent` (coordinator maps scroll → CHR `ScrollBy`/`ScrollToIndex`), `wdb-client` (scroll params on `uiAction`), `wdb-plugin` (`MirrorPanel`: overlay highlight, two-way selection, auto-refresh toggle, node-details, scroll menu).
- **Rollout:** scroll needs an agent built with this change → bump to 0.2.7 and `wdb agent-update`. Highlight/auto-refresh/details are plugin-only. Wire stays backward-tolerant (`ignoreUnknownKeys`), but scroll kinds fail on older agents.
