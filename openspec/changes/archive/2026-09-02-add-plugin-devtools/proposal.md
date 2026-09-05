## Why

The plugin can start and hot-reload a Compose app on a wall, but it can't *see* or *touch* the running UI — to check what a machine is actually showing you walk over to it. Compose Hot Reload already exposes an app's screen, semantic tree, and UI actions over its orchestration channel (the same channel the wdb agent already hosts for reload). A spike confirmed a wdb-style hot launch answers `ScreenshotRequest` / `SemanticTreeRequest` / `UIActionRequest` once the CHR runtime is on the app's classpath. This change turns that into an in-IDE mirror of the hot app.

## What Changes

- **Core (reused by later flavors):**
  - The agent bundles the CHR runtime jars in its app-image and adds them to the hot launch classpath, so the CHR development entrypoint activates and the app answers devtools requests. Hot mode only.
  - The agent relays screenshot / semantic-tree / UI-action requests over the orchestration channel it already holds and returns the results (PNG bytes, tree JSON, action ack) over the wdb wire.
  - New wire messages and `WdbClient` methods: `screenshot`, `semanticTree`, `uiAction`.
- **Flavor A — in-plugin mirror panel (this change):**
  - **Screenshot first:** a panel that shows the selected hot machine's screen as a PNG with a Refresh control.
  - Then: a semantic-tree view, and click-on-the-screenshot → resolve the node by its bounds → send a Click `UIAction`.

Out of scope: the MCP flavor (B) exposing this to an AI agent — a separate future change, ideally its own module rather than plugin-hosted. Also out of scope: multi-window handling beyond the primary window, and non-Click UI actions beyond what the panel needs.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ide-integration`: adds a requirement that the operator can view a hot machine's screen and interact with it (tap by semantic node) from inside the IDE.

## Impact

- **Code:** `wdb-agent` (bundle CHR runtime jars into the app-image + hot `-cp`; relay devtools requests over orchestration), `wdb-protocol` (screenshot/semantics/ui-action messages, PNG bytes), `wdb-client` (`screenshot`/`semanticTree`/`uiAction`), `wdb-plugin` (mirror panel + tool window content).
- **Packaging:** the agent app-image grows by the CHR runtime jars; **version compatibility** of the injected runtime with the target app's Compose/CHR version is a design concern (see design.md).
- **Preconditions:** works only when the app runs in hot-reload mode (CHR connected), same as reload.
