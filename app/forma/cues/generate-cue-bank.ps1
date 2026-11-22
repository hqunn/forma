# Generates the spoken bank from the exercise specifications.
#
# 4.4 chose option O3: a pre-authored bank, no synthesis step in the live
# path. This script is where "pre-authored" happens. It reads the texts from
# docs/specs, synthesises each one offline, trims the silence the synthesiser
# pads them with, and writes the .wav file the specification already names.
# Nothing here runs on the handset.
#
# The bank has two halves and they are spoken in disjoint windows:
#
#   cue_*   a fault correction, played during a set. NFR-P1 is measured
#           through these, and nothing else may speak while a set is running.
#   say_*   framing and calibration guidance, spoken before the set and never
#           during it. Added 2026-11-04, when it became clear that the user
#           stands where the screen cannot be read and in the configuration
#           4.1 describes cannot be seen at all. Before a set, speech is
#           not a nicety on top of the interface. It is the interface.
#
# The specifications are the single source of the filenames and the wording.
# Changing a cue means editing the specification and running this again, which
# is the only arrangement in which the file on disk and the text quoted in
# Chapter 7 cannot drift apart.
#
# **The trim is not cosmetic.** SAPI pads every utterance with about 100 ms of
# silence before the first word. 4.1 budgets 60 ms from audio dispatch to the
# first sample, and a silent first sample is not what that line means: the
# requirement is time to audio the user can hear. Shipping the padding would
# have put 100 ms of unbudgeted latency on the governing requirement of the
# system, invisibly, inside a binary asset. Measured before trimming:
# 100 to 110 ms of lead-in on all eight cues.
#
# Voice: a Windows SAPI voice, chosen because it is offline, free, and
# reproducible on this machine. It is adequate for the proof of concept and is
# not a final product decision. Replacing it means swapping the synthesiser
# here and re-running; every consumer references the filename, not the voice.
#
#   pwsh app/forma/cues/generate-cue-bank.ps1
#   pwsh app/forma/cues/generate-cue-bank.ps1 -Voice "Microsoft Mark" -Force

