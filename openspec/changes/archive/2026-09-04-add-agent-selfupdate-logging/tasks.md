## 1. Log helper

- [x] 1.1 Add `AgentInstallLayout.log(event: String)`: best-effort append to `<agentDir>/agent-update.log`, one timestamped line (`Instant.now() [<AGENT_VERSION>/pid <pid>] <event>`), `runCatching` so it never throws.

## 2. Instrument the lifecycle

- [x] 2.1 `SelfUpdater.apply`: log `apply start ver/zipSize`, size/checksum reject, `extracted`, `switchTo <v> (prev=<p>)`, `marker written`, and the catch-branch error.
- [x] 2.2 `AgentSelfRestart.productionRestart`: log previous version, watchdog exe path + `exists`, and spawn ok/error.
- [x] 2.3 `AgentSelfRestart.superviseUpdate`: log `supervise start` (marker present?), `schtasks run exit=<code>`, each health poll (elapsed + marker present/absent), and outcome (`committed <v>` or `revert -> <p> (deadline)`).
- [x] 2.4 `Main.runAgent` healthy start: when `installBase != null`, log `boot ver/installBase/tcp` and whether the marker was cleared.

## 3. Version + build

- [x] 3.1 Bump `wdbAgentVersion` 0.2.9 → 0.2.10 (`gradle.properties`).
- [x] 3.2 `:wdb-agent:build` green (existing tests unaffected; `log` is best-effort). Optional: a unit test that `apply` against a temp `AgentInstallLayout` writes an `agent-update.log` with the expected lines.

## 4. Verify (roll + read)

- [x] 4.1 Build the 0.2.10 app-image + zip. Get a 0.2.10 agent onto wall 1 (manual/local install if self-update still reverts).
- [x] 4.2 Trigger an `agent-update` (or the next roll) and read `<base>/agent/agent-update.log` on the wall — confirm it captures apply → restart → supervise → boot, and shows why a rollback happens (task launch version, `detectInstallBase`, or health deadline). Then screenshot a third-party app to close out the binary-screenshot change once 0.2.10 (with the blob wire) is live.
