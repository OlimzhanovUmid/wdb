## 1. Module scaffold

- [x] 1.1 Add version-catalog entry for the Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk-server:0.13.0`).
- [x] 1.2 Create `wdb-mcp` module (Kotlin JVM `application`, mainClass `dev.wdb.mcp.MainKt`) depending on `project(":wdb-client")` + the MCP SDK; add to `settings.gradle.kts`.

## 2. MCP server + tools

- [x] 2.1 `main()`: build a `WdbClient` on a coroutine scope, create an MCP `Server`, connect it over `StdioServerTransport`, and keep it running until stdin closes.
- [x] 2.2 Machine/inspection tools: `list_machines` (discover → name/address/state), `screenshot` (→ ImageContent, base64 PNG), `semantic_tree` (→ JSON text); devtools-unavailable → `isError` result with a clear message.
- [x] 2.3 Interaction tool: `ui_action` `{machine, nodeId, kind, text?, dx?, dy?, index?}` → `client.uiAction`, report applied/not.
- [x] 2.4 Lifecycle + logs tools: `run` / `hot_run` / `stop` / `reload` and `logs` (last N lines) → report outcome.

## 3. Packaging + docs

- [x] 3.1 `installDist` launcher; note the MCP-client registration command (e.g. `claude mcp add wdb -- <launcher>`) in the module README/help.

## 4. Verify

- [x] 4.1 `:wdb-mcp:build` compiles and assembles; a unit/smoke test drives the tool handlers against `FakeAgent` (list/screenshot/ui_action round-trip) without a real MCP client.
- [x] 4.2 Register the built launcher in an MCP client (Claude Code) and confirm the tools appear; `list_machines` returns the walls, `screenshot` shows a hot machine's screen, `ui_action` taps a node, `hot_run`/`reload` work.
