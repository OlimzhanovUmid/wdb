<#
.SYNOPSIS
  Extract a downloaded JBR archive into the agent's runtime/ directory.

.DESCRIPTION
  Used by the "web" agent installer: Inno's download page fetches + sha256-verifies the JBR archive
  to {tmp}; this only unpacks it into <Dest> (= ...\versions\<ver>\runtime) so the app-image is
  complete. Kept a separate step because Inno can't untar a .tar.gz natively. Fails loudly.
#>
param(
    [Parameter(Mandatory)][string] $Archive,
    [Parameter(Mandatory)][string] $Dest
)
$ErrorActionPreference = 'Stop'

$extract = Join-Path $env:TEMP ("jbr-ex-" + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    tar -xzf $Archive -C $extract   # Windows 10+ ships bsdtar (tar.exe)
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
    Write-Host "JBR extracted to $Dest"
} finally {
    Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
}
