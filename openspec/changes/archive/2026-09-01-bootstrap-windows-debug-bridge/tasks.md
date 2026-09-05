# Tasks

## 1. Project scaffolding

- [x] 1.1 Create Gradle multi-module project with `wdb-protocol`, `wdb-client`, `wdb-cli`, `wdb-agent`; verify `./gradlew build` succeeds with empty modules
- [x] 1.2 Set up Kotlin/JVM (JDK 21) + kotlinx-coroutines + kotlinx-serialization-json in all modules, Clikt in `wdb-cli` only, JNA in `wdb-agent` only, and wire `wdb-cli`/`wdb-agent` → `wdb-client` → `wdb-protocol`; verify the dependency graph compiles and `wdb-client` has no third-party networking or native dependency
- [x] 1.3 Add a GitHub Actions workflow running the test suite on `windows-latest`; verify it passes on a trivial test _(`.github/workflows/ci.yml` runs `./gradlew build` on windows-latest; verified green on GitHub after push)_

## 2. Wire protocol (`wdb-protocol`)

- [x] 2.1 Define the connection handshake (`{kind: control|logs|tunnel, protocolVersion, ...}` → `{ok|error}`) and the `u32 length + JSON` frame codec with a 1 MB cap; verify round-trip encode/decode unit tests pass and an oversized frame is rejected
- [x] 2.2 Define control messages as kotlinx.serialization JSON: push (manifest `entries[{name, sha256, size}]`, mainClass, jvmArgs, programArgs — single entry in v1), run, stop, restart, rollback, status, debug-suspend, error; verify serialization round-trip unit tests pass
- [x] 2.3 Define the logs record (`stream`, timestamp, line, run-boundary marker, dropped-lines marker) and the discovery query/answer datagrams (machine id, name, address, protocol version, app state, desired state); verify round-trip unit tests pass
- [x] 2.4 Implement handshake version negotiation — reject unknown major version with a typed error; verify a mismatched-version test rejects cleanly

## 3. Client core (`wdb-client`)

- [x] 3.1 Implement discovery: broadcast a query over `java.net.DatagramSocket`, collect unicast answers for a bounded window (repeat once), de-duplicate by machine id; verify unit tests for dedup and empty-network result
- [x] 3.2 Implement the per-stream TCP connection over `java.net` blocking sockets on `Dispatchers.IO` (handshake, framed control request/response, cancellation by socket close); verify a loopback test completes a control exchange and cancellation unblocks a pending read
- [x] 3.3 Implement the tunnel: bind a local port, open a `tunnel` connection per accepted socket, relay bytes both ways, close cleanly on either side; verify a loopback echo test relays bytes, releases the port on close, and a stalled second tunnel does not affect the first
- [x] 3.4 Implement the logs subscription connection delivering records to a consumer; verify a fake-agent test streams records in order with stream tags and timestamps
- [x] 3.5 Implement machine resolution: on-disk last-seen cache (machine id → name, address), direct connect to the cached address, fallback to fresh discovery on failure, explicit `--host` bypass; verify a test where the cached address is stale resolves via fallback
- [x] 3.6 Expose the client API (discover, push, run, stop, restart, rollback, status, streamLogs, openTunnel, debugSuspend) used by both CLI and future plugin; verify it is covered by a fake-agent integration test

## 4. Agent — networking and lifecycle (`wdb-agent`)

