## 1. Relaunch mechanism

- [x] 1.1 In `AgentSelfRestart`, add `relaunch(layout: AgentInstallLayout)`: if `layout.launchCmd` exists → `ProcessBuilder("cmd", "/c", layout.launchCmd.toString()).start()` (detached, no `waitFor`); else fall back to `ProcessBuilder("schtasks", "/run", "/tn", TASK_NAME).start()`. Log which path was taken (`relaunch via launch.cmd` / `relaunch via schtasks`).
- [x] 1.2 `superviseUpdate`: replace both `schtasks /run` calls (initial new-version launch, and post-`revertToPrevious` launch) with `relaunch(layout)`. Keep the marker poll / 60s deadline / revert logic unchanged.

## 2. Version

- [x] 2.1 Bump `wdbAgentVersion` 0.2.10 → 0.2.12 (`gradle.properties`).

## 3. Verify

- [x] 3.1 `:wdb-agent:build` green (existing self-update tests unaffected — `superviseUpdate` is a production path; `SelfUpdater.apply` is what's unit-tested).
- [x] 3.2 Build the 0.2.12 app-image + zip; roll it onto the wall (first roll may still use the outgoing agent's racy watchdog — retry until committed, or manual install). Then trigger a SECOND `agent-update` from the now-0.2.12 agent and read `agent-update.log`: it should show `relaunch via launch.cmd`, the new version's `boot`, and `committed …` — no revert.
