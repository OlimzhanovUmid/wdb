## Context

See proposal.md. Grounding in the current code:

- `scripts/install-agent.ps1` is a thin wrapper: self-elevate → prompt name → `wdb-agent.exe install --name <name>` → `schtasks /run`.
- The real logic is `InstallManager` (`wdb-agent/.../Install.kt`):
  - `install()` → `layOutVersioned()` copies the running app-image into `<base>/agent/versions/<ver>/`, sets `current-version`, writes `launch.cmd`; then creates the `wdb-agent` Task Scheduler **logon task** (`InteractiveToken`, 15s delay, RestartOnFailure) via UTF-16LE XML, and the TCP 7420 / UDP-discovery firewall rules.
  - `base` = **parent of the app-image dir** = wherever the exe was unzipped (the kiosk's `Documents\...`).
  - `uninstall()` only deletes the task + firewall rule (not files, no installed-apps entry).
  - Runs as / registers for `defaultUser()` = the user running the exe.
- The agent MUST run in the interactive kiosk session (GUI apps + screenshots) → a per-user **logon task**, not a service.
- Self-update rewrites `<base>/agent/versions/` + `current-version` (the launcher stub reads it) → **base must stay writable by the running agent** (the just-fixed self-update pulls the raw app-image zip).

## Goals / Non-Goals

**Goals:** a single-exe installer to a stable writable system location, installed-apps entry + clean uninstall, machine-name + dir prompt, silent mode, and purge-any-previous-install.

**Non-Goals:** MSI; cross-OS installer (agent is Windows-only); auto-updating the installer; converting the agent to a Windows service (it must stay in the interactive session).

## Decisions

**D1 — Inno Setup for the single-exe installer.**
Chosen over extending `wdb-agent.exe install` because silent/unattended deployment is required and Inno gives industrial silent mode, the installed-apps/uninstaller registration, an install-dir + custom (machine-name) wizard page, and per-file tracking — all out of the box. Cost is one CI tool (`choco install innosetup`; `iscc wdb-agent.iss` on the Windows runner). *Alt:* hand-roll registry uninstall key + ACL + silent-flag parsing in the agent — rejected as reinventing Inno for less.

**D2 — Install base = `C:\ProgramData\wdb-agent`, ACL the run-user for modify.**
All-users, fixed path (survives a kiosk-user change), and — critically — writable by the running agent for self-update once the run-user is granted modify on the tree. **Not Program Files** (read-only without elevation → self-update breaks). **Not `%LOCALAPPDATA%`** (elevating the installer flips the profile to the admin's LocalAppData, not the kiosk user's). Inno `[Dirs] … Permissions:` (or an icacls `[Run]` step) grants the run-user modify.

**D3 — Refactor agent install into `finalize` (no double-copy).**
Inno places the app-image directly at `<base>\agent\versions\<ver>\` via `[Files]`. The agent then runs a lightweight `wdb-agent.exe finalize --name <name> [--user <kiosk-user>]` that does only: write `current-version`, write `launch.cmd`, create the logon task, add the firewall rules — reusing the existing task/firewall/launcher code, skipping the app-image copy (avoids 2×159 MB on disk). The old `install` (copy-based, for the legacy zip+ps1 path) can remain or be expressed in terms of `finalize`.

**D4 — Run-user: default to the installer's user; `--user`/wizard field to override.**
The logon task + the ACL target must be the **kiosk auto-login user**. Default = the (elevated) installer user; when the kiosk user differs from the admin doing the install, an interactive field / silent `/RUNUSER=` sets it. (On the current kiosk the auto-login user is admin, so the default works.)

**D5 — Purge any previous install before installing (Inno `[Code]` pre-install).**
Idempotent migration: kill any running `wdb-agent.exe`; `schtasks /delete /tn wdb-agent /f`; `netsh advfirewall firewall delete rule name=wdb-agent`. Best-effort remove the old base by reading the existing task's action (its `launch.cmd` path → old base) before deleting the task; if unresolvable, leave old scratch files (harmless) and log it. The fixed task/firewall names make the guaranteed cleanup deterministic.

**D6 — Uninstall = Inno uninstaller + `[UninstallRun]` agent teardown + remove base.**
Inno auto-creates the installed-apps entry + `unins000.exe`. On uninstall: `[UninstallRun]` runs `wdb-agent.exe uninstall` (which now also **stops the running agent process**, then removes task + firewall) **before** Inno deletes files; then remove `C:\ProgramData\wdb-agent` (the runtime `agent\versions\` + data Inno didn't track). Order matters — stop the process first or the exe is locked.

**D7 — Silent/unattended.**
`wdb-agent-setup-<ver>.exe /VERYSILENT /SUPPRESSMSGBOXES /MACHINE=wall-04 [/DIR="C:\ProgramData\wdb-agent"] [/RUNUSER=kioskuser]`. Inno `{param:Machine}` feeds the finalize step. This replaces the zip+ps1 for mass deploy; `install-agent.ps1` becomes legacy.

**D8 — Self-update asset unchanged.**
The installer bundles the app-image for first install; self-update still downloads the **raw app-image zip** (today's fix). Both consistent; no change to the self-update wire/asset.

## Risks / Trade-offs

- **Elevation flips `%LOCALAPPDATA%`** → use ProgramData (fixed path), not LocalAppData (D2).
- **Run-user ≠ installer-user** (kiosk user not admin) → `--user`/`/RUNUSER=` (D4); document it.
- **Old base location unknown for purge** → task/firewall/process cleanup is guaranteed by fixed names; old *files* are best-effort (read the old task action, else leave + log) (D5).
- **Uninstall with a running agent** → locked exe; mitigate by stopping the process first (D6).
- **Inno as a build dependency** → pin a version, install via choco in CI; the `.iss` is small and versioned in-repo.
- **ProgramData ACL too broad** → grant modify to the specific run-user, not Everyone.

## Migration Plan

1. Land the finalize refactor + `.iss` + CI packaging; publish `wdb-agent-setup-<ver>.exe`.
2. On the existing kiosk (old Documents install): run the new setup → it purges the old task/firewall/process, installs to ProgramData, appears in Apps & features. Self-update continues to work from ProgramData.
3. `install-agent.ps1` kept as legacy for one release, then removed.
4. Rollback: the old zip+ps1 path still works if the installer is reverted.

## Open Questions

- Whether to keep `install-agent.ps1` long-term as a fallback, or delete it once the setup exe is proven — decide after first live install.
- Exact ACL mechanism (Inno `[Dirs] Permissions` vs an `icacls` `[Run]` step) — an implementation detail; both work.
