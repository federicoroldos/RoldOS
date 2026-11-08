# Renders the README promo images: docs/promo/*.html -> docs/images/*.png (headless Edge).
# Usage (PowerShell, from the repo root):  .\docs\promo\render.ps1
$ErrorActionPreference = 'Stop'
$promo = $PSScriptRoot
$out = Join-Path (Split-Path $promo -Parent) 'images'
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
          "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
$pages = @{ 'hero' = '1600,560'; 'stats' = '1600,720'; 'tiles' = '1600,820' }
foreach ($name in $pages.Keys) {
    $url = 'file:///' + ((Join-Path $promo "$name.html") -replace '\\', '/')
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
        --window-size=$($pages[$name]) --virtual-time-budget=6000 `
        --screenshot="$(Join-Path $out "$name.png")" $url 2>$null
    Start-Sleep -Seconds 2
    Write-Host "rendered $name.png"
}
