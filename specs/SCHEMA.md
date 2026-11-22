# Exercise specification format

The artifact 4.6 promises. One file defines one exercise; a generic evaluator
executes it; adding an exercise changes no application code.

These files live here while they are design artifacts. They move into the client's
assets when the client exists, and the report's section 7.5 is written from this
document.

**Format: JSON.** 4.6 did not fix a serialisation format, so this is an
implementation choice made here. JSON parses with no added dependency and
validates through `kotlinx.serialization`, which matters because the validation
is load-bearing: 4.6 requires that a specification cannot be authored without
a `provenance` entry, and that requirement is only real if the parser rejects a
file that omits one. YAML would read better and would cost a library; the
readability argument in 4.6 was about structure rather than punctuation, and
a trainer reviewing a fault definition reads the `description` and `provenance`
fields, not the braces.

---

## The eight blocks

| Block | Required | Purpose |
|---|---|---|
| `landmarks` | yes | Which of the 33 BlazePose landmarks this exercise depends on |
| `rep_driver` | yes | The single scalar signal whose extrema segment repetitions |
| `phases` | yes | Named intervals over that signal |
| `calibration` | no | A per-user measurement taken before the first set |
| `faults` | yes | One entry per detectable fault, each with its provenance |
| `cues` | yes | Fault code to audio asset |
| `trigger` | yes | Confidence floor, debounce, rate limit |
| `framing` | yes | The camera placement the exercise assumes |

`calibration` is optional: an exercise whose thresholds are all absolute omits it.

## Metadata, which is not a block

Alongside the eight blocks a file carries `id`, `version`, `display_name`,
`status`, `notes`, and `excluded_faults`. None of these affects evaluation, and
they are deliberately not blocks: 4.6 fixed the count at eight and that
count is a claim about the behavioural surface, not about the file.

`excluded_faults` is the one worth explaining. It records a fault that was
considered for this exercise and deliberately left out:

```json
"excluded_faults": [
  {
    "code": "SQ_KNEE_VALGUS",
    "description": "...",
    "reason": "...",
    "why_not_change_the_framing": "...",
    "status": "excluded_from_mvp",
    "revisit_when": "...",
    "decided": "2026-10-31"
  }
]
```

This exists because of where the question gets asked. 4.6 claims the
specification is the single artifact defining an exercise, and section 2.6
claims FORMA's distinguishing property is being answerable for what it does
and does not do. A reviewer who asks *why does this not detect knees caving
in* should find the answer in the file that defines the squat, not only in a
decision record three chapters away. A fault silently absent and a fault
deliberately excluded look identical from outside unless the file says which
it is.

---

## Landmark names

Symbolic throughout. The evaluator resolves them to BlazePose indices.

| Name | Index | | Name | Index |
|---|---|---|---|---|
| `NOSE` | 0 | | `LEFT_KNEE` | 25 |
| `LEFT_SHOULDER` | 11 | | `RIGHT_KNEE` | 26 |
| `RIGHT_SHOULDER` | 12 | | `LEFT_ANKLE` | 27 |
| `LEFT_ELBOW` | 13 | | `RIGHT_ANKLE` | 28 |
| `RIGHT_ELBOW` | 14 | | `LEFT_HEEL` | 29 |
| `LEFT_WRIST` | 15 | | `RIGHT_HEEL` | 30 |
| `RIGHT_WRIST` | 16 | | `LEFT_FOOT_INDEX` | 31 |
| `LEFT_HIP` | 23 | | `RIGHT_FOOT_INDEX` | 32 |
| `RIGHT_HIP` | 24 | | | |

`LEFT_HEEL` through `RIGHT_FOOT_INDEX` have no COCO-17 equivalent. They are the
reason 4.2 eliminated three engines before any of them was
timed. **F1 no longer holds.** `SQ_HEEL_LIFT` was the only fault that used
these four landmarks and it was removed on 2026-11-03, so every fault the MVP
retains is expressible in COCO-17 and the engine decision rests on the
measured benchmark instead.

---

## Measurement functions

The expressiveness ceiling 4.6 accepts is set here. An exercise needing a
measurement not on this list cannot be expressed, and extending the list is a
code change, which is the ceiling behaving as documented rather than failing.

| `fn` | Arguments | Returns |
|---|---|---|
| `angle` | three landmarks `a, b, c` | Interior angle at `b`, degrees |
| `angle_from_vertical` | two landmarks `a, b` | Angle of segment `a-b` from image vertical, degrees |
| `y_delta` | two landmarks `a, b` | Signed vertical difference, `a.y - b.y` |
| `dist` | two landmarks `a, b` | Euclidean distance |
| `ratio` | two `dist` expressions | Dimensionless ratio |
| `mean_y` | a list of landmarks | Mean vertical position |
| `signed_offset_from_line` | a point `p` and two landmarks `a, b` | Perpendicular distance of `p` from the line `a-b`, **signed**: positive when `p` lies on the larger-y side, meaning below the line |
| `signed_angle_between_segments` | four landmarks `a, b, c, d` | Angle of segment `a-b` from vertical, minus that of segment `c-d`. Positive when the first segment is further from vertical |

