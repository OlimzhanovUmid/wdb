# Contributing to wdb

Thanks for your interest! This guide covers how to build, test, and propose changes.

## Prerequisites

- **JDK 21** (the build uses a Gradle toolchain; have a JDK 21 available).
- To build or test the **agent**, or to exercise end-to-end deploy/run, you need **Windows**
  (the agent uses Win32/JNA and jpackage). The client, CLI, MCP server, and plugin build and
  run on macOS, Linux, and Windows.

## Build & test

```bash
./gradlew build                    # compile + test everything
./gradlew :wdb-client:test         # a single module's tests
./gradlew :wdb-plugin:runIde       # launch a sandbox IDE with the plugin
./gradlew :wdb-cli:distZip         # build the CLI distribution
```

Please make sure `./gradlew build` passes before opening a pull request.

## How changes are planned: OpenSpec

Non-trivial changes are planned with [OpenSpec](https://github.com/Fission-AI/OpenSpec) before
implementation. Capability specs live in [`openspec/specs/`](openspec/specs); proposed changes
(proposal → design → spec deltas → tasks) live in `openspec/changes/` and are archived once
shipped. For a bug fix or a small, self-contained change, a plain PR is fine — just describe
the what and why. For a feature or any change to externally observable behavior, propose it as
an OpenSpec change first so the spec and the implementation stay in sync.

## Code style

- **Kotlin**, official code style (`kotlin.code.style=official`).
- Match the surrounding code — comment density, naming, and idioms.
- Package namespace is `uz.disastrouspumpkin.wdb.*`.

## Commit messages

Use `<module>: short description`, present tense, English, no ticket numbers:

```
wdb-cli: add `bring-to-front` command
wdb-agent: self-update relaunch via launch.cmd, not schtasks
wdb-client: handle multi-owner semantic tree (dialogs)
```

Keep each commit atomic — one logical change — and buildable.

## Pull requests

1. Fork and branch off `master`.
2. Make your change; keep it focused.
3. Ensure `./gradlew build` is green.
4. Open a PR describing the what and why (link the OpenSpec change if there is one).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), per section 5 of that license.
