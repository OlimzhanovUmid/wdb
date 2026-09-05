## Context

See proposal.md — Why. Anchors that already exist:

- `add-release-pipeline` (shipped) publishes `wdb-agent-installer-<ver>.zip` and `latest.json` at the stable `https://github.com/OlimzhanovUmid/wdb/releases/latest/download/latest.json` URL. `latest.json.agent` = `{version, asset, url, sha256, size}` — the last two mirror the agent's own `AgentUpdateManifest`.
- The self-update wire path is proven: `wdb-client` `sendAgentUpdate(address, zip, version, onProgress)` streams a zip → agent verifies size+sha256 → extract → switch → relaunch. **Reused unchanged.**
- `wdb-plugin` `WdbService`: `MachineUi.agentVersion` is already surfaced; `forEach(machines, label, op)` runs a per-machine op with per-machine outcome reporting; heavy ops run off-EDT.

## Goals / Non-Goals

**Goals:**
- Fetch the latest agent version from the manifest, flag per-machine update availability.
- One-click rollout: download the release installer, verify integrity, push over the existing wire to selected machines; download once for a fleet.

**Non-Goals:**
- **Agent self-pull** from GitHub (needs wall-box internet) — deferred.
- Changing the wire protocol or the agent (`sendAgentUpdate` and the agent are untouched).
- Auto-updating the agent without operator action (rollout stays explicit).

## Decisions

**D1 — Reuse `sendAgentUpdate` verbatim; only the zip's origin changes.**
Today the zip comes from a local build; now it comes from a GitHub download. Same manifest → zip → verify → relaunch. No client/agent/protocol change, so the risky part (self-update) keeps its proven behavior. *Alt:* a new agent "pull" op — rejected (self-pull non-goal; would need wall internet + new agent code).

**D2 — Manifest + installer fetched via the stable `releases/latest/download` URLs, with IntelliJ `HttpRequests`.**
Read `latest.json` from `.../releases/latest/download/latest.json`; the agent installer from `latest.json.agent.url`. Public repo → no auth. IDE-native HTTP integrates with progress + cancellation. *Alt:* GitHub REST API — unneeded; the stable download URL is simpler and matches how the pipeline is meant to be consumed.

**D3 — New `ReleaseSource` helper (download + verify + per-version cache).**
A plugin helper that: fetches/parses `latest.json`; for a component, downloads its asset to a per-version cache dir (`~/.wdb/agent-updates/wdb-agent-installer-<ver>.zip`), returning the cached file if size+sha256 already match; verifies size+sha256 against the manifest before returning; surfaces typed failures (no manifest, network, integrity). This is the reusable GitHub-download core that `cli-install` / `add-plugin-mcp-install` will share (extract common bits later). *Alt:* inline it in `WdbService` — rejected; keep the service thin and the logic testable + reusable.

**D4 — Version comparison: SemVer, offer only on strictly-newer.**
Compare `MachineUi.agentVersion` to `latest.json.agent.version` with a small SemVer compare (numeric dotted parts); offer an update only when the published version is strictly greater, so we never offer a downgrade or churn equal versions. Unparseable/`?` machine version → treat as "unknown", offer allowed but marked. *Alt:* string inequality — rejected (would offer downgrades / equal-version noise).

**D5 — UI: per-machine + all-machines, consistent with the existing toolbar.**
Surface "update available" on a machine card (e.g., the agent-version label styled/badged) and an "Update agent" action gated to machines with an update, routed through `WdbService` using the existing `forEach` per-machine outcome pattern with a progress indicator (reuse the deploy-progress channel). The manifest fetch happens on refresh / on demand, cached briefly. *Alt:* a separate modal — rejected; stay in the tool window.

**D6 — Cache once per version for fleet rollout.**
`ReleaseSource` downloads to the per-version cache path; a rollout to N machines calls it once (cache hit for the rest), then `sendAgentUpdate` streams the cached file to each. Integrity is re-checked on cache hit (size+sha256) so a corrupted cache is re-downloaded.

## Risks / Trade-offs

- **Manifest/asset unreachable (offline, no release)** → *Mitigation:* typed "no update" state; the update UI simply doesn't appear; no blocking error (D1 scenario).
- **Agent version string unparseable** (`?`, non-semver) → *Mitigation:* treat as unknown; don't crash the compare; allow an explicit update but don't auto-flag "newer".
- **Large download (agent installer ~159 MB)** → *Mitigation:* background task + progress + cancellation; per-version cache so it's downloaded once, not per machine.
- **First roll onto a fixed self-update still uses the outgoing agent's relaunch path** (known from `fix-selfupdate-relaunch`) → not changed here; unaffected by where the zip came from.

## Migration Plan

1. Ship the plugin change; it consumes the already-published `latest.json` + agent installer.
2. Verify live: a wall on an older agent shows "update available" → roll it → machine restarts on the new version.
3. Rollback: revert the plugin change; agents already updated are unaffected.

## Open Questions

- None blocking. (Whether to later extract a shared `ReleaseInstall` across agent/cli/mcp is an implementation refactor, not a spec/approach question.)