`signed_angle_between_segments` exists because a published criterion required
it, not because an exercise did. The FMS deep squat defines correct trunk
position as *upper torso parallel with tibia or toward vertical* - a comparison
between two unconnected segments in the same frame, which no three-point angle
expresses. Added 2026-10-31; see `SOURCES.md` and 4.6.1 of the report.

`signed_offset_from_line` was added for push-up and plank, and the sign is the
whole reason it exists. Hip sag and hip pike are opposite deviations of the same
body line and take opposite cues, but the interior angle at the hip falls below
180 degrees for both and cannot distinguish them. Added on 2026-10-31, at a cost
recorded in 4.6.1 of the report.

Any measurement may carry `"aggregate": "mean_of_sides"`, which evaluates it for
both the left and the right landmark set and averages the two. The specification
then names only the left-hand landmarks, and the evaluator substitutes their
right-hand counterparts for the second evaluation.

All coordinates are the normalised image coordinates the engine emits. A raw
`y_delta` or `dist` therefore varies with the user's distance from the camera
and must be normalised, either by `normalise_by` or by use inside a `ratio`.

`normalise_by` accepts a named scale: `foot_length`, `shoulder_width`,
`hip_width`, `torso_length`, `shoulder_ankle_length`. Each is itself a `dist`
over landmarks, computed per frame.

## What an exercise reports

`rep_driver` carries a `reports` field saying what the user is shown and what
the session record stores.

```json
"reports": { "type": "count" }
"reports": { "type": "duration", "phase": "holding" }
```

Most exercises count repetitions. A hold does not: a plank's result is seconds,
and reporting "1 repetition" for a ninety-second hold would be true and useless.

This field is where the format bent to fit the second kind of movement, and the
cost is recorded in 4.6.1 of the report rather than absorbed quietly. `rep_driver`
still does the same job in both cases, segmenting a signal with hysteresis;
what differs is only whether the segments are counted or timed.

---

## Calibration

The block declares one or more named measurements, each a static hold.

```json
"calibration": {
  "id": "squat_range",
  "safe_unsupervised": true,
  "rationale": "<why nothing measured here can itself be done wrong>",
  "measures": [
    {
      "id": "standing_knee_angle",
      "instruction": "Stand up straight and hold still.",
      "signal": { "fn": "angle", "args": [...], "aggregate": "mean_of_sides" },
      "statistic": "median" | "min_sustained" | "max_sustained",
      "hold_ms": 1500,
      "stability_tolerance": 4.0,
      "plausible_range": [155.0, 185.0]
    }
  ],
  "on_implausible": "reject_and_repeat"
}
```

A measurement carrying `"during": "<another measure id>"` is taken inside that
measure's hold instead of prompting for its own, and omits `instruction`,
`hold_ms` and `stability_tolerance`. This keeps calibration short: the squat
takes three measurements from two spoken steps. Every spoken step is time
before the user's first set, and that is the budget calibration has to live in.

`safe_unsupervised` and `rationale` are required and are not decoration.
4.5 accepted that calibration has a failure mode of its own: a user who
calibrates with poor form encodes that form as their reference. The containment
is that calibration measures only a static hold or a reach, never a repetition.
The field forces the author to say why each measurement meets that bar.

`plausible_range` is the guard against a calibration taken while the user was
walking back to the camera. A reading outside it is rejected and the
instruction repeats, rather than being silently adopted as that user's normal.

## Thresholds

```json
"threshold": { "type": "absolute", "value": -0.25 }
"threshold": { "type": "relative", "calibration_id": "max_squat_depth_knee_angle", "factor": 1.15 }
"threshold": { "type": "relative", "calibration_id": "bottom_torso_angle",        "offset": 15.0 }
```

A `relative` threshold carries **either** `factor` (multiplicative) **or**
`offset` (additive), never both. Which one is right depends on the signal.
A ratio or a distance scales, so it takes a `factor`. An angle does not: ten
degrees past a reference of thirty and ten degrees past a reference of sixty
are the same deviation, while `factor: 1.3` applied to each is not. The loader
rejects a threshold carrying both.

Hysteresis bounds in `rep_driver` take the same two forms:

```json
"descent_enter": { "type": "relative", "calibration_id": "standing_knee_angle", "offset": -20.0 }
```

