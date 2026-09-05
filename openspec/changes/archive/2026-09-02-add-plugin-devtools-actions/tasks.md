## 1. Core — SET_TEXT through the action path

- [x] 1.1 `wdb-protocol`: add `UiActionKind.SET_TEXT` and `UiActionRequest.text: String = ""`.
- [x] 1.2 `wdb-agent`: coordinator `uiAction(nodeId, kind, text)` maps CLICK/LONG_CLICK/SET_TEXT to CHR `UIAction.Click/LongClick/SetText(text)`; `AgentServer` passes `kind`+`text`.
- [x] 1.3 `wdb-client`: `uiAction(target, nodeId, kind, text, host)`; update the FakeAgent round-trip test to the new signature.

## 2. Plugin — act on semantic-tree nodes

- [x] 2.1 `WdbService.deviceUiAction(m, nodeId, kind, text)` (with `deviceTap` = CLICK convenience).
- [x] 2.2 `MirrorPanel`: JTree nodes wrap their `SemanticNode`; double-click taps; right-click menu Click / Long Click / Set Text… (Set Text prompts via `Messages.showInputDialog`), calling `deviceUiAction`.

## 3. Rollout + fixture

- [x] 3.1 Bump the agent version and roll a devtools+SET_TEXT agent to the wall via `wdb agent-update` (built to 0.2.4).
- [x] 3.2 Give the dummy hot app a `TextField` as a set-text target.

## 4. Verify

- [x] 4.1 `compileKotlin` + unit tests (client round-trip, agent) pass with the new signature.
- [x] 4.2 `runIde` against a hot wall app: right-click the text-field node → Set Text → the app's field shows the value; double-click a button node taps it.