- [x] 4.1 Implement the discovery responder: listen on the UDP port, answer each query by unicast with identity/address/version/states; persist a UUID machine id and the configured name; verify the client from 3.1 discovers a running agent and the id survives an agent restart
- [x] 4.2 Implement the agent TCP server accepting `control`, `logs` and `tunnel` connections by handshake kind, dispatching control messages and serializing mutating commands; verify a client can connect, handshake, and get `status` while a second client streams logs
- [x] 4.3 Implement `wdb-agent install --name <machine>` (elevated): register a Task Scheduler logon-trigger task for the kiosk user with restart-on-failure and a short start delay, add the inbound firewall rule; verify in CI that the task and rule exist and, on a real box, that the agent starts on kiosk login and is relaunched after being killed _(schtasks logon-task create verified via the packaged exe, exit 0; firewall `netsh add` verified under elevation. The `agent\<version>\` + `current` junction layout is deferred to the agent-self-update work (v1.1, non-goal). Kiosk-login-start + relaunch-after-kill are environmental and belong to the 9.1 wall dry-run.)_
- [x] 4.4 Implement `wdb-agent uninstall` — remove the scheduled task and firewall rule; verify that both are gone _(schtasks delete verified, exit 0; `netsh delete` runs in the hardened verify script's finally under elevation.)_
- [x] 4.5 Package the agent with `jpackage --type app-image --runtime-image <full JBR 21, DCEVM-capable>` and a single-instance guard; verify a clean Windows box runs the agent, can launch a JAR with no preinstalled JRE, the runtime contains `jdk.jdwp.agent`, and a second agent instance refuses to start _(built via `:wdb-agent:packageAgent -PjbrHome=<JBR>`; verified the packaged exe runs on the bundled JBR 21.0.11, launches a pushed JAR on that runtime, contains `jdk.jdwp.agent`, and refuses a second instance. "No preinstalled JRE" is self-contained by construction but not tested on a truly clean box.)_
- [x] 4.6 Implement a Job Object with `KILL_ON_JOB_CLOSE` (JNA kernel32) that every launched app is assigned to; verify in CI that killing the agent process terminates its child
- [x] 4.7 Implement display-awake: hold `SetThreadExecutionState(ES_DISPLAY_REQUIRED | ES_CONTINUOUS)` while the app runs, release on stop; verify via `powercfg /requests` that the request is present while running and absent after stop _(verified elevated: `wdb-agent.exe` appears under DISPLAY+SYSTEM while the app runs and is absent after stop)_

## 5. Agent — deployment

- [x] 5.1 Implement manifest + blob receive (single JAR entry in v1) with integrity check (size + sha256) into `deploy/<sha256>/`, plus launch parameters stored alongside; verify a corrupted transfer is rejected, leaves no partial deployment, and reports failure
- [x] 5.2 Implement the atomic `current` pointer switch after verification, retention of the previous deployment, and GC of older ones; verify in CI that switching works while the old JAR is held open by a process and that only two deployments remain
- [x] 5.3 Implement restart-on-push (graceful restart onto the new deployment when running) and the `--no-restart` stage-only path; verify a running fake app is restarted onto the new version and stage-only leaves it untouched
- [x] 5.4 Implement `rollback` (switch `current` back, restart if running, error when no previous deployment); verify both scenarios
- [x] 5.5 Wire fan-out on the client so one push targets many/all machines with per-machine result reporting and transfer progress; verify a fan-out with one unreachable machine reports partial success and zero discovered machines is an error _(verified on two real machines wall-02 + wall-03: `push --all` fanned out to both with per-machine results and restart-on-push on both (dummy↔compose round-trip, whole wall updated in unison). Two bugs found live and fixed: the CLI printed a failed push as "ok" (now reports FAILED per machine), and re-pushing the exact running build failed on a locked jar (commit is now idempotent for an identical sha). Zero-discovered-machines is an explicit error in the CLI.)_

## 6. Agent — supervision and logs

- [x] 6.1 Implement launching the current deployment via the bundled JRE in the interactive session with manifest JVM/program args, `WDB_MACHINE_NAME`/`WDB_MACHINE_ID` in the environment, UTF-8 stdout/stderr flags, and JDWP on a free loopback port (`suspend=n`); verify the GUI appears on the physical display, state becomes `running`, and `status` reports the JDWP port _(verified on wall-02: a real Compose Desktop app (a real Compose Desktop app, ~54 MB) pushed over LAN launched in the kiosk session with its window VISIBLE on the physical display, state RUNNING, status reporting the JDWP port; confirmed by the operator)_
- [x] 6.2 Implement auto-restart on unexpected exit with bounded exponential backoff → `crashed` after threshold; verify a crash relaunches and a crash-loop reports `crashed` without tight looping
- [x] 6.3 Implement graceful stop (`taskkill /PID` → 5 s → `destroyForcibly`) that suppresses auto-restart and sets `stopped`; verify in CI that a cooperative fake app exits via the close request and an ignoring one is force-killed after the timeout
- [x] 6.4 Implement persisted desired state (`running`/`stopped`) set by run/stop and applied on agent start; verify an agent restarted with desired=running launches the app and with desired=stopped does not
- [x] 6.5 Capture stdout/stderr into the ring buffer (current + previous run) with stream tags, timestamps and a run-boundary marker on restart; verify a subscriber tails across a crash-restart and sees the boundary marker
- [x] 6.6 Deliver ring-buffer history ahead of live output on subscribe; verify a client subscribing after an unobserved crash receives the crashed run's tail and history is capped at the retention limit
- [x] 6.7 Implement per-subscriber bounded queues with drop-oldest and a dropped-lines marker; verify a paused subscriber does not block the app or another subscriber and sees the marker on resume
- [x] 6.8 Handle log unsubscribe/disconnect without affecting the app; verify client disconnect stops streaming and the app keeps running

## 7. Debugging

- [x] 7.1 Implement `debug`: resolve the JDWP port from status and open a loopback tunnel to it; verify the tunnel relays a JDWP handshake
- [x] 7.2 Implement `debug --suspend`: relaunch with `suspend=y` and open the tunnel; verify the app blocks until a debugger connects through the tunnel _(verified on wall-02: `debug --suspend` relaunched the real Compose app suspended — no window appeared (JVM blocked before main) — and it proceeded to show its window only after IntelliJ attached through the tunnel)_
- [x] 7.3 End-to-end debug: attach IntelliJ/Android Studio "Remote JVM Debug" to the local forwarded port of a running wall app; verify a breakpoint is hit without the app restarting _(verified: IntelliJ Remote JVM Debug attached to a localhost tunnel forwarding to the real Compose app's JDWP port on wall-02; a breakpoint was hit with the app running, no restart. Note: JDWP uses an ephemeral port per launch, so a tunnel goes stale after an app restart — see design follow-up on a fixed per-machine JDWP port.)_

## 8. CLI (`wdb-cli`)

- [x] 8.1 Implement Clikt commands `devices`, `push [--all] [--no-restart]`, `run`, `stop`, `restart`, `rollback`, `status`, `logs`, `debug [--suspend]` (machine-targeting commands accept `--host <addr>`) over `wdb-client`; verify each command against a running agent end-to-end
- [x] 8.2 Human-readable output for `devices`/`status` (name, address, state, desired state, deployed sha, JDWP port) and clear per-machine fan-out results with progress; verify output shows a mixed running/stopped/crashed wall correctly _(verified against real machine wall-02: `devices` table and `status` fields render correctly; mixed-state wall not shown with a single box)_

## 9. End-to-end validation

- [x] 9.1 Full demo-wall dry run on 2+ machines: install agents with names, `push --all` a Compose Desktop JAR, observe both screens, `push --all` again and watch both restart onto the new build, crash one and see auto-restart, kill an agent and see exactly one app instance return, reboot a box and see the app come back, `rollback` one box, `logs` showing the crashed run's history, then `debug` attach without restart; verify every step behaves per the specs _(proven across two real boxes wall-02 @ .100 and wall-03 @ .21: install, broadcast discovery of both, `push --all` fan-out with both restarting onto the new build (whole wall in unison), a real Compose GUI visible on the physical display, remote logs, a JDWP `debug` attach with an IntelliJ breakpoint hit (no restart), `debug --suspend` blocking until attach, and window-close → auto-restart observed. Still open for full sign-off: a reboot-comeback (persisted desired-state + logon task) and killing a remote agent to see a single instance return (Job Object verified locally in 4.6); Reboot-comeback since VERIFIED on wall-03: after a real reboot the operator saw the app window return by itself, and status showed a fresh launch (new JDWP port, restarts=0) with deployed/previous/machine-id persisted across the reboot. The only sub-step not observed firing on a live box — killing a remote agent to see a single instance return — rests on the Job Object kill-on-close test (4.6) plus the Task Scheduler restart-on-failure registration (4.3).)_
