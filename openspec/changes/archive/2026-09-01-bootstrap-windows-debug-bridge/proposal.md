## Why

Running and iterating Compose Desktop (JVM) apps across a **demo wall** — several Windows machines on a LAN, each showing the app fullscreen — has no good tool today. Manually copying JARs, launching apps in the right session, keeping crashed screens alive, and attaching a debugger box-by-box does not scale past one machine. ADB is the closest analogy but targets Android over USB and solves the wrong problem. Because the target is a JVM, debugging is already solved by JDWP over TCP and "install" is just file-copy + process spawn — so a small orchestration tool captures nearly all the value.

## What Changes

- Introduce **windows-debug-bridge (`wdb`)**: an agent daemon per demo-wall machine plus a client/CLI on the dev machine.
- **Agent** runs as an autostart app inside the logged-in kiosk session (NOT a Windows service — session 0 isolation would prevent it from showing the GUI). It bundles a JRE, receives an app JAR into a versioned deployment directory, launches the Compose app in the interactive session, supervises it (auto-restart on crash; app lifetime bound to the agent so no orphaned instances; persisted desired state so the wall comes back by itself after a reboot), keeps the display awake while the app runs, streams logs (with a short in-memory history so a crash with nobody watching is still diagnosable), and launches the app with JDWP always listening on loopback so an IDE can attach without restarting it. Self-installs (`wdb-agent install --name <machine>`) by registering a Task Scheduler logon task + firewall rule.
- **Client** is a reusable Kotlin library: LAN discovery (broadcast query / unicast reply), a stable wire protocol over one TCP connection per stream, and a TCP-forward tunnel to the agent's loopback ports. Both the CLI and a future IntelliJ plugin consume it.
- **CLI** (`wdb`) surfaces: `devices`, `push [--all] [--no-restart]` (fan-out deploy that restarts running apps onto the new build), `run`, `stop`, `restart`, `rollback`, `status`, `logs`, `debug [--suspend]` (open a tunnel to the app's JDWP port for a manual "Remote JVM Debug" attach in IntelliJ/Android Studio). Every machine-targeting command accepts `--host <addr>` to bypass discovery.
- **Deploy model**: shared JRE on the box (shipped with the agent); each push transfers only the app JAR plus its launch parameters (main class, JVM/program args) into `deploy/<sha>/`; the previous deployment is kept for rollback.
- **No hub daemon.** Clients talk straight to agents; discovery is by broadcast query. The reusable client lib + stable protocol keep a hub optional forever.

Non-goals (explicitly deferred): hub daemon, IntelliJ/Android Studio plugin, real Android device proxying, auth/pairing, Compose hot-reload, self-contained (jpackage) app packaging, an agent self-update command (the install layout is prepared for it), remote per-machine app configuration beyond machine identity, tunnels to anything but the agent's loopback, detection of a hung-but-alive app.

## Capabilities

### New Capabilities
- `device-discovery`: clients query the LAN and agents answer with identity, address and state; machines can also be addressed explicitly.
- `app-deployment`: transfer an app JAR and its launch parameters from the dev machine to one or many agents (fan-out) into a versioned deployment, restart running apps onto it, and roll back to the previous deployment.
- `process-supervision`: launch the app in the interactive kiosk session with its machine identity, keep it alive (auto-restart on crash, persisted desired state across reboots, no orphaned instances, display kept awake), and stop it gracefully on command.
- `log-streaming`: stream a launched app's stdout/stderr from the agent back to a connected client in near-real-time, with recent history and without ever blocking the app.
- `port-tunnel`: a TCP-forward primitive from a client to the agent's loopback ports, used first to reach the app's always-on JDWP port for an IDE attach without restarting the app.
- `agent-lifecycle`: install (with a machine name), autostart in the kiosk session, recover from its own crash, and self-register (firewall rule) with minimal manual steps.

### Modified Capabilities
<!-- None. Greenfield project; no existing specs. -->

## Impact

- **New codebase** (greenfield repo). Proposed modules: `wdb-protocol` (handshake, frames, messages), `wdb-client` (shared discovery + connections + tunnel lib), `wdb-cli` (command surface consuming the client lib), `wdb-agent` (JVM daemon, bundled runtime, Windows integration).
- **Platform**: Windows target machines with auto-login kiosk session; dev machine cross-platform for the CLI.
- **Runtime/deps**: bundled JetBrains Runtime (JDK 21, DCEVM-capable build) with the agent; `java.net` sockets + kotlinx-coroutines + kotlinx-serialization (JSON) — no third-party networking library, so `wdb-client` can later be embedded in an IDE plugin without classloader conflicts; JNA in `wdb-agent` only (Job Object, display-awake); Clikt for the CLI.
- **CI**: GitHub Actions on `windows-latest` for the Windows integration pieces (Task Scheduler, firewall, Job Object, versioned swap, graceful stop); GUI verification stays manual on the wall.
- **Security**: LAN-trusted, no auth in v1. Pushing and launching arbitrary JARs is remote code execution by design; acceptable only on a controlled LAN. Tunnels are restricted to the agent's loopback and JDWP binds to loopback, so the agent is never a proxy into the LAN. Auth/pairing is a named non-goal to revisit before any untrusted-network use.
- **Wire protocol** becomes a stability contract the moment a second client (IntelliJ plugin) or a hub is added; design must treat it as versioned.
