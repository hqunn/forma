# Downloads the three MediaPipe PoseLandmarker bundles into the app assets.
# The .task files are not committed: they are third-party model weights,
# ~46 MB in total, and they are reproducible from these URLs.
#
# Run from anywhere:  pwsh -File app/benchmark/scripts/fetch-models.ps1

$ErrorActionPreference = "Stop"

$assets = Join-Path $PSScriptRoot "..\app\src\main\assets"
$assets = [System.IO.Path]::GetFullPath($assets)
New-Item -ItemType Directory -Force -Path $assets | Out-Null

$base = "https://storage.googleapis.com/mediapipe-models/pose_landmarker"

$models = @(
    @{ name = "lite";  bytes = 5777746  },
    @{ name = "full";  bytes = 9398198  },
    @{ name = "heavy"; bytes = 30664242 }
)

foreach ($m in $models) {
    $file = "pose_landmarker_$($m.name).task"
    $url  = "$base/pose_landmarker_$($m.name)/float16/latest/$file"
    $dest = Join-Path $assets $file

    if ((Test-Path $dest) -and ((Get-Item $dest).Length -eq $m.bytes)) {
        Write-Host "ok       $file  (already present)"
        continue
    }

    Write-Host "fetching $file ..."
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing

    $size = (Get-Item $dest).Length
    if ($size -ne $m.bytes) {
        throw "$file is $size bytes, expected $($m.bytes). Download incomplete."
    }
    Write-Host "ok       $file  ($size bytes)"
}

Write-Host ""
Write-Host "Assets ready in $assets"
