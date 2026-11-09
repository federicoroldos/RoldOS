# Renders the README promo images: docs/promo/*.html -> docs/images/*.png (headless Edge).
# Usage (PowerShell, from the repo root):  .\docs\promo\render.ps1
$ErrorActionPreference = 'Stop'
$promo = $PSScriptRoot
$out = Join-Path (Split-Path $promo -Parent) 'images'
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
          "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
$pages = @{ 'hero' = '1600,500'; 'stats' = '1600,720'; 'tiles' = '1600,900'; 'social' = '1280,640'; 'memory' = '1600,720'; 'size' = '1600,720'; 'home' = '1600,1050'; 'playing' = '1600,580'; 'close' = '1600,410'; 'move' = '1600,700' }
# Output names: bump one (e.g. -2, -3) whenever its image changes. GitHub caches images hard.
$files = @{ 'hero' = 'roldos-hero.png'; 'stats' = 'roldos-stats-2.png'; 'tiles' = 'roldos-tiles.png'; 'social' = 'roldos-social.png'; 'memory' = 'roldos-memory-2.png'; 'size' = 'roldos-size.png'; 'playing' = 'roldos-playing.png'; 'close' = 'roldos-close.png'; 'move' = 'roldos-move-2.png'; 'home' = 'roldos-home-2.png' }
foreach ($name in $pages.Keys) {
    $url = 'file:///' + ((Join-Path $promo "$name.html") -replace '\\', '/')
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --allow-file-access-from-files `
        --window-size=$($pages[$name]) --virtual-time-budget=6000 `
        --screenshot="$(Join-Path $out ($files[$name]))" $url 2>$null
    Start-Sleep -Seconds 2
    Write-Host "rendered $($files[$name])"
}
