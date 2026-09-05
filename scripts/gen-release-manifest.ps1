<#
.SYNOPSIS
  Stage the wdb release assets and generate latest.json + updatePlugins.xml.

.DESCRIPTION
  Collects the four built distribution archives (agent installer, cli, mcp, plugin) into an
  output directory, computes each asset's sha256 + size, and writes:
    - latest.json      : { <component>: { version, asset, url, sha256, size } }
    - updatePlugins.xml: IntelliJ custom-plugin-repository index for the plugin

  Consumers read both indexes at GitHub's stable
  https://github.com/<repo>/releases/latest/download/<asset> URLs.

  The plugin id, version, and since/until-build are read from the BUILT plugin jar's
  META-INF/plugin.xml (single source of truth), so updatePlugins.xml can never drift from
  the plugin's real compatibility range. The agent versions independently via
  gradle.properties (wdbAgentVersion); cli/mcp/plugin use the release version (-WdbVersion,
  from the tag).

.EXAMPLE
  pwsh scripts/gen-release-manifest.ps1 -Repo OlimzhanovUmid/windows-debug-bridge -WdbVersion 1.2.3
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Repo,        # owner/repo, e.g. OlimzhanovUmid/windows-debug-bridge
    [Parameter(Mandatory)] [string] $WdbVersion,  # release version (from tag), drives cli/mcp/plugin
    [string] $OutDir = "release-staging"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot   # scripts/ lives under the repo root

# Agent versions independently — read it from gradle.properties.
$agentVersion = (Get-Content "$root/gradle.properties" |
    Select-String '^wdbAgentVersion=(.+)$').Matches.Groups[1].Value.Trim()
if (-not $agentVersion) { throw "wdbAgentVersion not found in gradle.properties" }

$components = [ordered]@{
    agent  = @{ path = "$root/wdb-agent/build/dist/wdb-agent-installer-$agentVersion.zip"; version = $agentVersion }
    cli    = @{ path = "$root/wdb-cli/build/distributions/wdb-cli-$WdbVersion.zip";         version = $WdbVersion }
    mcp    = @{ path = "$root/wdb-mcp/build/distributions/wdb-mcp-$WdbVersion.zip";         version = $WdbVersion }
    plugin = @{ path = "$root/wdb-plugin/build/distributions/wdb-plugin-$WdbVersion.zip";   version = $WdbVersion }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$base = "https://github.com/$Repo/releases/download/v$WdbVersion"

$manifest = [ordered]@{}
foreach ($name in $components.Keys) {
    $path = $components[$name].path
    if (-not (Test-Path $path)) { throw "missing release asset: $path" }
    $asset = Split-Path $path -Leaf
    Copy-Item $path (Join-Path $OutDir $asset) -Force
    $manifest[$name] = [ordered]@{
        version = $components[$name].version
        asset   = $asset
        url     = "$base/$asset"
        sha256  = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
        size    = (Get-Item $path).Length
    }
}

# UTF-8 without BOM regardless of PowerShell edition (5.1 vs 7) so consumers parse cleanly.
$jsonPath = Join-Path $OutDir "latest.json"
[System.IO.File]::WriteAllText($jsonPath, ($manifest | ConvertTo-Json -Depth 5))

# --- updatePlugins.xml: read id/version/build-range from the built plugin jar ---
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tmp = New-Item -ItemType Directory -Force -Path (Join-Path ([System.IO.Path]::GetTempPath()) ("wdbplugin-" + [guid]::NewGuid()))
try {
    Expand-Archive -Path $components.plugin.path -DestinationPath $tmp -Force
    $jar = Get-ChildItem -Path "$tmp/wdb-plugin/lib/wdb-plugin-$WdbVersion.jar" -File
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $entry = $zip.GetEntry("META-INF/plugin.xml")
        if (-not $entry) { throw "META-INF/plugin.xml not found in $($jar.Name)" }
        $sr = New-Object System.IO.StreamReader($entry.Open())
        $pluginXmlText = $sr.ReadToEnd(); $sr.Close()
    } finally { $zip.Dispose() }
} finally { Remove-Item $tmp -Recurse -Force }

$px = [xml]$pluginXmlText
$pluginId = $px.'idea-plugin'.id
$pluginVer = $px.'idea-plugin'.version
$since = $px.'idea-plugin'.'idea-version'.'since-build'
$until = $px.'idea-plugin'.'idea-version'.'until-build'
$pluginUrl = "$base/" + $manifest.plugin.asset

$xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="$pluginId" url="$pluginUrl" version="$pluginVer">
    <idea-version since-build="$since" until-build="$until" />
  </plugin>
</plugins>
"@
[System.IO.File]::WriteAllText((Join-Path $OutDir "updatePlugins.xml"), $xml)

Write-Host "Staged assets + latest.json + updatePlugins.xml in '$OutDir' (agent=$agentVersion, wdb=$WdbVersion)"
