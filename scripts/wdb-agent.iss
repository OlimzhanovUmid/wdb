; Inno Setup installer for the wdb agent (change add-agent-inno-installer).
;
; Compile (on Windows, with Inno Setup 6):
;   iscc /DAgentVersion=0.2.17 /DAppImage="..\wdb-agent\build\jpackage\wdb-agent" scripts\wdb-agent.iss
; Produces:  scripts\wdb-agent-setup-<AgentVersion>.exe
;
; Silent / unattended:
;   wdb-agent-setup-<ver>.exe /VERYSILENT /SUPPRESSMSGBOXES /MACHINE=wall-04 [/RUNUSER=kioskuser] [/DIR="C:\ProgramData\wdb-agent"]

#ifndef AgentVersion
  #define AgentVersion "0.0.0"
#endif
#ifndef AppImage
  #define AppImage "..\wdb-agent\build\jpackage\wdb-agent"
#endif

[Setup]
; Stable AppId → a permanent uninstall identity in the OS installed-apps list.
AppId={{A1B2C3D4-E5F6-4789-ABCD-1234567890AB}
AppName=wdb agent
AppVerName=wdb agent {#AgentVersion}
AppVersion={#AgentVersion}
AppPublisher=Umid Olimzhanov
DefaultDirName={commonappdata}\wdb-agent
DisableProgramGroupPage=yes
PrivilegesRequired=admin
OutputDir=.
OutputBaseFilename=wdb-agent-setup-{#AgentVersion}
Compression=lzma2
SolidCompression=yes
Uninstallable=yes
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Files]
; Place the app-image DIRECTLY into the versioned layout so launch.cmd / self-update find the exe at
; {app}\agent\versions\<ver>\wdb-agent.exe (no wrapper folder — that nesting broke self-update before).
Source: "{#AppImage}\*"; DestDir: "{app}\agent\versions\{#AgentVersion}"; Flags: recursesubdirs createallsubdirs ignoreversion
; Bundled (not installed) — extracted to {tmp} and run pre-install to purge any previous agent.
Source: "purge-old-agent.ps1"; Flags: dontcopy

[Run]
; Grant the run-user Modify on the install tree so the running agent can write versions/ during
; self-update without elevation. {code:RunUserForAcl} never returns empty.
Filename: "icacls.exe"; Parameters: """{app}"" /grant *S-1-1-0:(OI)(CI)R ""{code:RunUserForAcl}"":(OI)(CI)M /T"; Flags: runhidden waituntilterminated
; Wire it up (pointer + launcher + logon task + firewall) reusing the agent's own logic.
Filename: "{app}\agent\versions\{#AgentVersion}\wdb-agent.exe"; Parameters: "{code:FinalizeParams}"; Flags: runhidden waituntilterminated
; Start now so the operator doesn't have to sign out/in (the logon task otherwise fires next logon).
Filename: "schtasks.exe"; Parameters: "/run /tn wdb-agent"; Flags: runhidden

[UninstallRun]
; Stop the running agent + remove task/firewall BEFORE Inno deletes files (else the versioned exe is locked).
Filename: "{app}\agent\versions\{#AgentVersion}\wdb-agent.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "wdbAgentUninstall"

[UninstallDelete]
; Remove the runtime layout the agent created at run time (self-update's versions/, data) — Inno
; only tracks what it installed, not what the agent wrote later.
Type: filesandordirs; Name: "{app}\agent"
Type: dirifempty; Name: "{app}"

[Code]
var
  ProvisionPage: TInputQueryWizardPage;

// The machine name: silent /MACHINE=, else the wizard field, else the computer name.
function MachineName(Param: String): String;
begin
  Result := ExpandConstant('{param:Machine|}');
  if (Result = '') and Assigned(ProvisionPage) then
    Result := Trim(ProvisionPage.Values[0]);
  if Result = '' then
    Result := ExpandConstant('{computername}');
end;

// The run-user (kiosk auto-login user) for the logon task: silent /RUNUSER=, else the wizard field,
// else '' meaning "let the agent default to the (elevated) installer user".
function RunUser(Param: String): String;
begin
  Result := ExpandConstant('{param:RunUser|}');
  if (Result = '') and Assigned(ProvisionPage) then
    Result := Trim(ProvisionPage.Values[1]);
end;

// Same, but never empty (for the ACL grant): fall back to the current user.
function RunUserForAcl(Param: String): String;
begin
  Result := RunUser('');
  if Result = '' then
    Result := ExpandConstant('{username}');
end;

// Full argument string for `wdb-agent.exe finalize`, adding --user only when specified.
function FinalizeParams(Param: String): String;
var u: String;
begin
  Result := 'finalize --base "' + ExpandConstant('{app}') + '" --name "' + MachineName('') + '"';
  u := RunUser('');
  if u <> '' then
    Result := Result + ' --user "' + u + '"';
end;

procedure InitializeWizard();
begin
  ProvisionPage := CreateInputQueryPage(wpSelectDir,
    'Machine', 'Name this machine and choose the run-user',
    'The agent advertises the machine name in discovery and status, and runs as the kiosk auto-login user.');
  ProvisionPage.Add('Machine name (e.g. wall-04):', False);
  ProvisionPage.Add('Run as user (kiosk auto-login user; blank = current user):', False);
  ProvisionPage.Values[0] := ExpandConstant('{computername}');
end;

// Pre-install: purge ANY previous agent (stop process, remove task + firewall by fixed name, delete
// the old install base found from the task's launch.cmd path) so re-install/migration is clean and
// idempotent. Delegated to the bundled purge-old-agent.ps1 (best-effort; never blocks the install).
function PrepareToInstall(var NeedsRestart: Boolean): String;
var code: Integer;
begin
  ExtractTemporaryFile('purge-old-agent.ps1');
  Exec('powershell.exe',
    '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{tmp}\purge-old-agent.ps1') + '"',
    '', SW_HIDE, ewWaitUntilTerminated, code);
  Result := '';
end;
