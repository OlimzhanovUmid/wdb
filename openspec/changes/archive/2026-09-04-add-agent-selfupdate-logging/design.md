# Design — agent self-update logging

## Context

Self-update spans three processes: the updating agent (`SelfUpdater.apply` → `productionRestart`
spawns a watchdog from the previous binary, then exits), the watchdog (`superviseUpdate`: `schtasks
/run` → wait for the new agent to clear the marker → commit or `revertToPrevious`), and the new agent
(`Main.runAgent` clears the marker once listening). Each has an `AgentInstallLayout` (or can build one
from the base). None writes a durable trace, so a rollback leaves nothing to inspect. `AgentInstallLayout`
already owns `agentDir` — the natural home for the log.

## Decisions

### D1 — `AgentInstallLayout.log(event)`

Add one helper:

```
fun log(event: String) = runCatching {
    Files.createDirectories(agentDir)
    Files.writeString(
        agentDir.resolve("agent-update.log"),
        "${java.time.Instant.now()} [${BuildConfig.AGENT_VERSION}/pid ${ProcessHandle.current().pid()}] $event\n",
        StandardOpenOption.CREATE, StandardOpenOption.APPEND,
    )
}
```

Best-effort (swallows all errors), append-only, one line per event, stamped with time + the writing
process's agent version + pid so the three processes are distinguishable in one file.

### D2 — Log points (concrete events)

- `SelfUpdater.apply`: `apply start ver=<v> zipSize=<n>`; on mismatch `apply reject size/checksum`;
  `extracted versions/<v>`; `switchTo <v> (prev=<p>)`; `marker written`; on throw `apply error: <msg>`.
- `AgentSelfRestart.productionRestart`: `restart: prev=<p> watchdog=<exe> exists=<b> spawn=<ok|err:msg>`.
- `superviseUpdate`: `supervise start marker=<present|absent>`; `schtasks run exit=<code>`;
  `poll <elapsed>s marker=<present|absent>`; then `committed <v>` or `revert -> <p> (deadline <s>s)`.
- `Main.runAgent` (only when `config.installBase != null`): `boot ver=<v> installBase=<path> tcp=<port>`;
  `marker cleared` (or `no marker`).

Each call site already has the base/layout; `Main` builds `AgentInstallLayout(installBase)` (it already
does so for `clearMarker`).

### D3 — No-op off a versioned install

Only the production paths (which have an `installBase`) log; `detectInstallBase()` returns null for dev
/ manual runs, so `Main` skips logging then, matching the existing marker-clear guard. `log` itself is
harmless if ever called without a real base (best-effort write under a temp agentDir).

### D4 — Version bump + rollout

`wdbAgentVersion` 0.2.9 → 0.2.10 so the logged build is identifiable and distinct; this build also
carries the already-committed binary-screenshot transport. The first 0.2.10 agent likely goes on via a
manual/local install (self-update is the suspect); from then the log narrates each roll.

## Non-Goals

- Fixing the rollback itself — this change only instruments it; the fix follows once the log shows the
  cause.
- Log rotation / structured JSON — plain append text is enough for a low-frequency operator log.
- Logging anything outside the self-update lifecycle.

## Risks

- **Unbounded growth** — negligible: a handful of lines per update, appended; can add rotation later if
  ever needed.
- **Concurrent writers** (watchdog + new agent briefly overlap) — each line is a single small
  `writeString` APPEND; interleaving at line granularity is acceptable for a diagnostic log.
