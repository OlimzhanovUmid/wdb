## 1. Release source helper

- [x] 1.1 Create `wdb-plugin/.../ReleaseSource.kt`: fetch + parse `latest.json` from `releases/latest/download/latest.json` (IntelliJ `HttpRequests`), into a typed model `{component -> {version, asset, url, sha256, size}}`. Typed failures for no-manifest / network / parse. Verify with a unit test over a captured `latest.json` (parse + each error branch). — DONE: HTTP in `ReleaseSource.latestManifest()` (returns null on any failure → quiet degrade); parsing in wdb-client `parseReleaseManifest` (`ComponentRelease`), covered by `ReleaseManifestTest` (parse + unknown-key forward-compat).
- [x] 1.2 Add `downloadVerified(component)`: download the component's asset to the per-version cache, return the cached file if size+sha256 already match, else download; verify size+sha256 against the manifest before returning; re-download on a corrupted cache. — DONE in `ReleaseSource.downloadVerified` (streamed copy w/ progress, cache reuse on match, `IntegrityException` on mismatch, corrupt cache re-downloaded). NOTE: HTTP path not unit-tested (needs a local server); integrity/cache logic exercised live in 4.1.
- [x] 1.3 Add a SemVer compare (numeric dotted parts) + `isNewerVersion(current, latest)` that returns true only when `latest` is strictly greater; unparseable `current` → "unknown". Verify with unit tests (older/equal/newer/`?`). — DONE in wdb-client `isNewerVersion`; `ReleaseManifestTest` covers older/equal/downgrade/`?`/non-numeric.

## 2. Detect + surface availability

- [x] 2.1 In `WdbService`, fetch the manifest (on refresh / on demand, cached briefly) and expose per-machine "agent update available" state derived from `MachineUi.agentVersion` vs the manifest agent version. Verify: with a machine on an older version the state is true; unreachable manifest → false, no error. — DONE: `refresh()` sets `_agentRelease` (best-effort, IO); `agentUpdateAvailable(m)` = `isNewerVersion`; null manifest → false.
- [x] 2.2 In `WallUi`, badge/indicate machines with an available update near the agent-version label. Verify in `runIde`: a stale-agent machine shows the indicator; up-to-date machines don't. — DONE: card shows `"<cur> → <new>"` in amber when an update is available. (runIde eyeball pending with a real stale wall — see 4.1.)

## 3. Rollout action

- [x] 3.1 Add `WdbService.updateAgent(machines)`: `ReleaseSource.downloadVerified(agent)` once, then per machine call the agent-update wire push (`client.agentUpdate` → `sendAgentUpdate`) streaming the cached zip with progress (reuse the deploy-progress channel). Verify: integrity failure aborts before any push; one unreachable machine doesn't abort others. — DONE: download once (integrity failure → notify + return before any push); per-machine loop with `runCatching` outcome + per-machine progress; `refresh()` after.
- [x] 3.2 Wire an "Update agent" toolbar/action gated to machines with an available update (per-machine + all), routed through `updateAgent`. Verify in `runIde`: action enabled only when an update is available; disabled/absent otherwise. — DONE: `WallUi` ActionRow "Update agent" (`AllIconsKeys.Actions.Download`) enabled only when `agentUpdateAvailable`; all-row targets the machines that need it. (runIde eyeball — see 4.1.)

## 4. Verification

- [ ] 4.1 Live end-to-end on a wall running an older agent: open the tool window → "update available" shows → invoke Update agent → plugin downloads `wdb-agent-installer-<ver>.zip` from the release, verifies, pushes over the wire → the machine restarts and `status` reports the new agent version. Confirm a second machine reuses the cached download. — PENDING: needs a wall on an agent older than 0.2.15 (the published version). Wall "Jasur" is already 0.2.14 → will flag "0.2.14 → 0.2.15".
- [x] 4.2 Run `openspec validate agent-github-pull --strict` and `./gradlew :wdb-plugin:build`; both pass. — VERIFIED: validate OK; `:wdb-plugin:build` + `:wdb-client:test` green (incl `ReleaseManifestTest`).
