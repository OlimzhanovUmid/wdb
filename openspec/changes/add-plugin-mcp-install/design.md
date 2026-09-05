## Context

See proposal.md — Why. Today `wdb` is registered by hand and only from a built repo. Two existing patterns anchor this design:

- `GradleSignatureExclude` (change add-plugin-exclude-signatures-action): guarded, undoable text edit of a config/build file under `WriteCommandAction`, with a copy-to-clipboard + open-file fallback on any doubt. This is the template for touching user config safely.
- The current registered entry format in `~/.claude.json` (user scope, top-level `mcpServers`):
  ```json
  "wdb": { "type": "stdio", "command": "<abs path to wdb-mcp.bat, forward slashes>", "args": [], "env": {} }
  ```
- `wdb-mcp` applies the Gradle `application` plugin, so `:wdb-mcp:distZip` already produces `wdb-mcp/build/distributions/wdb-mcp-<ver>.zip` (`bin/wdb-mcp.bat` + `lib/*.jar`). The `.bat` invokes `java` from `JAVA_HOME`/PATH.
- Repo: `github.com/OlimzhanovUmid/windows-debug-bridge`. CI (`ci.yml`) is windows-latest build+test; there is no release workflow yet.

## Goals / Non-Goals

**Goals:**
- Publish a repo-independent, prebuilt `wdb-mcp` distribution on GitHub Releases.
- One plugin action that downloads it, installs it to a stable per-user path, and registers it at user scope — with the operator choosing the write method and seeing warnings first.
- Never corrupt `~/.claude.json`; always have a manual fallback.

**Non-Goals:**
- Bundling a JRE — the `.bat` uses the user's Java; we only warn if none is found.
- Auto-update of an already-installed server (install is explicit; re-running replaces on confirm).
- Registering MCP clients other than Claude Code in this change (design leaves room, but only `~/.claude.json` / `claude mcp add` are implemented).
- Signing / notarizing the distribution.

## Decisions

**D1 — Distribution = `:wdb-mcp:distZip`, published by a tag-triggered workflow.**
Reuse the application plugin's zip instead of a custom fat-jar. New `.github/workflows/release.yml`: on `push` of tag `v*`, set up JDK 21, run `./gradlew :wdb-mcp:distZip`, then upload `wdb-mcp/build/distributions/wdb-mcp-*.zip` as a Release asset (via `softprops/action-gh-release`, or `gh release create`/`gh release upload` which is preinstalled on GitHub runners). Version derives from the tag. *Alt considered:* upload on every push — rejected (noise, no stable "latest release").

**D2 — Install location: `~/.wdb/mcp/` (stable, repo-independent).**
Unzip the archive there; the archive's inner dir is `wdb-mcp/`, so the launcher lands at `~/.wdb/mcp/wdb-mcp/bin/wdb-mcp.bat`. Clean/replace that subtree on reinstall so stale `lib/*.jar` from an older version don't linger. *Alt:* a versioned dir per release — rejected as unneeded complexity; a single current install is enough and keeps the registered path stable.

**D3 — Download via GitHub Releases REST API, no `gh` dependency on the user side.**
Query `GET /repos/OlimzhanovUmid/windows-debug-bridge/releases/latest`, pick the asset matching `wdb-mcp-*.zip`, download over HTTPS with IntelliJ's `HttpRequests`. No auth needed for public-repo release assets. Handle: no releases yet, no matching asset, network failure → each maps to an actionable error + the manual fallback. *Alt:* shell out to `gh` — rejected (not guaranteed installed on the user's machine, and IDE-native HTTP integrates with progress/cancellation).

**D4 — Registration method chosen by the operator at run time (per the user's decision).**
Present a dialog listing warnings first, then two methods:
- *Edit `~/.claude.json` directly* — parse JSON, set `mcpServers.wdb` to the D-format entry (path with forward slashes), write back preserving all other content and formatting as much as practical. Mirror `GradleSignatureExclude`: if the file is missing create a minimal one; if it is unreadable / not the expected shape, do **not** edit — fall back.
- *Run `claude mcp add`* — `claude mcp add wdb -s user -- <launcher>` via a process call; only offered/attempted when `claude` is resolvable on PATH, else greyed out with a note. On non-zero exit, fall back.
Idempotency: if `mcpServers.wdb` already exists and points at a valid launcher, report "already registered" and require explicit confirm before overwriting.
*Alt:* pick one method unconditionally — rejected; the user explicitly wants the choice and the warnings surfaced.

**D5 — Warnings surfaced before any write.**
Compute and show: JDK discoverable? (probe `JAVA_HOME`, then `java` on PATH); existing `wdb` entry that would be overwritten; the resolved launcher path to be registered. The `.bat` needs Java at *run* time, so a missing JDK is a warning, not a hard block.

**D6 — Placement in the UI.**
Add the action to the tool-window toolbar (`WallUi`, next to Configure-deploy / Refresh) routed through `WdbService`, consistent with existing plugin actions. The heavy work (download/unzip) runs under a background task with progress; the method dialog and fallbacks run on EDT.

**D7 — New helper `McpInstall` (object), analogous to `GradleSignatureExclude`.**
Owns: locate/create install dir, download+unzip, JDK probe, build the entry, the two write paths, and the clipboard/open fallback, returning an `Outcome` enum (`INSTALLED`, `ALREADY`, `FALLBACK`, `CANCELLED`, `FAILED`). Keeps `WdbService`/`WallUi` thin.

## Risks / Trade-offs

- **User has no JDK** → the MCP server won't start when the client launches it. *Mitigation:* D5 warning at install time with the exact requirement (JDK 21).
- **Corrupting `~/.claude.json`** (it also holds unrelated Claude Code state) → *Mitigation:* parse-and-reserialize only the `mcpServers.wdb` key; on any parse/shape doubt, do not write — clipboard + open-file fallback (D4). No partial writes.
- **`claude mcp add` writes a different/wrong scope or format across CLI versions** → *Mitigation:* pass `-s user` explicitly; treat the CLI as opaque and just report its outcome; the direct-edit method is the deterministic path.
- **No release published yet / offline** → *Mitigation:* explicit "no release found" / network errors, with fallback text pointing at the manual command; the release workflow ships in the same change so a tag produces an asset.
- **Latest release asset name drift** → *Mitigation:* match by `wdb-mcp-*.zip` glob, not an exact name; fail clearly if none matches.

## Migration Plan

1. Merge; push a tag `v<x.y.z>` to trigger `release.yml` and produce the first downloadable asset.
2. Plugin action becomes usable once at least one release with a `wdb-mcp-*.zip` asset exists.
3. Rollback: revert the plugin action + workflow; already-registered `~/.claude.json` entries are unaffected (they point at a local path the user can keep or remove).

## Open Questions

- None blocking. (Registering non-Claude-Code MCP clients is deliberately deferred, not an open question for this change.)
