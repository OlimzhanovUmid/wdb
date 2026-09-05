## Context

See proposal.md — Why. Greenfield repo. Target is a demo wall: several Windows machines on one trusted LAN, each auto-logging into a kiosk session and showing one Compose Desktop (JVM) app fullscreen. The dev machine drives them. Debugging is JDWP over TCP (built into the JVM), so no debugger is built — a port is routed. Constraints that shape the design:

- The GUI must appear on the physical display → the process launching it must live in the interactive session, not session 0.
- A JRE is present on each box (shipped with the agent) → pushes carry only the app JAR.
- Windows holds a lock on any JAR a running JVM has open → a deployed JAR cannot be replaced in place while the app runs.
- Windows does not kill child processes when their parent dies → an agent crash can orphan the app.
- LAN-trusted, no auth in v1.
- A second client (IntelliJ plugin) and possibly a hub are future consumers → the wire protocol is a stability contract from day one.

## Goals / Non-Goals

**Goals:**
- One reusable client core (`wdb-client`) consumed by both the CLI and a future IDE plugin.
- Simple, byte-transparent transport: one TCP connection per logical stream (control, logs, tunnel) on a single agent port — no multiplexer to get wrong.
- A versioned wire protocol stable enough that a hub or plugin can be added without changing the agent.
- Trivial per-machine install/autostart; the wall comes back by itself after a reboot.

**Non-Goals (design-level):**
- No hub daemon, no IDE plugin, no Android device proxying, no auth/pairing, no hot-reload, no self-contained (jpackage) app packaging in v1 (see proposal Non-goals).
- No multi-subnet routing — single LAN broadcast domain assumed.
- No multi-developer coordination — single dev driving the wall.
- No agent self-update command in v1 (install layout is prepared for it, see D11).
- No remote per-machine app configuration beyond identity (see D18).
- No detection of a hung-but-alive app (process alive, UI frozen).

## Decisions

### D1 — Agent runs as an autostart app in the kiosk session (not a service)
Windows session-0 isolation prevents a service from rendering a GUI. The demo wall auto-logs into a kiosk user, so the agent runs inside that interactive session and launches the app there. *Alternative:* service + `CreateProcessAsUser`/`WTSQueryUserToken` to inject into the active session — more Win32 surface, more failure modes, no benefit here. Rejected for v1.

### D2 — Agent is Kotlin/JVM and bundles its own JRE
Reuses the team's stack; `ProcessBuilder` and JVM libs make process spawn and networking easy. The bundled JRE doubles as the app's runtime, so "install agent" and "provide JRE" collapse into one step, and each push is just the app JAR. *Alternative:* Kotlin/Native (tiny standalone exe, lower RAM) — but the box needs a JRE anyway, so KN's main advantage evaporates while Win32 cinterop cost rises. Revisit KN only if agent RAM becomes a problem; the stable protocol makes swapping cheap.

### D3 — One TCP connection per stream, single agent port, no multiplexer
The agent listens on one TCP port. A client opens a separate connection for each logical stream and declares its kind in the handshake: `control` (request/response commands), `push` (binary app upload — see below), `logs` (subscription; one-way stream of records), or `tunnel` (raw byte relay to a loopback port on the agent, see D19). Control and logs carry length-prefixed JSON frames (D7); a tunnel connection turns into raw bytes right after the handshake ack. A `push` connection sends a JSON manifest frame, then the blob bytes as raw length-prefixed frames terminated by a zero-length frame, then reads a JSON result frame — this keeps large binary off the JSON control channel (a refinement discovered in implementation: a dedicated `push` kind rather than mixing binary into control). Rationale: with one connection per stream the kernel does per-stream flow control and there is no head-of-line blocking — a stalled log reader cannot freeze a JDWP tunnel — and the JDWP relay is two copy loops. *Alternatives:* a single multiplexed connection with credit-based windows (HTTP/2-style; 150–200 lines of subtle, deadlock-prone logic), or ADB-style one-frame-in-flight per channel (simple but throughput bound to RTT — 3–12 MB/s on Wi-Fi). Both rejected: more code for no benefit on a single LAN port. A versioned handshake opens every connection.