param(
    [string]$Voice = "Microsoft Zira Desktop",
    [int]$Rate = 0,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

# A short lead is kept so playback does not begin mid-waveform, which clicks.
$LeadMs = 8
# The tail only has to let the last consonant decay. SAPI ships about 750 ms.
$TailMs = 60
# Fraction of the peak below which a sample counts as silence.
$SilenceFloor = 0.02

<#
.SYNOPSIS
Rewrites a 16-bit PCM WAV in place with its leading and trailing silence cut.
.OUTPUTS
The number of milliseconds removed from the front.
#>
function Trim-Silence {
    param([string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ([System.Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne "RIFF" -or
        [System.Text.Encoding]::ASCII.GetString($bytes, 8, 4) -ne "WAVE") {
        throw "$Path is not a RIFF/WAVE file"
    }

    # Walk the chunk list rather than assuming a 44-byte header: SAPI emits a
    # 'fact' chunk on some voices and the data would then start 12 bytes later.
    $offset = 12
    $fmtAt = -1; $dataAt = -1; $dataLen = 0
    while ($offset -lt $bytes.Length - 8) {
        $id = [System.Text.Encoding]::ASCII.GetString($bytes, $offset, 4)
        $size = [BitConverter]::ToUInt32($bytes, $offset + 4)
        if ($id -eq "fmt ") { $fmtAt = $offset + 8 }
        if ($id -eq "data") { $dataAt = $offset + 8; $dataLen = $size; break }
        $offset += 8 + $size + ($size % 2)
    }
    if ($fmtAt -lt 0 -or $dataAt -lt 0) { throw "$Path has no fmt or data chunk" }

    $channels = [BitConverter]::ToUInt16($bytes, $fmtAt + 2)
    $rate = [BitConverter]::ToUInt32($bytes, $fmtAt + 4)
    $bits = [BitConverter]::ToUInt16($bytes, $fmtAt + 14)
    if ($bits -ne 16) { throw "$Path is $bits-bit; this trimmer handles 16-bit PCM only" }

    $sampleCount = [int]($dataLen / 2)
    $samples = New-Object int[] $sampleCount
    $peak = 0
    for ($i = 0; $i -lt $sampleCount; $i++) {
        $v = [BitConverter]::ToInt16($bytes, $dataAt + $i * 2)
        $samples[$i] = $v
        $a = [Math]::Abs($v)
        if ($a -gt $peak) { $peak = $a }
    }
    if ($peak -eq 0) { throw "$Path is silent" }

    $threshold = $peak * $SilenceFloor
    $first = 0
    while ($first -lt $sampleCount -and [Math]::Abs($samples[$first]) -le $threshold) { $first++ }
    $last = $sampleCount - 1
    while ($last -gt $first -and [Math]::Abs($samples[$last]) -le $threshold) { $last-- }

    $framesPerMs = $rate * $channels / 1000
    $startedAtMs = [int]($first / $framesPerMs)

    $from = [Math]::Max(0, $first - [int]($LeadMs * $framesPerMs))
    $to = [Math]::Min($sampleCount - 1, $last + [int]($TailMs * $framesPerMs))
    $keep = $to - $from + 1
    $keepBytes = $keep * 2

    # Canonical 44-byte header, which is all a PCM cue needs.
    $out = New-Object byte[] (44 + $keepBytes)
    [void][System.Text.Encoding]::ASCII.GetBytes("RIFF").CopyTo($out, 0)
    [BitConverter]::GetBytes([uint32](36 + $keepBytes)).CopyTo($out, 4)
    [void][System.Text.Encoding]::ASCII.GetBytes("WAVEfmt ").CopyTo($out, 8)
    [BitConverter]::GetBytes([uint32]16).CopyTo($out, 16)
    [BitConverter]::GetBytes([uint16]1).CopyTo($out, 20)
    [BitConverter]::GetBytes([uint16]$channels).CopyTo($out, 22)
    [BitConverter]::GetBytes([uint32]$rate).CopyTo($out, 24)
    [BitConverter]::GetBytes([uint32]($rate * $channels * 2)).CopyTo($out, 28)
    [BitConverter]::GetBytes([uint16]($channels * 2)).CopyTo($out, 32)
    [BitConverter]::GetBytes([uint16]16).CopyTo($out, 34)
    [void][System.Text.Encoding]::ASCII.GetBytes("data").CopyTo($out, 36)
    [BitConverter]::GetBytes([uint32]$keepBytes).CopyTo($out, 40)
    [Array]::Copy($bytes, $dataAt + $from * 2, $out, 44, $keepBytes)

    [System.IO.File]::WriteAllBytes($Path, $out)
    return $startedAtMs
}

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$specDir = Join-Path $root "docs\specs"
$outDir = $PSScriptRoot

if (-not (Test-Path $specDir)) { throw "specifications not found at $specDir" }

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer

$available = $synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }
if ($available -notcontains $Voice) {
    throw "voice '$Voice' is not installed. Available: $($available -join ', ')"
}
$synth.SelectVoice($Voice)
$synth.Rate = $Rate

# 22.05 kHz, 16-bit, mono. Speech needs no more, and the bank ships inside the
# application bundle, which 4.4 lists as the cost of this option.
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    22050,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono
)

# Everything that will be spoken, collected before anything is synthesised, so
# that the loop below has exactly one job. The text is never written into this
# script: each line is read from the file that owns it, which is the only
# arrangement in which the wording quoted in Chapter 7 and the wording inside
# the .wav cannot drift apart.
$items = @()

foreach ($specFile in Get-ChildItem -Path $specDir -Filter *.json | Sort-Object Name) {
    $spec = Get-Content $specFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json

    # Shared guidance: the lines that read the same whatever is being trained,
    # which is why they live in guidance.json and not in three copies.
    if ($null -ne $spec.lines) {
        foreach ($code in $spec.lines.PSObject.Properties.Name) {
            foreach ($lang in $spec.lines.$code.PSObject.Properties.Name) {
                $l = $spec.lines.$code.$lang
                $items += [pscustomobject]@{
                    exercise = $spec.id; code = $code; lang = $lang
                    kind = "guidance"; asset = $l.asset; text = $l.text
                }
            }
        }
    }

    # Framing, spoken while the user is still walking backwards into position.
    if ($null -ne $spec.framing -and $null -ne $spec.framing.guidance_asset) {
        $items += [pscustomobject]@{
            exercise = $spec.id; code = "FRAMING"; lang = "en"
            kind = "guidance"; asset = $spec.framing.guidance_asset
            text = $spec.framing.guidance
        }
    }

    # One per calibration measure, in the order the specification lists them.
    if ($null -ne $spec.calibration -and $null -ne $spec.calibration.measures) {
        foreach ($m in $spec.calibration.measures) {
            if ($null -eq $m.instruction_asset) { continue }
            $items += [pscustomobject]@{
                exercise = $spec.id; code = $m.id; lang = "en"
                kind = "guidance"; asset = $m.instruction_asset
                text = $m.instruction
            }
        }
    }

    # Fault cues.
    if ($null -ne $spec.cues) {
        foreach ($code in $spec.cues.PSObject.Properties.Name) {
            foreach ($lang in $spec.cues.$code.PSObject.Properties.Name) {
                $cue = $spec.cues.$code.$lang
                $items += [pscustomobject]@{
                    exercise = $spec.id; code = $code; lang = $lang
                    kind = "cue"; asset = $cue.asset; text = $cue.text
                }
            }
        }
    }
}

# Two lines claiming one filename would leave the second silently wearing the
# first one's words, which is the sort of defect that is only found by a user.
$clash = @($items | Group-Object asset | Where-Object { $_.Count -gt 1 })
if ($clash.Count -gt 0) { throw "more than one line claims: $($clash.Name -join ', ')" }

$written = 0
$skipped = 0
$manifest = @()

foreach ($item in $items) {
    $path = Join-Path $outDir $item.asset

    if ((Test-Path $path) -and -not $Force) {
        Write-Host ("  skip  {0,-46} exists" -f $item.asset)
        $skipped++
    } else {
        $synth.SetOutputToWaveFile($path, $format)
        $synth.Speak($item.text)
        $synth.SetOutputToNull()
        $padMs = Trim-Silence -Path $path
        Write-Host ("  write {0,-46} {1,6} bytes  cut {2,3} ms  `"{3}`"" -f `
            $item.asset, (Get-Item $path).Length, $padMs, $item.text)
        $written++
    }

    # Durations are read back from the files rather than predicted, so the
    # manifest describes the bank that exists.
    $b = [System.IO.File]::ReadAllBytes($path)
    $durationMs = [int](([BitConverter]::ToUInt32($b, 40) / 2) / 22.050)

    $manifest += [pscustomobject]@{
        exercise    = $item.exercise
        code        = $item.code
        lang        = $item.lang
        kind        = $item.kind
        asset       = $item.asset
        text        = $item.text
        duration_ms = $durationMs
    }
}

$synth.Dispose()

# A manifest so the app module, and Chapter 7, can both be checked against the
# bank without opening a directory of binary files.
$manifestPath = Join-Path $outDir "manifest.json"
$payload = [pscustomobject]@{
    generated   = (Get-Date -Format "yyyy-MM-dd")
    voice       = $Voice
    rate        = $Rate
    format      = "22050 Hz, 16-bit PCM, mono"
    source      = "docs/specs/*.json"
    lead_ms     = $LeadMs
    tail_ms     = $TailMs
    trim_note   = "Leading silence is cut because 4.1 budgets time to audible audio, not to the first sample of a silent file."
    kinds       = "cue: spoken during a set, NFR-P1 is measured through these. guidance: spoken before a set and never during one."
    cues        = $manifest
}
$json = $payload | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($manifestPath, $json.Replace("`r`n", "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))

$cueCount = @($manifest | Where-Object { $_.kind -eq "cue" }).Count
$sayCount = @($manifest | Where-Object { $_.kind -eq "guidance" }).Count

Write-Host ""
Write-Host "$written written, $skipped skipped"
Write-Host "$($manifest.Count) in manifest.json: $cueCount fault cues, $sayCount guidance lines"
