# Design — wdb MCP server v2 (cache + status + deploy)

## Context

v1 (`wdb-mcp/src/main/kotlin/dev/wdb/mcp/Main.kt`) registers tools whose bodies are extracted into
`internal suspend tool*` functions with an injectable `host`, so a smoke test drives them against a
fake agent. `WdbClient` already exposes `status(target, host)` and `push(target, jar, mainClass,
restart, host)`; every devtools/lifecycle call takes an optional `host`. v1 passed no `host`, so the
client ran its own discovery per call. v2 adds a resolution cache and two tools, staying in that
same extract-and-inject shape.

## Decisions

### D1 — Machine cache keyed by name, holding the full `Machine`

Introduce a small `MachineCache(ttlMs, discover: suspend () -> List<Machine>)` in `wdb-mcp`:

- `list(): List<Machine>` — always refreshes (a full discovery), repopulates, returns the machines.
  `list_machines` calls this, so it stays authoritative and self-healing.
- `resolve(name): AgentAddress?` — returns the cached machine's address; if the cache is empty for
  that name or older than `ttlMs`, it refreshes once first. A `Mutex` guards refresh so concurrent
  tool calls don't fan out into parallel discoveries.

Holding the full `Machine` (not just the address) lets `list_machines` keep showing app state, and
lets `resolve` hand the address to host-addressed client calls. TTL default ~5s: long enough to
coalesce a screenshot→tap→tap burst, short enough that a moved/vanished machine self-corrects on the
next `list_machines`. Injecting `discover` (rather than calling `client.discover()` inside) makes the
cache unit-testable with a supplied machine list — no UDP.

Wire-through: `screenshot`, `semantic_tree`, `ui_action`, the lifecycle tools, `status`, and `deploy`
resolve `host = cache.resolve(name)` and pass it to the client (returning a clear "machine not found"
error when resolve fails), instead of relying on the client's per-call discovery.

### D2 — `status` tool

`toolStatus(client, machine, host)` → `client.status(machine, host)` → a multiline text result:
app state, hot-reload mode (`hotMode`), desired state, JDWP port (+ fallback flag), uptime, restart
count, last exit, deployed and previous sha, main class, agent and runtime versions. Unreachable →
caught and returned as an `isError` text result (never fabricated status).

### D3 — `deploy` tool

`toolDeploy(client, machine, jarPath, restart, mainClass, host)`:

1. Resolve `Path.of(jarPath)`; error if it is not a regular file.
2. `val main = mainClass ?: readMainClass(jar)`; error if still blank. `readMainClass` is a local
   manifest read (`JarFile(...).manifest?.mainAttributes?.getValue("Main-Class")`) — the same rule the
   plugin's deploy uses, kept local to `wdb-mcp` rather than depending on the plugin.
3. `client.push(machine, jar, main, restart = restart, host = host)` → `PushResult`; report the
   deployed sha on success, the error otherwise.

`restart` defaults to true. No Gradle build — the jar must already exist where the server runs (same
trust model as the CLI). This is the v1 "out of scope: deploy needs a Gradle build" boundary relaxed
to exactly the headless case: a *prebuilt* jar.

### D4 — Testability

`toolStatus` and `toolDeploy` are `internal suspend` functions taking an injectable `host`, like the
v1 `tool*` funcs; the smoke test drives them against the fake agent (status round-trip; deploy of a
temp jar; missing-jar error). `MachineCache` is tested directly with a fake `discover` lambda:
resolve hits the cache within TTL (discover called once), and refreshes after expiry / on a miss.

### D5 — Streaming logs resource (start-on-read, coalesced)

The SDK primitives exist — `ServerCapabilities.Resources(subscribe = true)`, `addResourceTemplate`,
`ReadResourceResult(TextResourceContents)`, and, on the handler's `ClientConnection` receiver,
`sendResourceUpdated(notification)` (1-arg, bound to the connection) — but there is **no public
subscribe hook**: `resources/subscribe` is handled internally by the SDK and never calls back. So the
per-machine log collector cannot be started on subscribe; it is started on **first read** of the
resource, which is the only handler-level signal we get (and the client reads it to get content
anyway).

- Advertise `resources = ServerCapabilities.Resources(subscribe = true, listChanged = false)` in
  `ServerOptions`.
- `addResourceTemplate(ResourceTemplate(uriTemplate = "wdb://logs/{machine}", name = "Machine logs",
  description = ..., mimeType = "text/plain")) { conn, req, vars -> ... }`. `vars["machine"]` is the
  target; resolve its host via the `MachineCache` (D1).
- A collector registry keyed by `(sessionId, machine)` (the connection exposes `getSessionId()`).
  On read: if no collector for that key, launch one on the server scope that tails
  `client.logs(machine, host)`, appending each `LogLine` to a bounded ring buffer (≤ ~500 lines) and
  marking the buffer dirty; a coalescing loop calls `conn.sendResourceUpdated(
  ResourceUpdatedNotification(ResourceUpdatedNotificationParams("wdb://logs/$machine")))` at most
  every ~500ms while dirty. The read returns the current ring buffer as
  `TextResourceContents(text, uri, mimeType = "text/plain")`.
- Lifecycle: capture the `ServerSession` from `createSession(...)` in `main`; on `session.onClose`,
  cancel all collectors. (No unsubscribe hook, so a collector lives until session close — acceptable
  for a single-client stdio server; an idle stop can come later.)

**Why not `sendLoggingMessage` push (Approach B):** less code, but it is a side-channel that clients
render inconsistently and does not feed the model as tool/resource content; the resource model is
more idiomatic and lets the agent pull the tail on demand. The one-shot `logs` tool is kept either
way.

## Non-Goals

- Any Gradle build inside `deploy` (jar must be prebuilt).
- An explicit stop/unsubscribe tool or idle-timeout for the logs collector (relies on session close
  for now).
- Protocol/agent/client changes — none; this is all in `wdb-mcp`.

## Risks

- **No subscribe hook** — the collector starts on first read rather than on subscribe; slightly
  against MCP grain but the only handler-level signal available in SDK 0.13.
- **Chattiness / client behavior** — high log volume is bounded by the ring buffer and ≤500ms
  coalesced updates; still, a client's handling of resource-updated (re-read) varies, and streaming's
  value to an agent is lower than to a human UI. The bounded `logs` tool remains the reliable path.
- **Stale cache** — a machine that changes address within the TTL resolves to the old address and the
  call fails; `list_machines` forces a refresh and the short TTL bounds the window.
- **Arbitrary jar path in `deploy`** — inherits the wall's open-agent trust model (same as the CLI);
  the jar is read from the server's own filesystem. Noted, not gated.
