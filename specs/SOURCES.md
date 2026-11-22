# Fault taxonomy sources

The evidence behind `provenance.taxonomy_source` in the three specification
files. Searched 2026-10-31.

G5 requires fault definitions adopted from a taxonomy already annotated by
qualified practitioners in published work, rather than invented here. This
document records what was found, what was not, and the transfer argument where
a source covers a close variant rather than the exact movement.

---

## Primary source: the Functional Movement Screen

**Cook G, Burton L, Hoogenboom B. Pre-participation screening: the use of
fundamental movements as an assessment of function - Part 1.** *North American
Journal of Sports Physical Therapy* 2006;1(2):62-72. PMID 21522216, PMCID
PMC2953313.

**Cook G, Burton L, Hoogenboom B. Pre-participation screening: the use of
fundamental movements as an assessment of function - Part 2.** *North American
Journal of Sports Physical Therapy* 2006;1(3):132-139. PMID 21522225, PMCID
PMC2953359.

*(Republished with Voight M as co-author in the International Journal of Sports
Physical Therapy, 2014;9(3):396-409 and 9(4):549-563.)*

**Why this source satisfies G5.** The FMS is a screening instrument developed by
physical therapists, published in a peer-reviewed physical therapy journal, and
in use for two decades. Its criteria were established by qualified clinicians
rather than by the authors of a computer vision paper, and each criterion is
stated as an observable condition rather than as a label. That is precisely the
bar 7.1 sets.

### Deep Squat - criteria quoted verbatim from Part 1

| Score | Criteria |
|---|---|
| **III** | "Upper torso is parallel with tibia or toward vertical" · "Femur below horizontal" · "Knees are aligned over feet" · "Dowel aligned over feet" |
| **II** | The same criteria, achieved only with a "2x6 board required under feet" - the test is repeated with the heels elevated |
| **I** | "Tibia and upper torso are not parallel" · "Femur is not below horizontal" · "Knees are not aligned over feet" · "Lumbar flexion is noted" |

The protocol states the squat "should be assumed with the heels on the floor".

**Mapping to this project's fault set:**

| Criterion | Fault |
|---|---|
| Femur below horizontal | `SQ_DEPTH_INSUFFICIENT` |
| Heels on the floor; a board under the heels downgrades the score | `SQ_HEEL_LIFT` - **excluded from the MVP on 2026-11-03**, see below |
| Upper torso parallel with tibia or toward vertical | `SQ_FORWARD_LEAN` |
| Knees aligned over feet | `SQ_KNEE_VALGUS` - excluded on framing grounds, not for want of a source |

### Trunk Stability Push-up - criterion quoted verbatim from Part 2

> "The body should be lifted as a unit; no 'lag' should occur in the lumbar
> spine when performing this push-up."

**Mapping:** this is `PU_HIP_SAG`.

### SQ_HEEL_LIFT: sourced, and excluded anyway

**Removed from the MVP fault set on 2026-11-03.** The criterion above is not in
doubt and is kept here deliberately, so the exclusion is never misread as a gap
in the evidence. Two reasons, in order of weight.

**A cue cannot fix it.** Heels rising at the bottom of a squat is in most cases
a limit on ankle dorsiflexion rather than a choice. Telling a user to keep
their heels down does not give them ankle range. Every other fault in the set
names something the user can do differently on the next repetition.

**The measurement did not work.** In `set-20261111-084459` the divisor,
`foot_length`, varied by a factor of 1.89 within one set, from 0.128396 to
0.067830, for a foot that does not change length. Repetitions 2 and 7 carry the
same raw displacement to within two percent and report severities of -0.314328
and -0.608081. The heel landmark is not at fault: its visibility was 0.891 to
0.936 in those frames, and in `sweep-20260910-215904` it cleared the 0.6 floor
in 100 percent of frames in all six configurations. The likely cause, stated as
a hypothesis, is that x is normalised by image width and y by image height on a
720 by 1280 frame, so a horizontal length and a vertical rise are measured on
differently scaled axes and the ratio does not cancel. That predicts 1.78
against an observed 1.89.

Full record in `docs/specs/bodyweight-squat.json` under `excluded_faults`.

### The transfer argument, stated

