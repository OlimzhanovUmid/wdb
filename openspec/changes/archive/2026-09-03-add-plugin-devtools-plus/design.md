# Design — devtools+ (inspect + scroll)

## Context

Builds on add-plugin-devtools / -actions. The mirror already fetches a PNG + the semantic tree (parsed to `SemanticNode` with id/role/text/bounds/actions/children) and can Click/LongClick/SetText a node. This adds inspection affordances (all plugin-side) plus scroll (needs the agent).

## Decisions

### D1 — Scroll rides the existing UI-action path

`UiActionKind` gains `SCROLL_BY` and `SCROLL_TO_INDEX`; `UiActionRequest` gains `dx: Float = 0, dy: Float = 0, index: Int = 0` (defaulted → wire stays backward-tolerant). The coordinator maps `SCROLL_BY → UIAction.ScrollBy(dx,dy)`, `SCROLL_TO_INDEX → UIAction.ScrollToIndex(index)`. The tree node menu adds **Scroll Up / Scroll Down** (`ScrollBy(0, ∓step)`, step ≈ 300) and **Scroll to index…** (prompt). New kinds need an agent built with this change → bump to 0.2.7.

### D2 — Two-way tree ↔ screenshot link (plugin only)

`MirrorPanel` keeps the parsed root and the last screenshot's image→semantic scale (root bounds ÷ image size, the DPI-agnostic factor already used for taps). Selecting a tree node repaints the image with a highlight rectangle at the node's bounds mapped to display coordinates (custom `paintComponent` over the image, or a glass overlay). Clicking the image hit-tests the tree (reusing `clickableNodeAt`, but for *selection* it uses a plain deepest-contains, since any node — not only clickable — should be selectable) and selects that row.

### D3 — Auto-refresh (plugin only)

A "Auto" toggle starts a coroutine on `devtoolsScope` that re-screenshots every ~1.5 s while enabled and the mirror is showing; toggling off cancels it. Reuses the existing `refresh()`.

### D4 — Node details (plugin only)

A read-only label/area under the tree shows the selected `SemanticNode`'s text / role / actions / bounds.

## Non-Goals

Drag-to-scroll on the screenshot (menu/step is enough for now); gesture actions beyond scroll; multi-window.

## Risks

- **Highlight coordinate mapping** must match the tap mapping (fraction × root bounds) or the rectangle drifts under DPI scaling — reuse the same factor.
- **Auto-refresh cost** — keep the interval modest (~1.5 s) and only while the tool window is visible; screenshots are on-demand PNGs, not a stream.
- Scroll only applies to nodes CHR considers scrollable — non-scrollables report "action failed".