### D4 — Discovery by broadcast query / unicast reply
The client broadcasts a small query datagram to the agent UDP port; every agent replies by unicast with its identity, address, protocol version and app/desired state. The client collects replies for a bounded window (~1 s, repeated once), de-duplicated by machine id. Rationale: Windows Firewall on the dev machine accepts unicast replies to a broadcast the host just sent (`unicastresponsetomulticast`, enabled by default, ~3 s window), so no inbound rule or firewall prompt is needed on developer machines or inside an IDE plugin; no staleness timer; no periodic chatter. *Alternatives:* periodic announcements from agents (passive live view, but every dev machine needs an inbound UDP rule and staleness logic); both (double code for a dashboard that does not exist yet). If a domain policy disables the unicast-response window, `--host <addr>` bypasses discovery (D9). mDNS/DNS-SD can replace the datagram format later behind the same client API.

### D5 — App is always debuggable: JDWP on loopback, tunnel on demand, no custom debugger
Every launch adds `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:<port>`; the agent picks a free port and reports it in `status`. `wdb debug <machine>` only opens a tunnel (D3/D19) from a local port to that JDWP port; the developer points the IDE's stock "Remote JVM Debug" at the local port and attaches **without restarting the app** — the screen state under investigation is preserved. `wdb debug --suspend` relaunches with `suspend=y` for debugging startup. HotSpot's full-speed debugging makes an idle JDWP agent essentially free; binding to 127.0.0.1 keeps it invisible on the LAN — only local processes (the agent's tunnel) can reach it, acceptable on a kiosk box. *Alternative:* enable JDWP only on request by restarting the app — loses state, blinks the screen, two launch code paths. No IDE plugin needed for v1; the future plugin automates the same steps through `wdb-client`.

### D6 — Repository layout: four Gradle modules over a shared client core
`wdb-protocol` (handshake, frame codec, message and datagram definitions), `wdb-client` (discovery + connections + tunnel; the reusable core), `wdb-cli` (command surface depending on `wdb-client`), `wdb-agent` (JVM daemon + Windows integration + install + supervision + bundled-runtime packaging). Keeps CLI and the future plugin on identical client code.

### D7 — Control frames are JSON (kotlinx.serialization)
Control traffic is about a dozen small message types on a LAN; size is irrelevant, debuggability is not. JSON is readable in packet captures and logs, needs no schema tooling, and evolves via optional fields with defaults. Tunnel connections stay raw bytes (D3), so the encoding touches only control/log frames and can be swapped later behind the handshake version. *Alternatives:* protobuf (compact, explicit `.proto` for non-Kotlin clients — but codegen or hand-numbered fields, unreadable dumps, overkill here); CBOR (binary JSON — unreadable and schemaless, gains only size).

### D8 — Push: whole-JAR in v1, manifest+blobs message, versioned deploy directories, restart-on-push
v1 transfers the single uber JAR (≈50–80 MB; sub-second on gigabit, several seconds on Wi-Fi). The push message is nonetheless `manifest { entries: [{name, sha256, size}], mainClass, jvmArgs, programArgs } + blobs`, with exactly one entry today, so a later per-artifact blob cache (agent requests only missing hashes → pushes shrink to the 1–5 MB that actually changed) lands without a protocol break.

Because Windows locks a JAR the running JVM has open, the agent never replaces a file in place: each deployment lands in `deploy/<sha256>/`, and a `current` pointer is switched atomically after verification. The previous deployment is retained (keep two, GC older) — which gives `wdb rollback` for free. If the app is running when a push completes, the agent restarts it onto the new deployment (graceful stop, D17); `--no-restart` stages only. Rationale: `wdb push --all` is the one command that updates the whole wall. *Alternatives:* per-jar blob cache now (changes launch to `-cp`; deferred until Wi-Fi push latency hurts); content-defined chunking over the uber JAR (rolling hash, fragile to non-deterministic zips, overkill); stage-only push with a separate restart (two commands per iteration, easy to forget).

### D9 — CLI discovery: on-disk last-seen cache, direct connect, broadcast fallback
The client persists a small last-seen table (machine id → name, address) on the dev machine. Commands targeting a named machine connect directly to the cached address; on failure they fall back to a fresh discovery query, then retry. `wdb devices` always queries. `--host <addr>` bypasses discovery entirely (also the escape hatch when broadcast is blocked). *Alternatives:* fresh discovery per command (stateless but +1–2 s on every command); local resolver daemon à la `adb server` (instant, but lifecycle/zombie/version-skew pain — and it is the hub we decided not to build).

### D10 — Networking on `java.net` blocking sockets + coroutines; `wdb-client` has zero third-party deps
Blocking `Socket`/`ServerSocket`/`DatagramSocket` wrapped on `Dispatchers.IO`, framing via `DataInputStream`/`DataOutputStream`. Scale is ≤ ~20 connections per client; blocking IO is the simplest to write and debug, and the tunnel relay is two copy loops. Decisive argument: the future IntelliJ plugin embeds `wdb-client`, and the IntelliJ platform ships its own kotlinx-coroutines; bundling Ktor/Netty inside a plugin invites classloader conflicts. Cancellation is by closing the socket. JNA is used by `wdb-agent` only (D16, D20). *Alternatives:* ktor-network (coroutine-native, structured cancellation, but pulls ktor + kotlinx-io and is less trodden than ktor-http); Netty (heavy, callback-style, overkill).

### D11 — Agent autostart via Task Scheduler logon-trigger task; versioned install layout
`wdb-agent install --name <machine>` (elevated, once) registers a scheduled task triggered at kiosk-user logon, with restart-on-failure and a short start delay (network/desktop ready), and adds the inbound firewall rule. The agent is a daemon and must survive its own crash; Task Scheduler gives that for free and does not depend on the Explorer shell (Assigned Access kiosks may replace it). Install lays the agent out as `agent\<version>\` plus a `current` junction, and the task points at the stable `current` path — so a future `wdb agent-update --all` (v1.1) is just push + swap junction + `schtasks /run`, without fighting the running-exe lock. *Alternatives:* HKCU `Run` key (one registry write, but no restart and often disabled by kiosk policies); Startup folder shortcut (same, plus shell dependency). Self-update itself is deferred: it needs a control message, self-restart logic and a broken-agent rollback story.

### D12 — In-memory log ring buffer (current + previous run)
The agent keeps a bounded tail (N lines / KB) of the current run and the previous run. On subscribe it delivers that history (run-boundary marker included) before switching to live streaming, so "why did the screen die overnight" is answerable. Lost on agent restart — accepted for v1. *Alternative:* per-run log files with rotation (survives restarts; more spec and disk management) — later if needed.

### D13 — Bundled runtime: full JetBrains Runtime, JDK 21, DCEVM-capable build
JBR is what JetBrains tests Compose Desktop against (HiDPI, fonts, AWT fixes). A full runtime (≈150–200 MB) is a one-time per-box cost; pushes stay thin. JDK 21 LTS (Compose needs 17+; 21 also offers virtual threads for the blocking-IO design). Pick a JBR build with enhanced class redefinition (DCEVM) so Compose Hot Reload works later without swapping runtimes on every box. Any future `jlink` trim MUST keep `java.desktop` and `jdk.jdwp.agent`, or GUI/debug silently break. *Alternative:* jlink-minimal now (40–60 MB, but a missing module crashes the app on the box; revisit once the module set is stable).

### D14 — CLI framework: Clikt
Kotlin-native, subcommands, good help output. *Alternatives:* picocli (Java-style annotations), kotlinx-cli (thin, effectively unmaintained).

### D15 — Persisted desired state; the wall comes back after a reboot
The agent stores `desired = running | stopped` on disk. `run` sets running, `stop` sets stopped. On agent start (reboot, crash-restart), if desired is running and a deployment exists, the agent launches the app. Rationale: reboot → auto-login → agent → app, with no operator action; and `stop` survives a reboot so a box can be deliberately kept quiet. *Alternatives:* always launch on agent start (stop does not survive reboot); never auto-launch (defeats the demo wall).

### D16 — App lifetime bound to the agent via a Windows Job Object
The agent places the app process in a Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. If the agent dies, Windows kills the app; Task Scheduler restarts the agent (D11); D15 relaunches the app. Invariant: the app only ever runs under an agent, so two instances can never appear. Cost: an agent crash blinks the screen for a few seconds, and `wdb-agent` gains a JNA dependency (kernel32) — `wdb-client` stays dependency-free (D10). *Alternative:* persist the PID and re-adopt on restart — no blink, but the stdout pipe of an adopted process is lost (log gap), PID-reuse races, more code.

### D17 — Stop is graceful with a bounded timeout
Stop (and the restart in D8) sends `WM_CLOSE` via `taskkill /PID <pid>` (no `/F`), which reaches Compose's `onCloseRequest` and JVM shutdown hooks, waits ~5 s, then `destroyForcibly()`. `taskkill` without `/F` only works for windowed processes in the same session — exactly our case. *Alternative:* force only (`TerminateProcess`) — simplest, but every push becomes a sudden death.

### D18 — App identity via environment; common args in the manifest
The agent always injects `WDB_MACHINE_NAME` and `WDB_MACHINE_ID` into the app's environment; the app maps the name to its role ("screen 3") in its own configuration. Common `jvmArgs` / `programArgs` travel in the push manifest (D8), identical for every machine. The machine name is the only per-machine input and is set at `install --name wall-03` (default: hostname). *Alternatives:* remote per-machine config (`wdb config wall-03 --env SCREEN=3`) — flexible but a new message, new spec and agent-side state; deferred. App deriving identity from hostname alone — hostnames are unstable and leak wdb naming into the app.

### D19 — Tunnel targets are restricted to the agent's loopback
A tunnel connection may only target `127.0.0.1:<port>` on the agent's machine. That covers JDWP (D5) and any future local service; the agent is never a proxy into the LAN — important with no authentication (see Risks). *Alternatives:* an allowlist (config + spec for a need that does not exist yet); any host:port (an open proxy).

### D20 — Keep the display awake while the app runs
While the app is running, the agent holds `SetThreadExecutionState(ES_DISPLAY_REQUIRED | ES_CONTINUOUS)` (JNA, already a dependency per D16) and releases it on stop. Reversible, scoped to "app running", touches no global settings. *Alternatives:* `powercfg` at install (global, must be undone at uninstall, still overridden by GPO); both; leaving it to the box administrator (one forgotten box = black screen at the demo). A GPO-enforced screensaver/lock is out of our hands — documented.

### D21 — Log backpressure: a slow subscriber never blocks the app
The agent reads the app's stdout/stderr unconditionally into the ring buffer (D12). Each log subscriber has a bounded outbound queue; when it overflows, the oldest entries are dropped and a `[dropped N lines]` marker is emitted. Combined with one connection per stream (D3), a paused terminal cannot stall the app or a tunnel.

### D22 — Test strategy: CI on `windows-latest` for non-GUI Windows integration, manual wall run for GUI
Task Scheduler registration/removal, firewall rule, Job Object kill-on-close, versioned swap under a file lock, graceful stop timeout, discovery query/reply and the loopback transport tests run in GitHub Actions on `windows-latest` (the runner is an administrator). What a runner cannot verify — GUI on the physical display in the kiosk session, IDE attach — stays a manual checklist (task 9.1). *Alternatives:* manual only (regressions found on the wall); scripted local VMs (control, but maintenance).

### D23 — Implementation defaults (settled; not worth separate decisions)
- **Machine identity:** UUID generated on first run and persisted in the agent data dir; display name defaults to hostname, overridable at `install --name`.
- **Ports:** one fixed default TCP port for the agent and one UDP port for discovery, both overridable; IPv4 only in v1.
- **Frames:** control/logs frames are `u32 length + JSON`, max 1 MB; push frames are the same framing but the blob frames carry raw bytes (JSON manifest frame, then blob frames, then a zero-length terminator, then a JSON result frame); tunnel connections are unframed after the handshake. The handshake is the first frame on every connection: `{kind, protocolVersion, ...}` → `{ok | error}`.
- **Concurrency:** multiple clients per agent are allowed (CLI + `logs` in another terminal + a plugin); mutating commands (push/run/stop/restart/rollback) are serialized on the agent.
- **`status` payload:** app state, desired state, uptime, restart count, last exit code, deployed sha + main class, JDWP port, agent version, runtime version.
- **Child JVM launch:** `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` (a console-less child otherwise emits the ANSI codepage); working dir = the deployment dir.
- **Agent packaging:** `jpackage --type app-image` with `--runtime-image` pointing at the full JBR; a single-instance guard (lock file); data in `%LOCALAPPDATA%\wdb\`, binaries under the versioned layout of D11.
- **CLI:** push shows transfer progress; connect/handshake timeouts are short; `push --all` with zero discovered machines is an error, not a no-op; log lines carry agent-side timestamps.
- **Build assumption:** the uber JAR is built on Windows (skiko natives are per-OS); a cross-OS build must add the Windows skiko runtime explicitly.

## Risks / Trade-offs

- **Arbitrary JAR push + launch is remote code execution by design** → Acceptable only on the controlled, trusted LAN. Bind the agent to LAN interfaces, restrict tunnels to loopback (D19), document the exposure, and treat auth/pairing as the first thing to add before any untrusted use.
- **Kiosk auto-login is a security weakening on the target boxes** → Inherent to a demo wall; scope agents to demo machines only, not shared workstations.
- **JDWP always listening on the box** → Bound to 127.0.0.1; only local processes can attach, and the box is a single-purpose kiosk. Revisit with auth.
- **Restart storms** (app crashes instantly on launch) → Bounded exponential backoff; after a threshold, report `crashed` instead of looping (see process-supervision spec).
- **Protocol churn breaks a deployed agent** → Version negotiated in the handshake; agents reject unknown major versions with a clear error rather than misbehaving.
- **Broadcast blocked, or the unicast-response window disabled by group policy** → `--host <addr>` bypasses discovery; the last-seen cache (D9) keeps working for known machines.
- **Bundled JRE bloats the agent installer** → One-time cost per machine; pushes stay thin, which is the fast-iteration goal.
- **Task Scheduler disabled by policy on a box** → Install detects the failure and falls back to the HKCU `Run` key with a warning (no restart-on-failure in that mode).
- **Agent crash blinks the screen** (Job Object kills the app) → Seconds of black screen; accepted in exchange for the no-duplicate-instance invariant.
- **Log ring buffer lost on agent restart** → Accepted v1 limitation; per-run log files are the documented upgrade path (D12).
- **Graceful stop hangs on an unresponsive app** → Bounded 5 s timeout, then force.
- **Blocking sockets vs coroutine cancellation** → All blocking calls run on `Dispatchers.IO`; cancellation closes the socket to unblock readers; covered by loopback tests in tasks.
- **Disk growth from retained deployments** → Keep two, GC the rest after a successful switch.

## Open Questions

- Log ring-buffer retention limit (lines vs bytes, default) and the per-subscriber queue size (D21) — tune during implementation.
- Exact Task Scheduler registration for the kiosk user from an elevated install (`/RU` without a stored password → "run only when user is logged on") — resolved while implementing install.
- Trigger point for enabling the per-artifact blob cache (D8) — when Wi-Fi push latency actually hurts; no protocol change required.
- Default port numbers and the discovery reply window — pick unused defaults during implementation.
- **Fixed per-machine JDWP port** (follow-up surfaced during the wall-02 debug run): JDWP currently uses a fresh ephemeral port per launch, so a `debug` tunnel and the IDE's run-config go stale after any app restart (e.g. the operator closing the window → auto-restart). A stable per-machine JDWP port would keep the IDE config valid across restarts. Non-blocking; revisit alongside the IntelliJ plugin.
