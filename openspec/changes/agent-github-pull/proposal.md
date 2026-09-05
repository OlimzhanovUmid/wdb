## Why

Agent self-update works today, but the update zip is built locally and pushed by the developer's machine — you can't roll out an agent update without the repo. With `add-release-pipeline` publishing `wdb-agent-installer-<ver>.zip` + `latest.json`, the plugin can source the update from GitHub instead of a local build. Per the explore decision, the model is **plugin-mediated pull**: the plugin downloads from GitHub, then pushes over the *existing* wire protocol — so wall boxes need no internet and the proven self-update path is unchanged.

> Depends on `add-release-pipeline` (publishes the agent installer asset + `latest.json` with version/sha256/size).

## What Changes

- Plugin resolves the latest agent version from `latest.json` (`releases/latest/download/latest.json`), compares it against each machine's reported `agentVersion`, and flags machines with an update available.
- On the user's action, the plugin downloads `wdb-agent-installer-<ver>.zip` (verifying sha256+size from `latest.json`), then reuses the existing `sendAgentUpdate` wire push (manifest → zip → verify → swap → relaunch) — no protocol change.
- Downloaded installers are cached per-version so a fleet rollout downloads once, pushes to many.
- Surface per-machine rollout outcome (as existing lifecycle actions do); one failing machine doesn't abort others.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ide-integration`: add requirements for detecting an available agent update from the published manifest and for rolling it out to selected machines from the downloaded release (sourced from GitHub, delivered over the existing wire).

## Impact

- **Plugin**: new "update available" indicator + rollout action, a GitHub download+cache helper (reuse the `add-plugin-mcp-install` download pattern), verify against `latest.json`.
- **Client**: reuse `sendAgentUpdate` unchanged; the only new input is the zip's origin (downloaded vs local).
- **No agent change**, **no wire change** — wall boxes need no internet (plugin-mediated).
- **Non-goal**: agent self-pull from GitHub (would require wall-box internet); explicitly deferred.
