# wdb

**A debug bridge for [Compose Desktop](https://www.jetbrains.com/lp/compose-multiplatform/) (JVM) apps — ADB, but for a LAN of desktop machines.**

wdb deploys, runs, **hot-reloads**, inspects, and debugs Compose Desktop apps on remote
machines (a "demo wall" of kiosks, POS terminals, signage, or test boxes) — from your
terminal, your IDE, or an AI agent. Push a jar to every machine at once, flip an app into
Compose hot-reload and push code changes live, mirror a machine's screen and tap its UI,
attach a debugger, or drive it all from Claude over MCP.

> Status: early, pre-1.0. Interfaces may change. The wire protocol is versioned and
> negotiated at connect time, so a newer client and an older agent fail loudly instead of
> silently misbehaving.

## Features

- **Deploy** an app jar to one or all machines (with stale JAR-signature stripping so signed fat jars run).
- **Run / stop / restart / rollback** — lifecycle over the LAN, per-machine outcomes.
- **Compose hot-reload** — run in hot mode and push compiled class deltas live; redeploy fallback.
- **Inspect** — screenshot a machine's screen (raw PNG) and dump its Compose semantic tree.
- **Interact** — click / long-click / set-text / scroll semantic nodes; raise a window to the foreground.
- **Debug** — open a JDWP tunnel for a one-click Remote JVM Debug attach.
- **Logs** — stream a machine's app logs.
- **Self-updating agent** — roll a new agent build to machines over the wire.
- **Four front-ends** — a CLI, an IntelliJ/Android Studio plugin, and an MCP server for AI agents, all over one client.

## How it works

```
   your machine (any OS)                         demo-wall machine (Windows)
 ┌───────────────────────────┐                 ┌──────────────────────────────┐
 │  wdb CLI  /  IDE plugin    │   framed-JSON   │  wdb-agent (bundled JBR)      │
 │  /  MCP server            ─┼────over TCP────▶│   • launch / supervise app    │
 │        (wdb-client)        │   + UDP disco   │   • Compose hot-reload        │
 └───────────────────────────┘                 │   • screenshot / semantics    │
                                                │   • JDWP tunnel, self-update  │
                                                └──────────────────────────────┘
```

An **agent** runs on each target machine and speaks a small framed-JSON protocol; the
**client** (embedded by the CLI, plugin, and MCP server) discovers agents over UDP and drives
them over TCP.

## Components

| Module | What it is |
|---|---|
| `wdb-protocol` | Framed-JSON wire protocol + version negotiation. |
| `wdb-client` | Discovery, connections, tunneling, deploy/reload ops. Compose- and network-dependency-free so IDEs can embed it. |
| `wdb-agent` | The on-machine agent (**Windows-only**): launches/supervises the app, hot-reload, screenshot/semantics, JDWP tunnel, self-update. Bundles a JetBrains Runtime. |
| `wdb-cli` | The `wdb` command-line client (cross-OS, needs a JVM). |
| `wdb-mcp` | [MCP](https://modelcontextprotocol.io) server over stdio — drives the wall from an AI agent. See [`wdb-mcp/README.md`](wdb-mcp/README.md). |
| `wdb-plugin` | IntelliJ IDEA / Android Studio plugin: tool window, screen mirror, one-click debug. |
| `wdb-dummy-app` | A Compose Desktop test fixture. |

## Requirements

- **JDK 21** (client, CLI, MCP need a JVM on `PATH`/`JAVA_HOME`; the agent bundles its own runtime).
- **Target machines: Windows** (the agent uses Win32/JNA and jpackage). The CLI, plugin, and MCP server run on macOS, Linux, and Windows.

## Install

Grab prebuilt archives from [Releases](https://github.com/OlimzhanovUmid/wdb/releases/latest).

**Agent** (on each Windows target machine):
```powershell
# unzip wdb-agent-installer-<ver>.zip, then:
.\wdb-agent\install-agent.ps1 -Name <machine-name>
```

**CLI** (your machine): unzip `wdb-cli-<ver>.zip` and add `wdb-cli-<ver>/bin` to your `PATH`.

**IntelliJ / Android Studio plugin** — add the custom update repository once, then install/update from the IDE:
```
Settings → Plugins → ⚙ → Manage Plugin Repositories… →
https://github.com/OlimzhanovUmid/wdb/releases/latest/download/updatePlugins.xml
```

**MCP server** (for Claude Code / Desktop): unzip `wdb-mcp-<ver>.zip` and register the launcher:
```bash
claude mcp add wdb -s user -- /path/to/wdb-mcp/bin/wdb-mcp
```

## Usage (CLI)

```bash
wdb devices                        # list reachable machines
wdb push app.jar --all             # deploy a jar to every machine (auto-restart)
wdb run wall-01 --hot              # launch in Compose hot-reload mode
wdb reload build/classes/kotlin/main wall-01 --watch   # push code changes live
wdb screenshot wall-01 --out shot.png
wdb semantic-tree wall-01          # dump the Compose semantic tree (JSON)
wdb logs wall-01                   # stream app logs
wdb status wall-01                 # app state, hot mode, jdwp, uptime, deployed sha
wdb bring-to-front wall-01         # raise the app window
wdb debug wall-01                  # open a JDWP tunnel for Remote JVM Debug
wdb agent-update wdb-agent-installer-<ver>.zip --all   # roll a new agent build
```

Run `wdb --help` or `wdb <command> --help` for all options.

## Build from source

```bash
./gradlew build                    # compile + test everything
./gradlew :wdb-cli:distZip         # CLI distribution
./gradlew :wdb-mcp:distZip         # MCP server distribution
./gradlew :wdb-plugin:buildPlugin  # IDE plugin zip
./gradlew :wdb-plugin:runIde       # launch a sandbox IDE with the plugin
# Agent installer needs a JBR 21: -PjbrHome=<path>
./gradlew :wdb-agent:packageAgentInstaller -PjbrHome=<JBR-21>
```

Releases are cut by pushing a `v*` tag; CI builds all artifacts and publishes them (see
[`.github/workflows/release.yml`](.github/workflows/release.yml)).

## Support

wdb is free and open source. If it helps you, you can support development:

- [GitHub Sponsors](https://github.com/sponsors/OlimzhanovUmid)
- [Buy Me a Coffee](https://buymeacoffee.com/umidolimzhanov)

## License

[Apache License 2.0](LICENSE).
