## 1. Protocol

- [x] 1.1 `wdb-protocol` `FrameCodec.MAX_FRAME_SIZE`: 1 MiB → 8 MiB.
- [x] 1.2 `ScreenshotResponse`: drop `dataBase64` → `data class ScreenshotResponse(val ok: Boolean, val format: String = "png", val error: String? = null)`.

## 2. Agent

- [x] 2.1 `AgentServer` control loop: special-case `ScreenshotRequest` — resolve `png = coord.screenshot()`, write a `ScreenshotResponse(ok = png != null, error = …)` header frame, then on `ok` write the raw PNG bytes as one blob frame. Leave all other control requests on the single-frame path.
- [x] 2.2 Bump `wdbAgentVersion` 0.2.8 → 0.2.9 (`gradle.properties`).

## 3. Client

- [x] 3.1 `ClientOps.screenshotControl(addr)`: open CONTROL, send `ScreenshotRequest`, read the `ScreenshotResponse` header; if `ok`, read the trailing blob frame and return it as `ByteArray` (null otherwise).
- [x] 3.2 `WdbClient.screenshot` uses `screenshotControl` (still returns `ByteArray?`); remove the base64 decode. `semantic_tree`/`ui_action`/`status` unchanged.

## 4. Verify

- [x] 4.1 `:wdb-protocol:build :wdb-client:build :wdb-agent:build :wdb-mcp:build :wdb-plugin:compileKotlin` green. Update/extend the screenshot round-trip test (`FakeAgent` sends header + blob) so the client decodes a >1 MiB PNG.
- [x] 4.2 Build agent 0.2.9, `wdb agent-update` both walls (or wall 1 first). Live: `screenshot 1` on a third-party app (fullscreen, ~1 MB PNG) returns the image (no "no control response"); HotApp screenshot still works; mirror in Android Studio shows a third-party app.
