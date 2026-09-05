## Why

The devtools mirror (add-plugin-devtools) can screenshot a hot app and tap by clicking the screenshot, but interaction stopped at click-on-image. The semantic tree it already fetches is a richer control surface: you can act on any node directly, and CHR supports more than clicks — long-click and, importantly, **set-text** (typing into a field). This change surfaces those.

## What Changes

- **Set text:** a new `SET_TEXT` UI action carried end-to-end (protocol → agent coordinator → client), mapping to CHR's `UIAction.SetText(text)`, so the operator can type into a text field of the hot app from the IDE.
- **Act on semantic-tree nodes:** the Mirror's semantic-tree view becomes interactive — double-click a node to tap it, right-click for a **Click / Long Click / Set Text…** menu (Set Text prompts for the value). Tree nodes carry their `SemanticNode` so the action targets the right id.

Requires hot mode (same as the rest of devtools). Needs a devtools-capable agent (the `UiActionRequest` gains a `text` field and a `SET_TEXT` kind; older agents don't know it).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: extends the "mirror and interact" requirement — interaction is not only tapping a point on the screenshot but also acting on a chosen semantic node, including setting text.

## Impact

- **Code:** `wdb-protocol` (`UiActionKind.SET_TEXT` + `UiActionRequest.text`), `wdb-agent` (coordinator `uiAction(nodeId, kind, text)` → `UIAction.SetText`), `wdb-client` (`uiAction(kind, text)`), `wdb-plugin` (`MirrorPanel` tree mouse handlers + action menu, `WdbService.deviceUiAction`).
- **Compat:** wire is backward-tolerant (`ignoreUnknownKeys`), but `SET_TEXT` needs an agent built with this change (rolled to walls via `wdb agent-update`). Bumped agent to 0.2.4.
- **Fixture:** the dummy hot app gains a `TextField` as a set-text target.
