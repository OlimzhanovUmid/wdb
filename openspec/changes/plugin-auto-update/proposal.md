## Why

Once `add-release-pipeline` publishes the plugin zip + `updatePlugins.xml` on every release, the IDE can auto-update the plugin — but only if it's told where to look. We want **both** channels (explore decision): JetBrains Marketplace for zero-setup public users, and a self-hosted custom repo for fast internal iteration without review latency.

> Depends on `add-release-pipeline` (publishes `wdb-plugin-<ver>.zip`, `updatePlugins.xml` at the stable `releases/latest/download/...` URL).

## What Changes

- **Marketplace channel**: add `publishPlugin` to CI (token-gated) so tagged releases also publish to JetBrains Marketplace. Requires a vendor account; first upload waits for review.
- **Custom-repo channel**: document / provide the one-time repo URL (`.../releases/latest/download/updatePlugins.xml`) users add under Settings → Plugins → Manage Repositories; the IDE then auto-checks and updates. Optionally a small in-plugin affordance / README snippet to register it.
- Surface plugin↔agent protocol skew: when the plugin hits `VERSION_MISMATCH` against an agent, show an actionable message (update the plugin, or update the agent) rather than a raw error.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ide-integration`: add requirements for plugin auto-update via a published channel (Marketplace and/or custom repo) and for surfacing protocol-version mismatch actionably.

## Impact

- **CI**: `publishPlugin` step + `PUBLISH_TOKEN`/`CERTIFICATE_*` secrets; Marketplace vendor setup (out-of-band).
- **Plugin**: minor UX for the mismatch message; optional custom-repo registration helper.
- **Docs**: README section on installing/auto-updating via each channel.
- **No wire change** — `PROTOCOL_VERSION` compat already exists.
