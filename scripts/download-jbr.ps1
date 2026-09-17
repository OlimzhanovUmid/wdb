<#
.SYNOPSIS
  Download + verify a JetBrains Runtime (JBR) and lay it out as the agent's runtime/ directory.

.DESCRIPTION
  Used by the "web" variant of the agent installer (wdb-agent.iss with /DWeb): the app-image ships
  without its bundled runtime, and this downloads a pinned JBR 21 (windows-x64) at install time,
  verifies its sha256, and extracts its contents into <Dest> so the app-image is complete
  (Dest = ...\versions\<ver>\runtime, and the launcher runs ...\wdb-agent.exe which uses .\runtime).
  The agent needs JBR specifically (Compose Hot Reload / devtools), not a generic JDK. Fails loudly
  (non-zero exit) so the installer can abort rather than leave a runtime-less agent.
#>
param(
    [Parameter(Mandatory)][string] $Url,
    [Parameter(Mandatory)][string] $Sha256,
    [Parameter(Mandatory)][string] $Dest
)
$ErrorActionPreference = 'Stop'

$archive = Join-Path $env:TEMP ("jbr-" + [guid]::NewGuid() + ".tar.gz")
$extract = Join-Path $env:TEMP ("jbr-ex-" + [guid]::NewGuid())
try {
    Write-Host "Downloading JBR: $Url"
    Invoke-WebRequest -Uri $Url -OutFile $archive -UseBasicParsing

    $actual = (Get-FileHash $archive -Algorithm SHA256).Hash
    if ($actual -ne $Sha256.ToUpper()) {
        throw "JBR sha256 mismatch: expected $($Sha256.ToUpper()), got $actual"
    }

    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    tar -xzf $archive -C $extract   # Windows 10+ ships bsdtar (tar.exe)
    if ($LASTEXITCODE -ne 0) { throw "tar extraction failed ($LASTEXITCODE)" }

    # JBR archives usually extract to a single top dir (jbr/ or jbr-21.../); occasionally at root.
    $root = if (Test-Path (Join-Path $extract 'bin\java.exe')) {
        $extract
    } else {
        (Get-ChildItem $extract -Directory | Select-Object -First 1).FullName
    }
    if (-not $root -or -not (Test-Path (Join-Path $root 'bin\java.exe'))) {
        throw "unexpected JBR layout: no bin\java.exe found after extraction"
    }

    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Copy-Item -Path (Join-Path $root '*') -Destination $Dest -Recurse -Force

    if (-not (Test-Path (Join-Path $Dest 'bin\java.exe'))) {
        throw "runtime not laid out: $Dest\bin\java.exe missing"
    }
    Write-Host "JBR installed to $Dest"
} finally {
    Remove-Item $archive -Force -ErrorAction SilentlyContinue
    Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
}
