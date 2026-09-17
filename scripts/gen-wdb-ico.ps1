<#
.SYNOPSIS
  Generate scripts/wdb.ico (the wdb bridge motif) from GDI+ — no external tools.

.DESCRIPTION
  Draws the same bridge mark as the plugin icon (an arc between two nodes, JetBrains blue #3574F0)
  at 16/32/48/256 px and assembles a multi-resolution PNG-in-ICO. Run once when the mark changes:
    powershell -ExecutionPolicy Bypass -File scripts\gen-wdb-ico.ps1
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$blue = [System.Drawing.Color]::FromArgb(53, 116, 240)   # #3574F0
$sizes = @(256, 48, 32, 16)
$pngs = @()

foreach ($sz in $sizes) {
    $s = $sz / 40.0   # SVG viewBox is 40x40
    $bmp = New-Object System.Drawing.Bitmap($sz, $sz, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $pen = New-Object System.Drawing.Pen($blue, [single](2.6 * $s))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    # arc: M9 27 C 15 12, 25 12, 31 27
    $g.DrawBezier($pen, [single](9*$s),[single](27*$s), [single](15*$s),[single](12*$s), [single](25*$s),[single](12*$s), [single](31*$s),[single](27*$s))
    $brush = New-Object System.Drawing.SolidBrush($blue)
    $r = 3.8 * $s
    foreach ($cx in @(9, 31)) {
        $g.FillEllipse($brush, [single]($cx*$s - $r), [single](27*$s - $r), [single](2*$r), [single](2*$r))
    }
    $g.Dispose(); $pen.Dispose(); $brush.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $pngs += ,($ms.ToArray())
}

# Assemble ICO (PNG-compressed entries; valid on Windows Vista+ and Inno Setup 6).
$out = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($out)
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)  # ICONDIR
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz = $sizes[$i]; $len = $pngs[$i].Length
    $bw.Write([byte]($(if ($sz -ge 256) { 0 } else { $sz })))   # width  (0 = 256)
    $bw.Write([byte]($(if ($sz -ge 256) { 0 } else { $sz })))   # height
    $bw.Write([byte]0); $bw.Write([byte]0)                       # colors, reserved
    $bw.Write([uint16]1); $bw.Write([uint16]32)                 # planes, bpp
    $bw.Write([uint32]$len); $bw.Write([uint32]$offset)         # bytesInRes, offset
    $offset += $len
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'wdb.ico'), $out.ToArray())
$out.Dispose()
Write-Host "wrote scripts\wdb.ico ($($sizes -join ',') px)"
