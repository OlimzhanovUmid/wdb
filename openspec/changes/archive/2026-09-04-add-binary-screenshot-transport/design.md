# Design — binary screenshot transport

## Context

`WdbClient.screenshot(target, host)` → `control(target, host, ScreenshotRequest)` → `sendControl(addr,
req)` opens a CONTROL connection, writes one request frame, reads **one** response frame, decodes a
`ScreenshotResponse`, then base64-decodes `dataBase64`. Agent side: `AgentServer` control loop reads
the request, and for `ScreenshotRequest` returns `ScreenshotResponse(ok, dataBase64 =
Base64(coord.screenshot()))`, which the loop writes as one frame. `FrameCodec` caps a frame at
`MAX_FRAME_SIZE = 1 MiB` and throws `FrameTooLargeException` past it. A ~1.09 MB PNG base64s to
~1.45 MB > 1 MiB → the agent throws while writing → connection closed → client `readFrame` returns
null → `EOFException("no control response from agent")`.

Two problems compound: the 1 MiB cap, and base64 inflating the payload 33% inside a JSON string.

## Decisions

### D1 — Screenshot reply = header frame + raw PNG blob frame

Keep the request/response shape for signalling, but carry the image as bytes:

- Agent writes a `ScreenshotResponse` **header** frame (`ok`, `format`, `error`; no image data).
- On `ok`, the agent then writes **one blob frame** containing the raw PNG bytes.
- Client reads the header; if `ok`, reads the next frame as the PNG `ByteArray`.

This mirrors the existing push exchange (manifest frame → blob frame) and removes the base64 + JSON
overhead. `SemanticTree`/`UIAction`/`Status` control exchanges are unchanged (single frame).

### D2 — `ScreenshotResponse` drops `dataBase64`

`data class ScreenshotResponse(val ok: Boolean, val format: String = "png", val error: String? = null)`.
Removing `dataBase64` is a wire break (justifies the agent bump). No other message changes.

### D3 — `MAX_FRAME_SIZE` 1 MiB → 8 MiB

Covers a full-HD photo screenshot as raw PNG bytes with headroom; not 4K-excessive. Applies to both
directions (agent write, client read) since both use the shared `wdb-protocol` constant.

### D4 — Agent: special-case screenshot in the control loop

The control loop currently computes a `ControlResponse` and writes it as one frame. Add a screenshot
branch that writes the header then the blob directly on the connection's output stream:

```
ScreenshotRequest -> {
    val png = withHotCoordinator { it.screenshot() }        // null if not hot / capture failed
    writeFrame(dout, encode(ScreenshotResponse(ok = png != null, error = if (png==null) "…" else null)))
    if (png != null) writeFrame(dout, png)                  // raw PNG blob
}
```

All other control requests keep the generic "one response frame" path.

### D5 — Client: dedicated screenshot read path

`WdbClient.screenshot` calls a new `ClientOps.screenshotControl(addr)` that opens CONTROL, sends
`ScreenshotRequest`, reads the `ScreenshotResponse` header, and — only when `ok` — reads the trailing
blob frame and returns it. `WdbClient.screenshot` still returns `ByteArray?` (null on `!ok` or read
failure). The generic `control()`/`sendControl` stay for the other devtools calls.

### D6 — Agent version bump + roll

`wdbAgentVersion` 0.2.8 → **0.2.9**. Old 0.2.8 agents both throw on the oversized frame and cannot
speak the blob reply, so both walls must be updated via `wdb agent-update` after the build.

## Non-Goals

- Chunking/streaming a screenshot across multiple frames (single blob ≤ 8 MiB is enough for the
  walls; revisit only if 4K displays appear).
- Changing `semantic_tree`, `ui_action`, `status`, push, or reload transports.
- Any change to the `wdb-mcp` / `wdb-plugin` screenshot APIs (still `ByteArray?`).

## Risks

- **Version skew during rollout:** a new client against an old 0.2.8 agent gets the old base64
  response and no blob → screenshot returns null until the wall is updated. Mitigated by rolling
  `agent-update` to both walls right after building. Acceptable (screenshot already broken on 0.2.8
  for these apps).
- **8 MiB still finite:** a 4K photo screenshot could exceed it → falls back to the same "too large"
  failure, now rarer. Documented as a non-goal.
