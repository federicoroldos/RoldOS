# Renders RoldOS's in-app brand assets from brand.html (headless Edge, transparent background),
# trims the transparent edges, and writes them into the app's resources.
# Usage (PowerShell, from the repo root):  .\docs\promo\render-brand.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$promo = $PSScriptRoot
$res = Join-Path $promo '..\..\app\src\main\res\drawable-xhdpi' | Resolve-Path
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
          "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1

function Trim-Transparent($path) {
    # Read through memory so the file isn't locked when we overwrite it.
    $ms = New-Object IO.MemoryStream(, [IO.File]::ReadAllBytes($path))
    $src = New-Object Drawing.Bitmap($ms)
    $minX = $src.Width; $minY = $src.Height; $maxX = -1; $maxY = -1
    for ($y = 0; $y -lt $src.Height; $y++) { for ($x = 0; $x -lt $src.Width; $x++) {
        if ($src.GetPixel($x, $y).A -gt 8) {
            if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y } } } }
    $rect = New-Object Drawing.Rectangle $minX, $minY, ($maxX - $minX + 1), ($maxY - $minY + 1)
    $out = New-Object Drawing.Bitmap $rect.Width, $rect.Height, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [Drawing.Graphics]::FromImage($out)
    $g.DrawImage($src, (New-Object Drawing.Rectangle 0, 0, $rect.Width, $rect.Height), $rect, [Drawing.GraphicsUnit]::Pixel)
    $g.Dispose(); $src.Dispose(); $ms.Dispose()
    $out.Save($path, [Drawing.Imaging.ImageFormat]::Png); $out.Dispose()
}

foreach ($asset in @(@{ a = 'wordmark'; size = '760,180'; file = 'wordmark.png' },
                     @{ a = 'icon';     size = '192,192'; file = 'ic_launcher.png' })) {
    $url = 'file:///' + ((Join-Path $promo 'brand.html') -replace '\\', '/') + "?a=$($asset.a)"
    $target = Join-Path $res $asset.file
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
        --default-background-color=00000000 --window-size=$($asset.size) --virtual-time-budget=5000 `
        --screenshot="$target" $url 2>$null
    Start-Sleep -Seconds 2
    Trim-Transparent $target
    Write-Host ("{0}: {1} KB" -f $asset.file, [math]::Round((Get-Item $target).Length / 1KB, 1))
}
