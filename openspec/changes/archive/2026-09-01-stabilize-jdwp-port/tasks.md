# Tasks

## 1. Protocol

- [x] 1.1 Add an optional `jdwpPortIsFallback: Boolean = false` field to `MachineStatus` in `wdb-protocol`; verify the serialization round-trip test covers it and an older payload without the field still decodes (default false)

## 2. Agent config

- [x] 2.1 Add a configurable JDWP port to `AgentConfig` (default 5005) and persist/read it in the agent data dir alongside the machine name (`AgentPaths`); verify a unit test round-trips the persisted value and falls back to the default when unset
- [x] 2.2 Accept `--jdwp-port <n>` in `wdb-agent install` and `run` (`Main`, `Install` task XML args); verify install embeds the flag and run honours it

## 3. Supervisor

- [x] 3.1 Use the configured fixed JDWP port on launch instead of always allocating a free port; if the port is not bindable on loopback, fall back to an ephemeral port and set the fallback flag; verify with a test that a pre-bound port forces the ephemeral fallback and clears it once free
- [x] 3.2 Report the actual JDWP port and the fallback flag in `MachineStatus`; verify status reflects fixed vs fallback correctly across two launches

## 4. End-to-end

- [x] 4.1 Verify the fixed port is stable across an app restart: launch, note the JDWP port in status, trigger a restart (crash-once or push), and confirm status reports the same fixed port so an existing tunnel/IDE config stays valid
- [x] 4.2 Manual wall check: on a real box, open `debug`, restart the app (close window → auto-restart), and confirm the same forward/IDE run-config still attaches without reconfiguration _(verified on wall-02 with the repackaged agent: JDWP port was 5005, IntelliJ attached, closing the window auto-restarted the app, and status still reported 5005 (restarts=1, not a fallback) — the same tunnel/IDE run-config re-attached without any reconfiguration, unlike the pre-fix ephemeral-port behaviour)_
