## 1. Agent install refactor

- [ ] 1.1 Split `InstallManager` so the app-image copy is separable from registration: add a `finalize(machineName, jdwpPort?, user?)` path that assumes the app-image is already at `<base>/agent/versions/<ver>/` and only writes `current-version` + `launch.cmd`, creates the logon task, and adds the firewall rules (reuse the existing task XML / launcher / netsh code; no copy). Keep `install` working (copy + finalize) for the legacy path. Add a CLI subcommand `finalize` (with `--name`, optional `--jdwp-port`, `--user`). Verify `:wdb-agent:build` + a unit test that `finalize` writes the layout/launcher without copying.
- [ ] 1.2 Extend `uninstall` to also **stop the running agent** (kill the running `wdb-agent.exe` / the logon task's process) before removing task + firewall, so an uninstall can proceed with a live agent. Verify the command stops + removes on a box (or a scripted check).
- [ ] 1.3 Allow the logon task + ACL target to be a specified `--user` (default = current user). Verify the task XML principal reflects the passed user.

## 2. Inno Setup installer

- [ ] 2.1 Add `scripts/wdb-agent.iss`: metadata (AppId, name, version from the build), `PrivilegesRequired=admin`, `DefaultDirName={commonappdata}\wdb-agent`, a wizard page for the machine name (+ optional run-user), `[Files]` placing the app-image into `{app}\agent\versions\<ver>\`. Verify `iscc` compiles it locally/CI.
- [ ] 2.2 `[Dirs]` (or an icacls `[Run]`) grants the run-user **modify** on `{app}` so self-update can write. Verify the installed tree is writable by the run-user (no elevation) — self-update dry check.
- [ ] 2.3 `[Run]` invokes `wdb-agent.exe finalize --name {code:Machine} [--user {code:RunUser}]` post-copy; support silent params `/MACHINE=` `/DIR=` `/RUNUSER=` (`{param:...}`). Verify interactive + `/VERYSILENT /MACHINE=...` both install + start the agent.
- [ ] 2.4 Pre-install purge (`[Code]`): stop any running `wdb-agent.exe`, `schtasks /delete /tn wdb-agent /f`, `netsh ... delete rule name=wdb-agent`; best-effort remove the old base by reading the existing task action. Verify installing over an old ad-hoc install leaves exactly one agent.
- [ ] 2.5 `[UninstallRun]` runs `wdb-agent.exe uninstall` before file removal; uninstall also removes `{app}` (runtime `agent\versions\` + data). Verify the OS installed-apps entry appears and uninstalling it leaves no task/firewall/dir/entry.

## 3. CI + distribution

- [ ] 3.1 In `release.yml`, install Inno Setup on the Windows runner (`choco install innosetup`) and compile `wdb-agent.iss` (version from the tag/agent version) after `packageAgent`; publish `wdb-agent-setup-<ver>.exe` as a release asset. Keep the raw app-image zip (self-update) and note the setup exe as the first-install artifact. Verify the asset appears on a test release.
- [ ] 3.2 Update README/first-install docs to use `wdb-agent-setup-<ver>.exe` (interactive + silent examples); mark `install-agent.ps1` legacy.

## 4. Verification

- [ ] 4.1 Live on the kiosk: run the new setup over the existing `Documents\...` install → it purges the old agent, installs to `C:\ProgramData\wdb-agent`, appears in Apps & features, agent runs + announces as the given name. Then push a self-update → confirm it writes under ProgramData and the new version boots (no revert). Then uninstall from Apps & features → nothing left behind.
- [ ] 4.2 Silent install on a second box: `wdb-agent-setup-<ver>.exe /VERYSILENT /MACHINE=wall-05` → installs + starts with no prompts.
- [ ] 4.3 Run `openspec validate add-agent-inno-installer --strict` and `./gradlew :wdb-agent:build`; both pass.
