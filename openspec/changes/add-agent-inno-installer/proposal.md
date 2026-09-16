## Why

Installing the agent today is a rough, unprofessional flow: copy the installer zip somewhere (it landed in `C:\Users\admin\Documents\...` on the kiosk), run a PowerShell script that elevates and calls `wdb-agent.exe install`, with the install rooted at wherever you happened to unzip it. It doesn't appear in Windows "Apps & features", can't be uninstalled the normal way, leaves scratch folders around, and can't be mass-deployed unattended. For real kiosk/fleet rollouts we want a proper single-exe installer that installs to a stable system location, shows up in the installed-apps list, uninstalls cleanly, and supports silent deployment across many machines.

## What Changes

- Ship a single-exe Windows installer (`wdb-agent-setup-<ver>.exe`) that:
  - installs the agent to a **stable system location** that is **writable by the running agent** (so self-update keeps working without elevation) — not a scratch/Documents folder and not read-only Program Files;
  - lets the operator choose the install directory and enter the **machine name** (interactive), or take both as parameters for **silent/unattended** deployment across many kiosks;
  - registers the agent in the OS **installed-apps list** with a working **uninstaller**;
  - **removes any previous agent installation first** (stop the running process, delete the old autostart task + firewall rule + prior install), so re-install and migration from the old ad-hoc layout are clean and idempotent;
  - keeps the autostart (interactive logon task), firewall rule, versioned layout, and machine name exactly as today (reuse the agent's own install logic).
- **Full uninstall**: remove the autostart + firewall + the running process **and** the runtime versioned layout/data the agent created, plus the installed-apps entry.
- Publish the installer as a release asset; keep the raw app-image zip as the self-update asset (unchanged from the self-update layout fix).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `agent-lifecycle`: add requirements for a stable writable system install location, an installed-apps/uninstall registration, removal of any previous installation before installing, and unattended (silent) install; strengthen the uninstall requirement to also stop the running agent and remove the install directory + installed-apps entry.

## Impact

- **New**: an installer definition (`scripts/wdb-agent.iss` or similar) + a small refactor of the agent's install path so the installer can place the app-image directly in the versioned layout and run a lightweight "finalize" (autostart + firewall + pointer) instead of copying the app-image twice.
- **Build/CI**: `release.yml` compiles the installer (Inno Setup via the Windows runner) and publishes `wdb-agent-setup-<ver>.exe`; the raw app-image zip (self-update asset) and the current installer zip stay as needed.
- **Agent**: `install`/`uninstall` commands extended (finalize step, stop-running-process on uninstall); the machine name still survives self-updates (baked into the launcher).
- **Docs**: README/first-install instructions point at the new setup exe; `install-agent.ps1` becomes legacy/optional.
- **Constraint carried forward**: the agent must run in the interactive kiosk session (GUI apps + screenshots) → it stays a per-user logon task, so the install location and run-user are per the kiosk auto-login user.
- **Non-goals**: an MSI, a cross-OS installer (the agent is Windows-only), and auto-updating the installer itself.