A `relative` threshold resolves against the value this user produced during
calibration. 4.5 adopted this in preference to absolute values because
ratios normalise for limb length but not for mobility, and two users with the
same proportions and different ankle mobility are not the same case.

An exercise using a `relative` threshold must declare the matching
`calibration` measurement, and the loader rejects the file if it does not.

---

## Provenance

Every fault carries one. It is not documentation; it is the field that makes
G5 checkable, and the loader rejects a fault without it.

```json
"provenance": {
  "status": "confirmed" | "taxonomy_confirmed" | "pending",
  "taxonomy_source": "<citation for: this is a recognised fault>",
  "threshold_source": "<citation or clip set for: this is the number>",
  "note": "<what remains unresolved>"
}
```

Three states, because the two fields can be satisfied independently:

| Status | Meaning | Chapter 11 treatment |
|---|---|---|
| `pending` | No taxonomy source. The fault is this project's own | Excluded from results entirely |
| `taxonomy_confirmed` | The deviation is a recognised fault with a cited criterion. The firing threshold is this project's | Reported, with the threshold's derivation stated |
| `confirmed` | Both the fault and its threshold are sourced | Reported as a headline result |

The middle state was added on 2026-10-31 after the taxonomy search, and the
reason it was needed is itself a finding: **every source located establishes
*that* a deviation is a fault and gives an observable criterion for it, and not
one gives the quantity at which it begins.** The two-field split had anticipated
exactly this, so the search vindicated the design rather than breaking it.

Splitting the two source fields is deliberate. *This is a real fault that
trainers recognise* and *this is the number at which it fires* are different
claims with different evidence, and conflating them is how an invented threshold
acquires a citation that does not support it.

---

## What the trigger block actually guarantees

Two of these fields promise less than their names suggest, and both were
measured on 2026-11-03 rather than reasoned about. The record is here because
a specification author reading `priority` or `debounce_ms` will otherwise
assume the stronger reading.

**`priority` breaks ties among faults raised in the same frame.** It is not a
scheduler. When two faults become speakable at different moments, the one that
became speakable first is spoken, whatever the ranking says, because the
trigger sees only the faults currently raised and the ranking only sorts that
list. Whether the ranking appears to hold therefore depends on the phase
scoping of the faults involved: a fault scoped to `bottom` and `ascent` keeps a
continuous clock across that boundary, while one scoped to `bottom` alone
restarts every repetition. In the squat these happen to line up with the
declared ranking. **An exercise whose top-ranked fault has the narrower phase
scope would invert the order, and nothing in the format prevents that.**

**`debounce_ms` is per exercise, but the time a fault has available to satisfy
it is per fault**, because a fault can only persist for as long as the phases
it is scoped to. A fault scoped to every phase accumulates continuously across
repetitions. A fault scoped to one short phase gets one window per repetition,
and if that window is shorter than `debounce_ms` the fault can never be spoken
at any tempo. Both MVP counting exercises were authored without this in mind
and both were affected:

| Fault | Scope | Authored `debounce_ms` | Measured outcome |
|---|---|---|---|
| `SQ_HEEL_LIFT` | `bottom`, `ascent` | 1500 | Needed a 3,630 ms repetition. Corrected to 400 ms; now speaks from 990 ms. **Fault removed 2026-11-03; row kept as the record of why the field changed** |
| `PU_DEPTH_INSUFFICIENT` | `bottom` | 1500 | Needs a **1,980 ms pause at the bottom**. Still mute at 400 ms, which needs 660 ms. Open |

`min_frames` already rejects transients, it is per fault, and it is expressed
in frames. The overlap between the two mechanisms is the subject of an open
decision in 4.6 of the report.

---

## Validation performed at load

1. Every landmark named is in the `landmarks` block.
2. Every `faults[].code` resolves to an entry in `cues`. A fault with no cue is a silent fault (4.4, 4.6).
3. Every name in `faults[].phases` is a declared phase.
4. Every `relative` threshold names a `calibration_id` that the `calibration` block declares.
5. Every fault has a `provenance` object with a non-empty `status`.
6. `rep_driver.hysteresis` enter and exit bounds do not cross.
7. Every code in `trigger.priority` is a declared fault, and every declared fault appears in it. An unranked fault has no defined behaviour when it fires alongside another.
8. If `calibration` is present, `safe_unsupervised` is `true`. A specification declaring otherwise does not load, because 4.5 permits no other kind.
9. A `relative` threshold carries exactly one of `factor` or `offset`.
10. Every `during` in a calibration measure names an earlier measure in the same list.
11. No code in `excluded_faults` appears in `faults`, in `cues`, or in `trigger.priority`. A fault cannot be both excluded and live, and the commonest way to get there is removing it from one list and forgetting the others.

A file failing any of these does not load. Failing at load is the point: the
alternative is discovering mid-set that a fault has been firing silently.
