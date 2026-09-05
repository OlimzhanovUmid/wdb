## 1. Discovery cache

- [x] 1.1 Add `MachineCache(ttlMs, discover: suspend () -> List<Machine>)` in `wdb-mcp`: `list()` refreshes + returns machines; `resolve(name): AgentAddress?` returns the cached machine's address, refreshing (under a `Mutex`) on a miss or when older than `ttlMs`.
- [x] 1.2 In `main()`, build the cache over `client.discover()`; make `list_machines` use `cache.list()`, and have `screenshot`/`semantic_tree`/`ui_action`/lifecycle tools resolve `host = cache.resolve(name)` and pass it to the client (clear "machine not found" error when resolve fails).

## 2. status tool

- [x] 2.1 `toolStatus(client, machine, host)` → `client.status` → multiline text (app state, hotMode, desired, jdwpPort + fallback, uptime, restarts, last exit, deployed/previous sha, main class, agent/runtime versions); unreachable → `isError` text. Register a `status` tool that resolves host via the cache.

## 3. deploy tool

- [x] 3.1 Local `readMainClass(jar)` (manifest `Main-Class`) in `wdb-mcp`.
- [x] 3.2 `toolDeploy(client, machine, jarPath, restart, mainClass, host)`: validate the jar is a regular file; resolve main class (arg or manifest) or error; `client.push(machine, jar, main, restart, host)`; report deployed sha / error. Register a `deploy` tool (`{machine, jarPath, restart?, mainClass?}`) resolving host via the cache; `restart` defaults to true.

## 4. Streaming logs resource

- [x] 4.1 Advertise `resources = ServerCapabilities.Resources(subscribe = true, listChanged = false)` in `ServerOptions`; capture the `ServerSession` from `createSession(...)` in `main` for cleanup.
- [x] 4.2 A `LogCollectors` registry keyed by `(sessionId, machine)`: on demand, launch a collector on the server scope that tails `client.logs(machine, host)` into a bounded ring buffer (≤500 lines) and, via a coalescing loop (≤500ms while dirty), calls `conn.sendResourceUpdated(ResourceUpdatedNotification(ResourceUpdatedNotificationParams("wdb://logs/$machine")))`. Cancel all collectors on `session.onClose`.
- [x] 4.3 `addResourceTemplate(ResourceTemplate("wdb://logs/{machine}", name, description, mimeType = "text/plain")) { conn, req, vars -> }`: resolve `vars["machine"]` host via the cache, ensure a collector for `(conn.sessionId, machine)`, return the ring buffer as `ReadResourceResult(listOf(TextResourceContents(text, uri, "text/plain")))`; error content when the machine can't be resolved.

## 5. Verify

- [x] 5.1 `:wdb-mcp:build` compiles + assembles; smoke tests: `MachineCache` (resolve hits cache within TTL — discover called once; refresh after expiry/miss), `toolStatus` round-trip vs the fake agent, `toolDeploy` of a temp jar (+ missing-jar error), and the logs-resource read returns buffered lines (collector fed by the fake agent's `logEvents`).
- [x] 5.2 Rebuild the launcher (`:wdb-mcp:installDist`), reconnect `wdb` in an MCP client, and confirm live: `status` returns a machine's status; repeated screenshot/ui_action calls after `list_machines` don't re-discover each time; `deploy` pushes a prebuilt jar and the app restarts; reading `wdb://logs/<machine>` returns recent lines and updates as new lines arrive.
  - Verified live on wall "1" via Claude Code: `status`, cache (many machine-addressed calls, no per-call discovery lag), full devtools loop (screenshot/semantic_tree/ui_action click+set_text+scroll_to_index) all work. `deploy` pushes the prebuilt uber jar and restarts — deployed sha changed, uptime reset, RUNNING, hot mode restored. Logs resource `wdb://logs/1` is readable and returns fresh content on re-read (`(no logs yet)` → 4 lines as they arrived). **Client caveat:** Claude Code's MCP client does not surface `resources/updated` push notifications — it re-reads on demand, so the resource is pollable but not "pushed" on this client. The server sends the notification correctly (D5); consuming it is client-side and out of scope. The one-shot `logs` tool remains the reliable path.
