# Design — devtools node actions (set-text + tree interaction)

## Context

Builds on add-plugin-devtools. That change carried a click-only `UiActionRequest(nodeId, kind)` where kind ∈ {CLICK, LONG_CLICK}, dispatched by the agent's `ChrHotReloadCoordinator` to CHR `UIAction.Click/LongClick` over the orchestration channel. CHR also exposes `UIAction.SetText(String)`. The Mirror already renders the semantic tree but only for hit-testing image clicks.

## Decisions

### D1 — SET_TEXT threaded through the existing action path

`UiActionKind` gains `SET_TEXT`; `UiActionRequest` gains `text: String = ""` (defaulted, so the wire stays backward-tolerant via `ignoreUnknownKeys`). The coordinator's `uiAction(nodeId, kind, text)` maps `SET_TEXT → UIAction.SetText(text)`. No new stream or message type — it rides the existing CONTROL `UiActionRequest`.

### D2 — The semantic tree is the action surface

`MirrorPanel`'s JTree nodes wrap their `SemanticNode` (so the id/role/actions are on hand). A double-click taps the node (CLICK); a right-click opens Click / Long Click / Set Text… (the last prompts via `Messages.showInputDialog`). This complements the existing click-on-screenshot tap; both call `WdbService.deviceUiAction`.

### D3 — Agent compatibility

`SET_TEXT` is an enum value an older agent's `UiActionKind` cannot decode, so it needs an agent built with this change. The agent is bumped to 0.2.4 and rolled to walls via `wdb agent-update`. CLICK/LONG_CLICK keep working against older agents (the extra `text` field is ignored).

## Non-Goals

Scroll actions (`ScrollBy`/`ScrollToIndex`), multi-select, and a keyboard-focus model — later if needed.

## Risks

- **Node must support the action** — CHR returns not-applied for a set-text on a non-editable node; surfaced as "action failed".
- Live-only verification (needs a hot Compose app with a text field); the dummy fixture gains one.
