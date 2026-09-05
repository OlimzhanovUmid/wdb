## Why

Mirror/`screenshot` fails for fullscreen or photo-heavy apps. Root cause: `FrameCodec.MAX_FRAME_SIZE`
is **1 MiB**, and the agent sends the screenshot **base64-encoded inside the JSON `ScreenshotResponse`**
(+33% inflation). A real screen (e.g. a third-party app's PIN screen, ~1.09 MB PNG) becomes a ~1.38 MB frame →
`FrameTooLargeException` on `writeFrame` → the agent closes the connection → the client sees
`no control response from agent`. Small/flat screens (HotApp ~50 KB) and `semantic_tree` (small) work,
which is why the failure looked app/IDE-specific — it is not: it is purely transport size. The
existing screenshot requirements already promise "returns that machine's current screen as image
content"; this change makes the implementation honor that for any realistic image size.

## What Changes

- **Send the screenshot as raw PNG bytes over a blob frame**, not base64-in-JSON. The screenshot
  control reply becomes a small `ScreenshotResponse` header (`ok` / `error`, no `dataBase64`) followed,
  on success, by exactly one raw PNG blob frame — mirroring the push manifest→blob pattern. The client
  reads the header, then (on `ok`) the blob. Drops the 33% base64 inflation and the JSON string cost.
- **Raise `MAX_FRAME_SIZE` from 1 MiB to 8 MiB** — enough for a full-HD photo screenshot as raw bytes,
  without going 4K-excessive.
- **BREAKING (wire):** `ScreenshotResponse` loses `dataBase64`; the screenshot control exchange now
  sends a trailing blob frame. Agent and client must both update → agent version bump + roll to walls.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

_None — `skip_specs: true`._ This is a transport/limit fix, not a spec-level behavior change: the
`mcp-server` and `ide-integration` specs already require the screenshot tools to return the screen as
image content. No requirement text changes; the fix removes an implementation size limit that
violated them.

## Impact

- **Code:** `wdb-protocol` (`MAX_FRAME_SIZE` → 8 MiB; `ScreenshotResponse` drops `dataBase64`),
  `wdb-agent` (`AgentServer` screenshot branch writes header + raw PNG blob frame; version bump),
  `wdb-client` (screenshot reads header + blob; `WdbClient.screenshot` still returns `ByteArray?`).
- **Callers unchanged:** `wdb-mcp` `screenshot` tool, `wdb-plugin` mirror — same `ByteArray?` API.
- **Rollout:** agents on the walls must be updated (old 0.2.8 agents throw on the oversized frame and
  don't speak the blob reply) → `wdb agent-update` to both walls.
