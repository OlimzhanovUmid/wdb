<#
.SYNOPSIS
  One-shot installer for the wdb-agent on a demo-wall box. The ONLY thing an operator
  supplies is the wall name.

.DESCRIPTION
  Meant to sit next to the packaged agent (jpackage app-image) produced by
  `:wdb-agent:packageAgentInstaller` — i.e. in the same folder as `wdb-agent.exe`.
  Copy that folder to the wall box and run this script. It:
    1. self-elevates (the install needs admin for the Task Scheduler task + firewall rule),
    2. asks for the wall name if not passed via -Name,
    3. runs `wdb-agent.exe install --name <wall>` (versioned layout + logon autostart + firewall),
    4. starts the agent immediately via `schtasks /run` (no need to wait for the next logon).

.PARAMETER Name
  The wall/machine name the agent advertises (e.g. "wall-04"). If omitted, prompted for.

.PARAMETER JdwpPort
  Optional fixed JDWP port for debug attach. Omit to use the agent default.

.EXAMPLE
  # interactive — prompts for the wall name:
  powershell -ExecutionPolicy Bypass -File install-agent.ps1

.EXAMPLE
  # non-interactive:
  powershell -ExecutionPolicy Bypass -File install-agent.ps1 -Name wall-04
#>
param(
    [string]$Name = "",
    [int]$JdwpPort = 0
)

$ErrorActionPreference = "Stop"

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# The agent exe ships next to this script inside the jpackage app-image.
$exe = Join-Path $PSScriptRoot "wdb-agent.exe"
if (-not (Test-Path $exe)) {
    throw "wdb-agent.exe not found next to this script ($PSScriptRoot). Run this from inside the packaged app-image folder."
}

# Ask for the wall name up front (before elevating) so the UAC prompt is the only surprise.
if ([string]::IsNullOrWhiteSpace($Name)) {
    $Name = (Read-Host "Wall name (e.g. wall-04)").Trim()
    if ([string]::IsNullOrWhiteSpace($Name)) { throw "Wall name is required." }
}

# Elevate if needed, forwarding the already-collected args so the elevated run is silent.
if (-not (Test-Admin)) {
    Write-Host "Elevating (install needs admin)..." -ForegroundColor Yellow
    $psArgs = @("-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"", "-Name", "`"$Name`"")
    if ($JdwpPort -gt 0) { $psArgs += @("-JdwpPort", "$JdwpPort") }
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $psArgs
    return
}

$TaskName = "wdb-agent"

Write-Host "== installing wdb-agent as '$Name' ==" -ForegroundColor Cyan
$installArgs = @("install", "--name", $Name)
if ($JdwpPort -gt 0) { $installArgs += @("--jdwp-port", "$JdwpPort") }
& $exe @installArgs

Write-Host "== starting agent now ==" -ForegroundColor Cyan
# The install registers a logon task that fires 15s after the next logon; kick it now so the
# operator doesn't have to sign out and back in.
schtasks /run /tn $TaskName

Write-Host ""
Write-Host "wdb-agent installed and running as '$Name'." -ForegroundColor Green
Write-Host "It will also auto-start ~15s after every logon. Uninstall: wdb-agent.exe uninstall (elevated)."
