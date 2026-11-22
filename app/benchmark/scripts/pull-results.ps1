# Pulls benchmark run directories off the device into app/benchmark/results/
# and prints the summary table for each new run.
#
# adb is not on PATH on this machine, so the path is resolved from the SDK
# location in local.properties rather than assumed.
#
# Run from anywhere:  pwsh -File app/benchmark/scripts/pull-results.ps1

$ErrorActionPreference = "Stop"

$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

$sdk = (Get-Content (Join-Path $root "local.properties") |
        Where-Object { $_ -match "^sdk\.dir=" }) -replace "^sdk\.dir=", ""
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$devices = & $adb devices | Select-Object -Skip 1 |
           Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    throw "No device. Check the cable, and that USB debugging is authorised on the phone."
}

$remote = "/sdcard/Android/data/ai.forma.benchmark/files"
$local  = Join-Path $root "results"
New-Item -ItemType Directory -Force -Path $local | Out-Null

$runs = & $adb shell "ls $remote 2>/dev/null" |
        ForEach-Object { $_.Trim() } | Where-Object { $_ }
if (-not $runs) { throw "No run directories on the device under $remote" }

foreach ($run in $runs) {
    Write-Host "pulling $run"
    & $adb pull "$remote/$run" $local | Out-Null
}

Write-Host ""
foreach ($run in $runs) {
    $summary = Join-Path $local "$run\summary.csv"
    if (-not (Test-Path $summary)) { continue }
    Write-Host "=== $run ===" -ForegroundColor Cyan
    Import-Csv $summary |
        Select-Object config, available, frames, fps, infer_p50_ms, infer_p95_ms,
                      pose_rate, heel_detect_rate, heel_jitter_norm, thermal_max |
        Format-Table -AutoSize
    $buckets = Join-Path $local "$run\buckets.csv"
    if (Test-Path $buckets) {
        Write-Host "buckets (endurance, fps per 30 s):"
        Import-Csv $buckets | Format-Table -AutoSize
    }
}

Write-Host "Pulled into $local"
