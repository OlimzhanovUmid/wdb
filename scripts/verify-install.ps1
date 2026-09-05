<#
.SYNOPSIS
  Verifies wdb-agent install/uninstall on a real Windows box. RUN ELEVATED.

.DESCRIPTION
  Runs the agent's `install` then checks that the Task Scheduler logon task and the
  inbound firewall rule exist, then runs `uninstall` and checks they are gone.
  Task 4.3 / 4.4 in the bootstrap-windows-debug-bridge change.

  As of the packaging-and-self-update change, `install` also lays the agent out under
  <base>/agent/versions/<version>/ with a `current` junction the task launches through,
  and the wall is driven by the packaged wdb.exe (build via `:wdb-cli:packageCli`).
  Agent builds are rolled out with `wdb agent-update [--all]`.

.PARAMETER Exe
  Path to the packaged wdb-agent launcher (from jpackage, task 4.5). If omitted, the
  script falls back to running the agent via the Gradle wrapper (the scheduled task
  will then point at java.exe, which still proves the mechanism).

.EXAMPLE
  # Build the app-image first (task 4.5):
  #   .\gradlew.bat :wdb-agent:packageAgent -PjbrHome=<path to JBR 21>
  # then, in an ELEVATED PowerShell from the repo root:
  powershell -ExecutionPolicy Bypass -File scripts\verify-install.ps1 -Exe wdb-agent\build\jpackage\wdb-agent\wdb-agent.exe
#>
param(
    [string]$Exe = ""
)

# Continue (not Stop): schtasks/netsh are native commands whose non-zero exit or
# stderr must not abort the script before the cleanup uninstall runs.
$ErrorActionPreference = "Continue"
$TaskName = "wdb-agent"
$RuleName = "wdb-agent"

function Assert-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "This script must run in an ELEVATED (Administrator) PowerShell."
    }
}

function Invoke-Agent([string[]]$AgentArgs) {
    if ($Exe -ne "") {
        & $Exe @AgentArgs
    } else {
        Write-Host "No -Exe given; running agent via Gradle wrapper (launcher will be java.exe)."
        & .\gradlew.bat ":wdb-agent:run" "--args=$($AgentArgs -join ' ')" --console=plain
    }
}

Assert-Admin

try {
    Write-Host "== install ==" -ForegroundColor Cyan
    Invoke-Agent @("install", "--name", "wall-verify")

    Write-Host "== check scheduled task ==" -ForegroundColor Cyan
    schtasks /query /tn $TaskName /v /fo LIST | Select-String -Pattern "TaskName|Run As User|Schedule Type|Task To Run|Delay"

    Write-Host "== check firewall rule ==" -ForegroundColor Cyan
    netsh advfirewall firewall show rule name=$RuleName
} finally {
    Write-Host "== uninstall ==" -ForegroundColor Cyan
    Invoke-Agent @("uninstall")
}

Write-Host "== verify removed ==" -ForegroundColor Cyan
$taskGone = -not (schtasks /query /tn $TaskName 2>$null)
$ruleGone = (netsh advfirewall firewall show rule name=$RuleName) -match "No rules match"
if ($taskGone) { Write-Host "task removed: OK" -ForegroundColor Green } else { Write-Host "task STILL present" -ForegroundColor Red }
if ($ruleGone) { Write-Host "firewall rule removed: OK" -ForegroundColor Green } else { Write-Host "firewall rule STILL present" -ForegroundColor Red }
