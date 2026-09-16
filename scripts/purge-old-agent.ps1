<#
.SYNOPSIS
  Best-effort removal of any previous wdb-agent install, so a fresh install is clean and idempotent.

.DESCRIPTION
  Run by the installer (wdb-agent.iss) before installing, and safe to run standalone. It:
    1. reads the old install base from the existing "wdb-agent" scheduled task's action
       (its launch.cmd path is <base>\agent\launch.cmd) BEFORE removing the task,
    2. stops the running agent (ends the task, kills wdb-agent.exe),
    3. removes the scheduled task and the firewall rule (fixed names),
    4. deletes the old install base directory.
  Everything is best-effort: it never throws, so a partial or absent prior install is fine.
#>
$ErrorActionPreference = 'SilentlyContinue'

$TaskName = 'wdb-agent'
$RuleName = 'wdb-agent'

# 1. Old base from the existing task's launch.cmd path (before we delete the task).
$oldBase = $null
try {
    $xml = (schtasks /query /tn $TaskName /xml ONE 2>$null | Out-String)
    if ($xml) {
        $m = [regex]::Match($xml, '([A-Za-z]:\\[^"<]*?)\\agent\\launch\.cmd')
        if ($m.Success) { $oldBase = $m.Groups[1].Value }
    }
} catch {}

# 2. Stop the running agent.
try { schtasks /end /tn $TaskName 2>$null | Out-Null } catch {}
try { taskkill /f /im wdb-agent.exe 2>$null | Out-Null } catch {}

# 3. Remove the scheduled task + firewall rule.
try { schtasks /delete /tn $TaskName /f 2>$null | Out-Null } catch {}
try { netsh advfirewall firewall delete rule name=$RuleName 2>$null | Out-Null } catch {}

# 4. Delete the old install base (only if we resolved a plausible path that still exists).
if ($oldBase -and (Test-Path -LiteralPath $oldBase)) {
    try { Remove-Item -LiteralPath $oldBase -Recurse -Force 2>$null } catch {}
    Write-Host "removed old base: $oldBase"
}

Write-Host "purge complete"
exit 0