The FMS Deep Squat is performed with a dowel held overhead, which adds a
shoulder and thoracic component this project does not assess. The lower-body
criteria - femur depth, knee alignment over the feet, heels in contact with the
floor, and torso angle relative to the tibia - are properties of the squat
itself and transfer directly to an unloaded bodyweight squat.

This is a transfer, and it is a far shorter one than the alternative discussed
below. It is stated here rather than left for a reader to notice.

### One criterion that improved the design

**"Upper torso is parallel with tibia or toward vertical" is a relative
criterion**, comparing the torso to the tibia within the same frame rather than
to a fixed angle.

This is better than the per-user calibration this project had designed for
forward lean, and for the same underlying reason: tibia angle moves with ankle
dorsiflexion and with squat depth, so comparing the torso against it normalises
automatically for the two body factors that made an absolute threshold
indefensible. It requires no calibration at all.

The rule was rewritten to implement the published criterion directly. The
cost was one measurement function, `signed_angle_between_segments`, added
because the source demanded it rather than because an exercise did.

---

## Secondary source, corroborating: Fitness-AQA

**Parmar P, Gharat A, Rhodin H. Domain Knowledge-Informed Self-Supervised
Representations for Workout Form Assessment.** ECCV 2022. arXiv:2202.14019.

An in-the-wild dataset of three exercises - BackSquat, BarbellRow,
OverheadPress - **annotated by expert trainers** across seven error types. The
BackSquat errors include Knees Inward, Knees Forward, Convex Rounded Back,
Concave Rounded Back and Shallow Squat.

**Used as corroboration only, not as the primary citation.** All three exercises
are barbell movements, which 1.3.3 places out of scope, and the bar changes both
the mechanics and the reason a fault matters. Its value here is that an
independent expert-annotated taxonomy names shallow depth and knees-inward as
faults, which supports the FMS criteria rather than standing in for them.

---

## Plank: a recognised criterion, no single authority

No equivalent of the FMS exists for the front plank. What does exist, and is
consistent across the literature, is the **termination criterion** of the plank
endurance test: the hold ends when neutral alignment is lost, and the two named
failure modes are the hips lowering and the pelvis rising.

| Source | Criterion |
|---|---|
| Plank performance and low back pain, *J Clin Med* 2025;14(11):3926, doi 10.3390/jcm14113926 | Hold ends on loss of neutral alignment, "such as excessive lumbar extension or a pelvic drop" |
| Prone plank protocol, Slovenian Armed Forces injury prevention trial, NCT03415464 | Test ends when the pelvis moves **5 cm or more up or down** |
| Front plank criterion validity, ADULT-FIT project, *Appl Sci* 2025;15(5):2722 | Notes protocols differ in how loss of neutral position ends the test |

**Mapping:** hips lowering is `PL_HIP_SAG`; pelvis rising is `PL_HIP_PIKE`. The
two faults this project declares are exactly the two failure modes the
literature names.

**What is missing and is stated as missing:** these are test-termination rules
rather than a taxonomy annotated by trainers for coaching purposes, and no
single authority defines them. The 5 cm figure is the only quantified threshold
located, and it comes from one trial protocol rather than from a standard.

---

## Remaining gaps

| Fault | Status | What is missing |
|---|---|---|
| `PU_HIP_PIKE` | No direct source | The FMS Trunk Stability Push-up names lumbar lag, not piking. The fault is the signed counterpart of a sourced fault, which is an argument from symmetry and not a citation |
| `PU_DEPTH_INSUFFICIENT` | Candidate only | Standardised push-up tests use a 90-degree elbow angle or chest contact as the depth criterion. Sources located are secondary, they disagree on which criterion applies, and at least one reports **low inter-scorer reliability for the 90-degree criterion specifically** - which is itself a finding worth carrying into 11.5 |
| **All thresholds** | Open | No source located gives a numeric threshold for any fault. `threshold_source` remains null throughout, and 11.3's reporting gate applies |

**No threshold in any specification is sourced.** Every source found establishes
*that* a deviation is a fault and gives an observable condition for it; none
gives the quantity at which it begins. This is exactly the separation the
two-field provenance record was built for, and the fact that the search produced
taxonomy sources and no threshold sources is the clearest possible vindication
of splitting the fields.

Thresholds are therefore derived from recorded clips, with the procedure and
the clip set recorded per threshold, as 4.5 requires.
