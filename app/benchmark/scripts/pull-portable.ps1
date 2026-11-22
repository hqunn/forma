# FORMA benchmark: pull a run off the phone and analyse it.
#
# Runs either from the repo (resolves adb from local.properties) or from the
# portable bundle (resolves adb sitting next to this script), so one script
# serves both the build machine and a laptop with no Android SDK on it.
#
#   pwsh -File pull-portable.ps1          # or double-click RUN-ME.cmd
#
# Produces, beside this script:
#   results\<run>\      every CSV pulled off the device
#   report.txt          a compact text report, small enough to paste
#   forma-results-*.zip everything, to carry back

param(
    # Re-run the analysis over whatever is already in results\, with no phone
    # attached. Used to test this script, and to redo the maths after a change
    # without asking for the handset back.
    [switch]$SkipPull,

    # Seconds of a Mode B run that were performed standing, before the subject
    # sat down. A ten-minute run exists to measure thermal drift, which posture
    # does not affect, but the heel figures are only comparable with the
    # pre-patch sweep for the part of the run that matches its framing: one
    # subject standing in the sagittal view. Given a value, the frames before
    # it are analysed separately and that segment alone is compared.
    [int]$StandingUntilS = 0
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$local = Join-Path $here "results"
$ready = @("(offline)")
$runs = @()

if ($SkipPull) {
    if (-not (Test-Path $local)) { throw "No results\ directory to analyse" }
    $runs = @(Get-ChildItem $local -Directory | ForEach-Object { $_.Name })
    if (-not $runs) { throw "results\ is empty" }
    Write-Host "offline: analysing $($runs.Count) run(s) already pulled"
}

# ---------------------------------------------------------------- adb

if (-not $SkipPull) {

$adb = Join-Path $here "adb.exe"
if (-not (Test-Path $adb)) {
    $lp = Join-Path $here "..\local.properties"
    if (Test-Path $lp) {
        $sdk = (Get-Content $lp | Where-Object { $_ -match "^sdk\.dir=" }) -replace "^sdk\.dir=", ""
        $adb = Join-Path $sdk "platform-tools\adb.exe"
    }
}
if (-not (Test-Path $adb)) { throw "adb.exe not found next to this script or via local.properties" }

# Files extracted from a downloaded zip carry Mark of the Web, which stops
# adb.exe launching on some machines. Clearing it is cheaper than explaining it.
Get-ChildItem $here -File | ForEach-Object {
    try { Unblock-File $_.FullName -ErrorAction SilentlyContinue } catch { }
}

Write-Host "adb: $adb"
& $adb start-server 2>&1 | Out-Null

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S" }
$ready = $devices | Where-Object { $_ -match "\tdevice$" }
if (-not $ready) {
    Write-Host ""
    Write-Host "No authorised device." -ForegroundColor Red
    if ($devices) { Write-Host ($devices -join "`n") }
    Write-Host ""
    Write-Host "Check, in order:"
    Write-Host "  1. The cable is a DATA cable, not charge-only."
    Write-Host "  2. The phone is unlocked."
    Write-Host "  3. USB debugging is still on (Settings > Developer options)."
    Write-Host "  4. This is a new computer, so the phone will ask"
    Write-Host "     'Allow USB debugging?' - tick Always allow, then accept."
    Write-Host "  5. If the list above says 'unauthorized', revoke USB debugging"
    Write-Host "     authorisations on the phone and replug."
    exit 1
}
Write-Host "device: $($ready -join ', ')"

# ---------------------------------------------------------------- pull

$remote = "/sdcard/Android/data/ai.forma.benchmark/files"
New-Item -ItemType Directory -Force -Path $local | Out-Null

$runs = & $adb shell "ls $remote 2>/dev/null" | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if (-not $runs) { throw "No run directories under $remote. Did the sweep reach DONE?" }

foreach ($run in $runs) {
    Write-Host "pulling $run"
    & $adb pull "$remote/$run" $local 2>&1 | Out-Null
}

}  # end: if (-not $SkipPull)

# ---------------------------------------------------------------- analysis

function Pct {
    param([double[]]$sorted, [double]$p)
    if ($sorted.Count -eq 0) { return 0 }
    $idx = [int][Math]::Floor(($sorted.Count - 1) * $p + 0.5)
    if ($idx -lt 0) { $idx = 0 }
    if ($idx -ge $sorted.Count) { $idx = $sorted.Count - 1 }
    return $sorted[$idx]
}

function Analyse {
    # Takes rows rather than a path so the same maths can be run over a slice
    # of a file. There is one implementation of the percentile method in this
    # script and it is the one that reproduces Metrics.kt.
    param($rows)

    $rows = @($rows)
    if ($rows.Count -eq 0) { return $null }

    $vis = 0.5
    $infer = New-Object System.Collections.ArrayList
    $prep = New-Object System.Collections.ArrayList
    $total = New-Object System.Collections.ArrayList
    $poseN = 0; $bothN = 0; $leftN = 0; $rightN = 0
    $jbSum = 0.0; $jbN = 0; $jlSum = 0.0; $jlN = 0
    $prevBoth = $null; $prevLeft = $null
    $thermal = @{}
    $firstMs = [double]$rows[0].elapsed_ms
    $lastMs = [double]$rows[$rows.Count - 1].elapsed_ms

    foreach ($r in $rows) {
        $p = [double]$r.prep_ms
        $i = [double]$r.infer_ms
        [void]$infer.Add($i); [void]$prep.Add($p); [void]$total.Add($p + $i)

        $found = ($r.pose_found -eq "1")
        if ($found) { $poseN++ }

        $lv = [double]$r.heel_l_vis
        $rv = [double]$r.heel_r_vis
        $okL = ($found -and $lv -ge $vis)
        $okR = ($found -and $rv -ge $vis)
        if ($okL) { $leftN++ }
        if ($okR) { $rightN++ }

        $t = [int]$r.thermal_status
        if ($thermal.ContainsKey($t)) { $thermal[$t]++ } else { $thermal[$t] = 1 }

        # both-heel jitter, matching Metrics.heelJitter exactly
        if ($okL -and $okR) {
            $bothN++
            if ($null -ne $prevBoth) {
                $dl = [Math]::Sqrt([Math]::Pow([double]$r.heel_l_x - [double]$prevBoth.heel_l_x, 2) +
                                   [Math]::Pow([double]$r.heel_l_y - [double]$prevBoth.heel_l_y, 2))
                $dr = [Math]::Sqrt([Math]::Pow([double]$r.heel_r_x - [double]$prevBoth.heel_r_x, 2) +
                                   [Math]::Pow([double]$r.heel_r_y - [double]$prevBoth.heel_r_y, 2))
                $jbSum += ($dl + $dr) / 2.0; $jbN++
            }
            $prevBoth = $r
        } else { $prevBoth = $null }

        # near-heel-only jitter: what the squat rule would actually rely on
        if ($okL) {
            if ($null -ne $prevLeft) {
                $jlSum += [Math]::Sqrt([Math]::Pow([double]$r.heel_l_x - [double]$prevLeft.heel_l_x, 2) +
                                       [Math]::Pow([double]$r.heel_l_y - [double]$prevLeft.heel_l_y, 2))
                $jlN++
            }
            $prevLeft = $r
        } else { $prevLeft = $null }
    }

    $n = $rows.Count
    $dur = ($lastMs - $firstMs) / 1000.0
    $si = [double[]]($infer.ToArray() | Sort-Object)
    $sp = [double[]]($prep.ToArray() | Sort-Object)
    $st = [double[]]($total.ToArray() | Sort-Object)

    $fps = 0.0
    if ($dur -gt 0) { $fps = $n / $dur }
    $jb = 0.0; if ($jbN -gt 0) { $jb = $jbSum / $jbN }
    $jl = 0.0; if ($jlN -gt 0) { $jl = $jlSum / $jlN }

    [pscustomobject]@{
        frames        = $n
        duration_s    = [Math]::Round($dur, 2)
        fps           = [Math]::Round($fps, 2)
        prep_p50      = Pct $sp 0.50
        infer_p50     = Pct $si 0.50
        infer_p95     = Pct $si 0.95
        infer_p99     = Pct $si 0.99
        infer_max     = $si[$si.Count - 1]
        total_p50     = Pct $st 0.50
        total_p95     = Pct $st 0.95
        pose_rate     = [Math]::Round($poseN / $n, 4)
        heel_both     = [Math]::Round($bothN / $n, 4)
        heel_near     = [Math]::Round($leftN / $n, 4)
        heel_far      = [Math]::Round($rightN / $n, 4)
        jitter_both   = [Math]::Round($jb, 5)
        jitter_near   = [Math]::Round($jl, 5)
        thermal_seen  = (($thermal.Keys | Sort-Object) -join "/")
        battery_start = $rows[0].battery_c
        battery_end   = $rows[$rows.Count - 1].battery_c
    }
}

function PoseTimeline {
    # Pose and heel detection per 30 s. A single figure over ten minutes
    # averages the subject walking into frame together with the subject
    # standing in it, and the average resembles a degraded model closely
    # enough to be mistaken for one.
    param($rows, [double]$vis = 0.5)

    $t0 = [double]$rows[0].elapsed_ms
    # Floor, not [int]. PowerShell's [int] cast rounds to nearest, so [int]0.6
    # is 1 and every bucket boundary lands half a window early: the first
    # bucket of this run came out 448 frames long instead of 895.
    $groups = $rows | Group-Object { [Math]::Floor((([double]$_.elapsed_ms) - $t0) / 30000) }

    $lines = @()
    $lines += ""
    $lines += "-- detection per 30 s --"
    $lines += "   window    frames   pose   both heels"
    foreach ($g in ($groups | Sort-Object { [int]$_.Name })) {
        $n = $g.Count
        $pose = 0; $both = 0
        foreach ($r in $g.Group) {
            if ($r.pose_found -eq "1") {
                $pose++
                if (([double]$r.heel_l_vis -ge $vis) -and ([double]$r.heel_r_vis -ge $vis)) { $both++ }
            }
        }
        $mark = ""
        if ($n -lt 300) { $mark = "  (partial window)" }
        $lines += ("  {0,5}s {1,9} {2,8:N3} {3,12:N3}{4}" -f ([int]$g.Name * 30), $n, ($pose / $n), ($both / $n), $mark)
    }
    return $lines
}

# ---------------------------------------------------------------- baseline

# The pre-patch lite-gpu row of sweep-20261102-215904, which is the run of
# record and the only thing a new measurement can honestly be compared with.
# One subject standing in the sagittal framing of 1.3.1, sixty measured
# seconds, software rotation still in the analyzer.
$Baseline = [pscustomobject]@{
    label       = "lite-gpu, sweep-20261102-215904, pre-patch"
    fps         = 17.70
    prep_p50    = 44
    infer_p50   = 52
    infer_p95   = 59
    total_p95   = 107
    pose_rate   = 1.0000
    heel_both   = 1.0000
    jitter_both = 0.00119
}

function CompareToBaseline {
    param($a, [string]$note)

    $lines = @()
    $lines += ""
    $lines += "-- against the pre-patch baseline ($note) --"
    $lines += ("  {0,-12} {1,10} {2,10}  {3}" -f "", "now", "before", "")

    function Row([string]$name, $now, $before, [string]$comment) {
        "  {0,-12} {1,10} {2,10}  {3}" -f $name, $now, $before, $comment
    }

    $prepDelta = $Baseline.prep_p50 - $a.prep_p50
    $lines += Row "prep p50" $a.prep_p50 $Baseline.prep_p50 ("{0:+0;-0;0} ms, allocation is 10 ms (4.1)" -f (-$prepDelta))
    $lines += Row "infer p50" $a.infer_p50 $Baseline.infer_p50 "should not move; the patch does not touch inference"
    $lines += Row "infer p95" $a.infer_p95 $Baseline.infer_p95 ""
    $lines += Row "total p95" $a.total_p95 $Baseline.total_p95 "NFR-P3 gate is 120 ms"
    $lines += Row "fps" $a.fps $Baseline.fps "NFR-P2 gate is 16 fps"
    $lines += Row "pose_rate" $a.pose_rate $Baseline.pose_rate "1.0000 means the rotation is not backwards"
    $lines += Row "heel both" $a.heel_both $Baseline.heel_both "geometry check"
    $lines += Row "jitter both" $a.jitter_both $Baseline.jitter_both "geometry check, normalised units"
    return $lines
}

# ---------------------------------------------------------------- report

$out = New-Object System.Collections.ArrayList
function Say($line) { Write-Host $line; [void]$out.Add($line) }

Say "FORMA benchmark results"
Say ("pulled " + (Get-Date -Format "yyyy-MM-dd HH:mm"))
Say ("device " + ($ready -join ", "))
Say ""

foreach ($run in $runs) {
    $dir = Join-Path $local $run
    Say "=== $run ==="

    $sum = Join-Path $dir "summary.csv"
    if (Test-Path $sum) {
        Say ""
        Say "-- summary.csv, verbatim --"
        Get-Content $sum | ForEach-Object { Say $_ }
    } else {
        Say "(no summary.csv - the run did not reach DONE)"
    }

    # Mode B writes buckets.csv. It is the only file that answers the thermal
    # question, because the figure that matters is the frame rate in the last
    # bucket and not the mean over the run.
    $bucketFile = Join-Path $dir "buckets.csv"
    if (Test-Path $bucketFile) {
        $b = @(Import-Csv $bucketFile)

        # The runner emits a bucket for every 30 s window that holds any
        # frame, so the last one is almost always a stub: a 600.16 s run
        # produces a 600 s bucket containing 5 frames. Reading drift against
        # it reports a thermal collapse that did not happen, which is what
        # the first real Mode B run did report. A bucket is partial exactly
        # when its window runs past the end of the run, which is a fact about
        # the duration rather than a judgement about the numbers, so this can
        # never swallow a genuine collapse.
        $runS = 0.0
        $sumFile = Join-Path $dir "summary.csv"
        if (Test-Path $sumFile) {
            $sm = @(Import-Csv $sumFile)
            if ($sm.Count -gt 0) { $runS = [double]$sm[0].duration_s }
        }
        $partial = @()
        if ($runS -gt 0) {
            $partial = @($b | Where-Object { ([double]$_.elapsed_s + 30) -gt $runS })
            $b = @($b | Where-Object { ([double]$_.elapsed_s + 30) -le $runS })
        }

        if ($b.Count -gt 0) {
            Say ""
            Say "-- buckets.csv, the thermal answer --"
            $first = [double]$b[0].fps
            $last = [double]$b[$b.Count - 1].fps
            $peak = ($b | ForEach-Object { [double]$_.fps } | Measure-Object -Maximum).Maximum
            Say ("  {0} complete buckets of 30 s over {1:N0} s" -f $b.Count, $runS)
            foreach ($p in $partial) {
                Say ("  {0}s dropped: a partial window, not a measurement" -f $p.elapsed_s)
            }
            Say ("  first {0:N2} fps | peak {1:N2} | last {2:N2}" -f $first, $peak, $last)
            if ($first -gt 0) {
                Say ("  drift first to last: {0:N2} fps, {1:N1}%" -f ($last - $first), (100 * ($last - $first) / $first))
            }
            Say ""
            foreach ($row in $b) {
                $f = [double]$row.fps
                $bar = "#" * [int]([Math]::Round($f))
                Say ("  {0,5}s {1,6:N2}  {2}" -f $row.elapsed_s, $f, $bar)
            }
        }
    }

    $frameFiles = @(Get-ChildItem $dir -Filter "frames-*.csv" -ErrorAction SilentlyContinue)
    if ($frameFiles.Count -gt 0) {
        Say ""
        Say "-- recomputed from the frame data --"
        foreach ($f in ($frameFiles | Sort-Object Name)) {
            $config = $f.BaseName -replace "^frames-", ""
            $rowsAll = @(Import-Csv $f.FullName)
            $a = Analyse $rowsAll
            if ($null -eq $a) { Say ("{0}: no frames" -f $config); continue }
            Say ""
            Say ("[{0}]  {1} frames over {2}s = {3} fps" -f $config, $a.frames, $a.duration_s, $a.fps)
            Say ("  latency ms   prep p50 {0} | infer p50 {1}  p95 {2}  p99 {3}  max {4} | total p50 {5}  p95 {6}" -f $a.prep_p50, $a.infer_p50, $a.infer_p95, $a.infer_p99, $a.infer_max, $a.total_p50, $a.total_p95)
            Say ("  pose_rate {0}" -f $a.pose_rate)
            Say ("  heel  both {0} | near(L) {1} | far(R) {2}" -f $a.heel_both, $a.heel_near, $a.heel_far)
            Say ("  jitter  both {0} | near(L) {1}" -f $a.jitter_both, $a.jitter_near)
            Say ("  thermal seen {0} | battery {1}C -> {2}C" -f $a.thermal_seen, $a.battery_start, $a.battery_end)

            if ($a.duration_s -gt 120) {
                PoseTimeline $rowsAll | ForEach-Object { Say $_ }
            }

            if ($config -eq "lite-gpu") {
                CompareToBaseline $a "whole run" | ForEach-Object { Say $_ }
            }

            if ($StandingUntilS -gt 0) {
                # The seated remainder is excluded from the heel comparison
                # rather than averaged into it. Posture does not affect
                # prep_ms or the thermal columns, so the whole run is still
                # the right basis for those; it does affect where the heels
                # are, so only the part framed like the baseline is compared.
                $rows = $rowsAll
                $t0 = [double]$rows[0].elapsed_ms
                $standing = @($rows | Where-Object {
                    ([double]$_.elapsed_ms - $t0) -lt ($StandingUntilS * 1000)
                })
                $sa = Analyse $standing
                if ($null -ne $sa) {
                    Say ""
                    Say ("[{0}] standing segment, first {1}s: {2} frames" -f $config, $StandingUntilS, $sa.frames)
                    Say ("  pose_rate {0} | heel both {1} | jitter both {2} | jitter near {3}" -f $sa.pose_rate, $sa.heel_both, $sa.jitter_both, $sa.jitter_near)
                    CompareToBaseline $sa ("standing, first " + $StandingUntilS + "s") | ForEach-Object { Say $_ }
                }
            }
        }
    }
    Say ""
}

$reportPath = Join-Path $here "report.txt"
$out | Set-Content -Path $reportPath -Encoding UTF8

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$zipPath = Join-Path $here ("forma-results-" + $stamp + ".zip")
$toZip = @($local)
if (Test-Path $reportPath) { $toZip += $reportPath }
Compress-Archive -Path $toZip -DestinationPath $zipPath -Force

Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "report.txt  $reportPath" -ForegroundColor Green
Write-Host "zip         $zipPath" -ForegroundColor Green
Write-Host ""
Write-Host "report.txt holds everything a reader needs about this pull." -ForegroundColor Green
Write-Host "Keep the zip; it carries the raw frames if they are needed." -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
