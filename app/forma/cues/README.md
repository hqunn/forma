# The cue bank

Eight pre-synthesised audio cues, one per declared fault across the three MVP
exercises. This is the whole of the audio half of 4.4.

4.4 compared four feedback paths and chose **O3, a pre-authored cue bank**.
The property that decided it is that the latency budget in 4.1 is met by
construction rather than by optimisation: there is no network call, no
generation step and no speech synthesis anywhere in the live path. A fault code
is a map key, and the only work at fault time is a lookup and the start of
playback.

## Generating

```
pwsh app/forma/cues/generate-cue-bank.ps1 -Force
```

The script reads `docs/specs/*.json` and writes the `.wav` file each cue
already names. **The specifications are the single source of both the filenames
and the wording**, so the file on disk cannot drift from the text quoted in
Chapter 7. Changing a cue means editing the specification and re-running this.

Without `-Force` existing files are left alone, so adding a ninth cue to a
specification regenerates only that one.

## What is in the bank

| Exercise | Fault | Asset | Text |
|---|---|---|---|
| Squat | `SQ_DEPTH_INSUFFICIENT` | `cue_squat_depth_en.wav` | "A little deeper." |
| Squat | `SQ_FORWARD_LEAN` | `cue_squat_chest_en.wav` | "Chest up." |
| Push-up | `PU_HIP_SAG` | `cue_pushup_hips_up_en.wav` | "Lift your hips." |
| Push-up | `PU_HIP_PIKE` | `cue_pushup_hips_down_en.wav` | "Drop your hips." |
| Push-up | `PU_DEPTH_INSUFFICIENT` | `cue_pushup_depth_en.wav` | "All the way down." |
| Plank | `PL_HIP_SAG` | `cue_plank_hips_up_en.wav` | "Lift your hips." |
| Plank | `PL_HIP_PIKE` | `cue_plank_hips_down_en.wav` | "Drop your hips." |

22,050 Hz, 16-bit PCM, mono. 0.68 to 1.23 seconds each, 336 KB for the bank.
`manifest.json` carries the same table plus the measured duration of each file,
so a consumer can check the bank without opening eight binary files.

The push-up and the plank duplicate two cues by text and keep them as separate
assets. That is deliberate: a cue belongs to an exercise's specification, and
sharing the file would couple two specifications through the filesystem.

## Why the silence is trimmed

**This is the part worth reading.** The synthesiser pads every utterance with
silence before the first word, measured at 95 to 111 ms across the eight cues.

4.1 budgets 60 ms from audio dispatch to the first sample. A file whose first
sample is silent satisfies that line as written and fails the requirement as
meant, because the 350 ms figure is derived from the user hearing the cue in
time to act on it. Shipping the padding would have put about 100 ms of
unbudgeted latency on the governing requirement of the system, hidden inside a
binary asset where neither the test suite nor the budget table would have shown
it.

The generator cuts the lead and leaves a uniform 8 ms, enough that playback does
not begin mid-waveform and click. 4.1's line now reads *first audible sample*.

## The voice is not a product decision

A Windows SAPI voice, chosen because it is offline, free and reproducible on
the development machine. It is adequate for the proof of concept and no more.
Every consumer references the filename rather than the voice, so replacing it
means swapping the synthesiser in the generator and re-running. If a recorded
human voice is used later, the files simply land in this directory under the
same names.
